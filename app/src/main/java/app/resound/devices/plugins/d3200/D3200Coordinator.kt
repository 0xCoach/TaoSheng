package app.resound.devices.plugins.d3200

import android.bluetooth.le.ScanResult
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import app.resound.ResoundApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class D3200Details(
    val light: String = "会话未连接", val sync: String = "保存录音后补传",
    val info: D3200DeviceInfo = D3200DeviceInfo(), val updatedAt: Long = 0,
    val connected: Boolean = false, val connecting: Boolean = false,
    val receiving: Boolean = false, val backfilling: Boolean = false,
    val busy: Boolean = false, val message: String = "", val error: String? = null,
) {
    val connectionLabel get() = when {
        connecting -> "正在连接"; !connected -> "未连接"; backfilling -> "已连接 · 正在补传"
        receiving -> "已连接 · 正在接收音频"; else -> "已连接 · 等待音频"
    }
}

/** Settings and one shared session lease per physical device; capture/transfer queues remain upstream-owned. */
class D3200Coordinator(private val app: ResoundApp) {
    internal val prefs = app.getSharedPreferences("d3200", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow<Map<String, D3200Details>>(emptyMap())
    private val sessions = ConcurrentHashMap<String, D3200Connection>()
    private val leases = ConcurrentHashMap<String, Mutex>()
    val details = mutable.asStateFlow()
    internal fun report(address: String, change: (D3200Details) -> D3200Details) {
        mutable.update { it + (address to change(it[address] ?: cached(address))) }
    }
    fun watch(address: String) { report(address) { it } }
    private fun cached(address: String): D3200Details = runCatching {
        val j = JSONObject(prefs.getString("info:$address", "{}")!!)
        fun number(key: String) = if (j.has(key) && !j.isNull(key)) j.getInt(key) else null
        fun flag(key: String) = if (j.has(key) && !j.isNull(key)) j.getBoolean(key) else null
        D3200Details(info = D3200DeviceInfo(number("battery"), flag("charging"), number("boxBattery"), flag("boxCharging"),
            j.optString("firmware"), j.optString("boxFirmware"),
            if (j.has("totalKb")) j.getLong("totalKb") else null, if (j.has("freeKb")) j.getLong("freeKb") else null,
            number("recording")), updatedAt = j.optLong("at"))
    }.getOrDefault(D3200Details())
    internal fun deviceInfo(address: String, info: D3200DeviceInfo) {
        val at = System.currentTimeMillis()
        report(address) { it.copy(info = info, updatedAt = at) }
        val j = JSONObject().put("battery", info.battery).put("charging", info.charging)
            .put("boxBattery", info.boxBattery).put("boxCharging", info.boxCharging)
            .put("firmware", info.firmware).put("boxFirmware", info.boxFirmware)
            .put("totalKb", info.totalKb).put("freeKb", info.freeKb).put("recording", info.recording).put("at", at)
        prefs.edit().putString("info:$address", j.toString()).apply()
    }
    internal suspend fun connect(context: Context, device: BluetoothDevice): D3200Connection {
        val lock = leases.getOrPut(device.address) { Mutex() }
        lock.lock()
        return openOwned(context, device, lock)
    }
    private suspend fun openOwned(context: Context, device: BluetoothDevice, lock: Mutex): D3200Connection {
        val released = AtomicBoolean(false)
        fun release(link: D3200Connection? = null) {
            if (released.compareAndSet(false, true)) { if (link != null) sessions.remove(device.address, link); lock.unlock() }
        }
        try {
            return D3200Connection.connect(context, device, this) { release(it) }.also { sessions[device.address] = it }
        } catch (e: Exception) { release(); throw e }
    }
    private suspend fun <T> session(address: String, block: suspend (D3200Connection) -> T): T {
        require(app.cardDevices.devices.value.any { it.address == address && it.cardId == "d3200" }) { "请先配对该录音豆" }
        val adapter = requireNotNull(app.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }) { "请开启蓝牙" }
        // A temporary management connection never starts capture or downloads; the same lease excludes ManagedDevices.
        val lock = leases.getOrPut(address) { Mutex() }
        val owned = withTimeout(20_000) {
            while (true) {
                sessions[address]?.let { return@withTimeout it to false }
                if (lock.tryLock()) return@withTimeout openOwned(app, adapter.getRemoteDevice(address), lock) to true
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE") error("无法获取设备会话")
        }
        try { return block(owned.first) } finally { if (owned.second) withContext(NonCancellable) { owned.first.close() } }
    }
    private fun perform(address: String, action: suspend (D3200Connection) -> String) {
        if (details.value[address]?.busy == true) return
        report(address) { it.copy(busy = true, error = null, message = "正在执行…") }
        app.scope.launch {
            try { val message = session(address, action); report(address) { it.copy(message = message) } }
            catch (_: TimeoutCancellationException) { report(address) { it.copy(error = "连接设备超时，请靠近后重试", message = "") } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { report(address) { it.copy(error = e.message ?: "设备操作失败", message = "") } }
            finally { report(address) { it.copy(busy = false) } }
        }
    }
    fun refresh(address: String) = perform(address) { it.inspect(); "设备信息已刷新" }
    fun record(address: String, start: Boolean) = perform(address) {
        it.control(start)
        if (start) "设备已确认录音；涛声接收音频需选择该音源并开始收音" else "设备已确认暂停；已保持手动暂停状态"
    }
    internal fun held(address: String) = prefs.getBoolean("hold:$address", false)
    internal fun hold(address: String, value: Boolean) { check(prefs.edit().putBoolean("hold:$address", value).commit()) }
    internal fun shouldAutoStart(address: String) = D3200ControlPolicy.autoStart(autoStart, held(address))
    internal fun observe(result: ScanResult) {
        val data = result.scanRecord?.manufacturerSpecificData ?: return
        for (i in 0 until data.size()) {
            val classic = BeanLight.classicAddress(data.keyAt(i), data.valueAt(i)) ?: continue
            prefs.edit().putString("classic:${result.device.address}", classic).apply()
        }
    }
    internal fun classic(address: String): String? = prefs.getString("classic:$address", null)
    internal fun mode(address: String) = classic(address)?.let { prefs.getString("light:d3200:$it", "none") } ?: "none"
    fun setMode(address: String, mode: String) {
        require(mode in listOf("none", "off", "on"))
        val classic = requireNotNull(classic(address)) { "请先搜索设备以读取灯控地址" }
        check(prefs.edit().putString("light:d3200:$classic", mode).commit())
        report(address) { it.copy(light = "已保存灯光偏好，等待设备回执") }
    }
    var autoStart: Boolean
        get() = prefs.getBoolean("startDevice", false)
        set(value) { check(prefs.edit().putBoolean("startDevice", value).commit()) }
    var autoSync: Boolean
        get() = prefs.getBoolean("autoSync", true)
        set(value) {
            val edit = prefs.edit().putBoolean("autoSync", value)
            val addresses = prefs.all.keys.filter { it.startsWith("syncRequest:") }.map { it.substringAfter(':') }.toSet() + details.value.keys
            if (!value) for (address in addresses) {
                val generation = maxOf(System.currentTimeMillis(), request(address) + 1)
                edit.putLong("syncRequest:$address", generation).putLong("syncAck:$address", generation)
            }
            check(edit.commit())
        }
    internal fun request(address: String) = prefs.getLong("syncRequest:$address", 0)
    internal fun requested(address: String) = request(address) > prefs.getLong("syncAck:$address", 0)
    fun requestSync(address: String) { check(prefs.edit().putLong("syncRequest:$address", System.currentTimeMillis()).commit()) }
    internal fun acknowledge(address: String, request: Long) { check(prefs.edit().putLong("syncAck:$address", request).commit()) }
    internal fun journal(address: String) = D3200Journal(app, address)
}
