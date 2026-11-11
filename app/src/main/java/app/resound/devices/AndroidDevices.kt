package app.resound.devices

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
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
import app.resound.devices.driver.SettingValue
import app.resound.update.UpdateDiagnostics
import app.resound.devices.android.*
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
internal class AndroidDeviceBond(private val context: Context) {
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private fun permission(scan: Boolean) {
        val permissions = if (Build.VERSION.SDK_INT >= 31) listOfNotNull(Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN.takeIf { scan }) else if (scan) listOf(Manifest.permission.ACCESS_FINE_LOCATION) else emptyList()
        if (permissions.any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED })
            throw SecurityException("缺少蓝牙权限")
    }
    suspend fun pair(address: String, bond: Boolean) {
        permission(false)
        val device = requireNotNull(adapter?.takeIf { it.isEnabled }).getRemoteDevice(address)
        if (!bond || device.bondState == BluetoothDevice.BOND_BONDED) return
        if (device.bondState != BluetoothDevice.BOND_BONDING) check(device.createBond()) { "系统拒绝配对" }
        withTimeout(30_000) {
            do { delay(250) } while (device.bondState == BluetoothDevice.BOND_BONDING)
            check(device.bondState == BluetoothDevice.BOND_BONDED) { "系统配对未完成" }
        }
    }
}

/** 唯一扫描入口和连接工厂均来自平台容器，实时录音改由 DeviceHub 持有连接。 */
internal fun createDevices(app: ResoundApp, cards: DeviceCards): DeviceInputs {
    val platform = AndroidDevicePlatform(app, app.scope)
    AppGraph.devicePlatform = platform
    val syncs = mutableMapOf<String, DeviceSync>()
    val plugins = emptyList<FileSourcePlugin>()
    val ingest = deviceImport(app, app.database, AppGraph.importer as AndroidAudioImporter)
    val registered = DriverCatalog.registered()
    return DeviceInputs(cards, AndroidPairedDeviceStore(app.getSharedPreferences("devices", Context.MODE_PRIVATE)),
        platform.repository, { address, card -> platform.connector { card.link.mtu }.connect(address) },
        platform.toolbox, platform.clock, platform.extras, { platform.bluetooth.scan() },
        AndroidDeviceBond(app)::pair, AndroidDevicePlatform::legacyIdentity, app.scope, plugins,
        onLinked = { row, card, link, start, end, offset, progress ->
            val sync = syncs.getOrPut(row.address) { DeviceSync(row.address, File(app.filesDir, "device-sync/downloads"),
                app.database, plugins, ingest) }
            coroutineScope {
                val observer = launch { sync.progress.collect(progress) }
                try { sync.onLinked(card, link, start, end, offset) }
                finally { observer.cancelAndJoin(); progress(null) }
            }
        }, report = { address, stats ->
            val label = MessageDigest.getInstance("SHA-256").digest(address.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
            UpdateDiagnostics.record(app,
                "device_link 设备 $label 收包=${stats.received} 丢包=${stats.lost} 校验失败=${stats.crcFailures} 解码失败=${stats.decodeFailures}")
        }, registered = registered, extrasFor = platform::extrasFor,
        onDriverLinked = { row, connection, link, manual, progress ->
            val auto = connection.settings?.schema()?.firstOrNull { it.key == "autoSync" }?.value as? SettingValue.Bool
            if (manual || auto?.value != false) {
                val files = connection.files
                if (files != null) {
                    val adapter = DriverFileAdapter(row.cardId, files)
                    val listed = adapter.list(link)
                    val selection = platform.repository.selections().first { it.platformHandle == row.address }
                    val storeKey = selection.deviceId?.let { "id:${it.value}" } ?: "handle:${row.address}"
                    val driverStore = platform.repository.store(selection.driverId,storeKey)
                    app.segmentFiles.awaitReady()
                    for (file in listed) {
                        val covered = adapter.original(file).liveCovered + recoverDriverCoverage(app.database,File(app.filesDir,"segment-spool"),file,driverStore)
                        seedLiveCoverage(app.database,row.address,file,covered)
                    }
                    val card = registered.first { it.driver.meta.id == row.cardId }.transport.copy(filePlugin = adapter.id)
                    val sync = DeviceSync(row.address,File(app.filesDir,"device-sync/downloads"),app.database,listOf(adapter),
                        ingest = { source,file,slice,key ->
                            val converted = File(app.cacheDir,"device-import-$key.ogg")
                            try {
                                if (DriverCatalog.encode(row.cardId,source,converted)) {
                                    ingest(converted,file.copy(format=FileFormat.Ogg,bytes=converted.length()),slice,key)
                                    if (slice.from == 0L && slice.to == file.durationMillis)
                                        app.database.folderImports().remember(ImportFingerprint(audioFingerprint(source),System.currentTimeMillis()))
                                } else ingest(source,file,slice,key)
                            } finally { converted.delete() }
                        },newestFirst=connection.policy.newestFirst)
                    coroutineScope {
                        val observer = launch { sync.progress.collect(progress) }
                        try {
                            sync.importAll(card,link,row.clockOffset)
                            check(app.database.deviceSync().rows(row.address).none { !it.imported }) { "设备文件尚未完成导入" }
                        }
                        finally { observer.cancelAndJoin(); progress(null) }
                    }
                }
            }
        })
}
