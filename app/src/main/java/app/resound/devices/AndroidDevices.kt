package app.resound.devices

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.resound.ResoundApp
import app.resound.AppGraph
import app.resound.importer.deviceImport
import app.resound.importer.AndroidAudioImporter
import app.resound.importer.ImportFingerprint
import app.resound.importer.audioFingerprint
import app.resound.update.UpdateDiagnostics
import app.resound.devices.plugins.sonicloud.*
import app.resound.devices.plugins.d3200.*
import app.resound.ui.verify.DeviceOptionsUi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal class AndroidPairedDeviceStore(private val prefs: SharedPreferences) : PairedDeviceStore {
    override fun read(): List<RememberedDevice> = runCatching {
        val rows = JSONArray(prefs.getString("paired", "[]"))
        (0 until rows.length()).map { rows.getJSONObject(it) }.map { row ->
            RememberedDevice(row.getString("address"), row.getString("cardId"), row.getString("name"),
                if (row.has("gapStart")) row.getLong("gapStart") else null,
                if (row.has("clockOffset")) row.getLong("clockOffset") else null)
        }.filter { BluetoothAdapter.checkBluetoothAddress(it.address) }.distinctBy { it.address }
    }.getOrDefault(emptyList())
    override fun write(devices: List<RememberedDevice>) {
        val rows = JSONArray()
        devices.forEach { rows.put(JSONObject().put("address", it.address).put("cardId", it.cardId).put("name", it.name)
            .put("gapStart", it.gapStart).put("clockOffset", it.clockOffset)) }
        check(prefs.edit().putString("paired", rows.toString()).commit()) { "设备设置保存失败" }
    }
}

@SuppressLint("MissingPermission")
internal class AndroidDeviceRadio(private val context: Context,
    private val connectors: Map<String, suspend (BluetoothDevice) -> LinkedDevice> = emptyMap(),
    private val observe: (ScanResult) -> Unit = {},
) : DeviceRadio {
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null
    private fun permission(scan: Boolean) {
        val permissions = if (Build.VERSION.SDK_INT >= 31) listOfNotNull(Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN.takeIf { scan }) else if (scan) listOf(Manifest.permission.ACCESS_FINE_LOCATION) else emptyList()
        if (permissions.any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED })
            throw SecurityException("缺少蓝牙权限")
    }
    override fun startScan(result: (DeviceAdvertisement) -> Unit, error: (String) -> Unit) {
        permission(true)
        stopScan()
        val scan = requireNotNull(adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner)
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, value: ScanResult) {
                try {
                    observe(value)
                    val record = value.scanRecord
                    val manufacturers = record?.manufacturerSpecificData
                    result(DeviceAdvertisement(value.device.address, record?.deviceName ?: value.device.name,
                        record?.serviceUuids.orEmpty().map { it.uuid.toString() }.toSet(),
                        (0 until (manufacturers?.size() ?: 0)).map { manufacturers!!.keyAt(it) }.toSet(), value.rssi))
                } catch (_: SecurityException) { error("蓝牙权限已撤回") }
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { onScanResult(0, it) } }
            override fun onScanFailed(errorCode: Int) { error("蓝牙搜索失败（$errorCode）") }
        }
        scanner = scan; callback = cb
        scan.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
    }
    override fun stopScan() { val old = callback; callback = null; if (old != null) scanner?.stopScan(old); scanner = null }
    override suspend fun pair(address: String, bond: Boolean) {
        permission(false)
        val device = requireNotNull(adapter?.takeIf { it.isEnabled }).getRemoteDevice(address)
        if (!bond || device.bondState == BluetoothDevice.BOND_BONDED) return
        if (device.bondState != BluetoothDevice.BOND_BONDING) check(device.createBond()) { "系统拒绝配对" }
        withTimeout(30_000) {
            do { delay(250) } while (device.bondState == BluetoothDevice.BOND_BONDING)
            check(device.bondState == BluetoothDevice.BOND_BONDED) { "系统配对未完成" }
        }
    }
    override suspend fun connect(address: String, card: DeviceCard): LinkedDevice {
        permission(false)
        val device = requireNotNull(adapter?.takeIf { it.isEnabled }).getRemoteDevice(address)
        return connectors[card.id]?.invoke(device) ?: AndroidDeviceLink.connect(context, device, card)
    }
}

