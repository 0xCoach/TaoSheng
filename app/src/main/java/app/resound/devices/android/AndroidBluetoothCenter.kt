package app.resound.devices.android

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import app.resound.devices.driver.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** 主线程串行使用；记录尝试启动的时间，失败的系统调用也消耗配额。 */
internal class ScanStartBudget(private val clock: ClockPort) {
    private val starts = ArrayDeque<Long>()
    fun waitMillis(): Long {
        val now = clock.monotonicMillis()
        while (starts.isNotEmpty() && now - starts.first() >= 30_000) starts.removeFirst()
        return if (starts.size < 5) 0 else (30_000 - (now - starts.first())).coerceAtLeast(1)
    }
    fun started() { starts.addLast(clock.monotonicMillis()) }
}

/** AppGraph 唯一实例；多个收集器共用物理扫描，筛选集合扩大时才重启。 */
@SuppressLint("MissingPermission")
class AndroidBluetoothCenter(
    private val context: Context,
    private val scope: CoroutineScope,
    clock: ClockPort,
) {
    private data class Listener(val services: Set<String>, val send: (Advertisement) -> Unit, val fail: (Throwable) -> Unit)
    private val listeners = mutableMapOf<Any, Listener>()
    private val budget = ScanStartBudget(clock)
    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null
    private var filters: Set<String> = emptySet()
    private var pending: Job? = null
    fun scan(services: Set<String> = emptySet()): Flow<Advertisement> = callbackFlow {
        val normalized = services.map { ParcelUuid.fromString(it).uuid.toString() }.toSet()
        val token = Any()
        try {
            withContext(Dispatchers.Main.immediate) {
                listeners[token] = Listener(normalized, { ad -> trySend(ad) }, { error -> close(error) })
                reconcile()
            }
            awaitClose { }
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) { listeners.remove(token); reconcile() }
        }
    }
    private fun stop() {
        val old = callback; callback = null
        try { if (old != null) scanner?.stopScan(old) } catch (_: SecurityException) { }
        scanner = null
    }
    private fun reconcile() {
        pending?.cancel(); pending = null
        if (listeners.isEmpty()) { stop(); return }
        val desired = if (listeners.values.any { it.services.isEmpty() }) emptySet()
            else listeners.values.flatMap { it.services }.toSet()
        if (callback != null && (filters.isEmpty() || (desired.isNotEmpty() && filters.containsAll(desired)))) return
        stop()
        pending = scope.launch(Dispatchers.Main.immediate) {
            delay(budget.waitMillis())
            if (listeners.isEmpty()) return@launch
            try {
                val permissions = if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                    else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
                if (permissions.any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED })
                    throw DeviceException(ErrorCode.PermissionDenied)
                val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                val scan = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: throw DeviceException(ErrorCode.Disconnected)
                start(scan, desired)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val error = if (e is SecurityException) DeviceException(ErrorCode.PermissionDenied) else e
                listeners.values.toList().forEach { it.fail(error) }; stop()
            }
        }
    }
    private fun start(scan: BluetoothLeScanner, desired: Set<String>) {
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val self = this
                scope.launch(Dispatchers.Main.immediate) {
                    if (callback !== self) return@launch
                    try {
                        val record = result.scanRecord
                        val manufacturers = record?.manufacturerSpecificData
                        val ad = Advertisement(result.device.address, record?.deviceName ?: result.device.name,
                            record?.serviceUuids.orEmpty().map { it.uuid.toString() }.toSet(),
                            (0 until (manufacturers?.size() ?: 0)).associate { manufacturers!!.keyAt(it) to manufacturers.valueAt(it).copyOf() }, result.rssi)
                        listeners.values.toList().filter { it.services.isEmpty() || it.services.any(ad.services::contains) }.forEach { it.send(ad) }
                    } catch (e: SecurityException) {
                        listeners.values.toList().forEach { it.fail(DeviceException(ErrorCode.PermissionDenied, cause = e)) }; stop()
                    }
                }
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { onScanResult(0, it) } }
            override fun onScanFailed(errorCode: Int) {
                val self = this
                scope.launch(Dispatchers.Main.immediate) {
                    if (callback === self) {
                        listeners.values.toList().forEach { it.fail(DeviceException(ErrorCode.Disconnected, "scan=$errorCode")) }; stop()
                    }
                }
            }
        }
        filters = desired; scanner = scan; callback = cb
        budget.started()
        scan.startScan(desired.map { ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(it)).build() }.ifEmpty { null },
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
    }
}
