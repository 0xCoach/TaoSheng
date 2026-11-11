package app.resound.devices.plugins.d3200

import android.bluetooth.le.ScanResult
import android.content.Context
import app.resound.ResoundApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class D3200Details(val firmware: String = "", val light: String = "会话未连接", val sync: String = "保存录音后补传")

/** Settings/scan metadata only. Connections, routes and transfer queues belong to the upstream device framework. */
class D3200Coordinator(private val app: ResoundApp) {
    internal val prefs = app.getSharedPreferences("d3200", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow<Map<String, D3200Details>>(emptyMap())
    val details = mutable.asStateFlow()
    internal fun report(address: String, change: (D3200Details) -> D3200Details) {
        mutable.update { it + (address to change(it[address] ?: D3200Details())) }
    }
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