/** 品牌注册只放装配层；每台设备独立文件插件实例，避免序号/文件列表相互覆盖。 */
internal fun createDevices(app: ResoundApp, cards: DeviceCards): ManagedDevices {
    DeviceOptionsUi.register("d3200") { device -> D3200OptionsEntry(device.address) }
    val syncs = mutableMapOf<String, DeviceSync>()
    val files = mutableMapOf<String, D3200Files>()
    val ingest = deviceImport(app, app.database, AppGraph.importer as AndroidAudioImporter)
    return ManagedDevices(cards, AndroidPairedDeviceStore(app.getSharedPreferences("devices", Context.MODE_PRIVATE)),
        AndroidDeviceRadio(app, mapOf("d3200" to { device -> D3200Connection.connect(app, device, app.d3200) }), app.d3200::observe), app.scope,
        source = { row, card, link, ready, battery ->
            if (link is D3200Connection) D3200AudioSource(link, row.name, ready) else {
            val protocol = if (card.filePlugin == "sonicloud") SonicloudLive() else object : LiveProtocol {}
            CardAudioSource(ManagedDevices.deviceKey(row.address), row.name, card, link, protocol,
                ready = ready, battery = battery, report = { stats ->
                    // 地址仅做不可逆短标识，不记录声音、设备名字或完整地址。
                    val label = MessageDigest.getInstance("SHA-256").digest(row.address.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
                    UpdateDiagnostics.record(app,
                        "device_link 设备 $label 收包=${stats.received} 丢包=${stats.lost} 校验失败=${stats.crcFailures} 解码失败=${stats.decodeFailures}")
                })
            }
        }, onLinked = { row, card, link, start, end, offset, progress ->
            if (link is D3200Connection && link.recording != 0) {
                app.d3200.report(row.address) { it.copy(sync = "正在录音；按设备键停止并保存后补传") }
            } else if (link is D3200Connection && !app.d3200.autoSync && !app.d3200.requested(row.address)) {
                app.d3200.report(row.address) { it.copy(sync = "自动补传已关闭；可在设备选项中立即检查") }
            } else {
            val plugin = files.getOrPut(row.address) { D3200Files() }
            val sync = syncs.getOrPut(row.address) { DeviceSync(row.address, File(app.filesDir, "device-sync/downloads"),
                app.database, listOf(SonicloudFiles(), plugin), ingest = { source, file, slice, key ->
                    if (card.filePlugin == "d3200") {
                        val ogg = File(app.cacheDir, "d3200-import-$key.ogg")
                        try {
                            require(source.length() > 0 && source.length() % 160 == 0L)
                            D3200Ogg.write(source, ogg, D3200HistoryCore.Range(0, source.length() / 160))
                            ingest(ogg, file.copy(format = FileFormat.Ogg, bytes = ogg.length()), slice, key)
                            if (slice.from == 0L && slice.to == file.durationMillis)
                                app.database.folderImports().remember(ImportFingerprint(audioFingerprint(source), System.currentTimeMillis()))
                        } finally { ogg.delete() }
                    } else ingest(source, file, slice, key)
                }, newestFirst = card.filePlugin == "d3200") }
            coroutineScope {
                val observer = launch { sync.progress.collect(progress) }
                try {
                    if (link is D3200Connection) {
                        val request = app.d3200.request(row.address)
                        app.d3200.report(row.address) { it.copy(sync = "正在检查已保存的离线录音") }
                        app.d3200.journal(row.address).seed(plugin.list(link))
                        sync.importAll(card, link, 0L)
                        check(app.database.deviceSync().rows(row.address).none { !it.imported }) { "补传或导入尚未完成，已保留进度" }
                        app.d3200.acknowledge(row.address, request)
                        app.d3200.report(row.address) { it.copy(sync = "离线录音已检查并确认导入；设备原文件保留") }
                    } else sync.onLinked(card, link, start, end, offset)
                }
                finally { observer.cancelAndJoin(); progress(null) }
            }
            }
        })
}
