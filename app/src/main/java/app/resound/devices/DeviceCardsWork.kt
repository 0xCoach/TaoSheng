package app.resound.devices

import android.content.Context
import android.util.AtomicFile
import androidx.work.*
import app.resound.AppGraph
import app.resound.update.UpdateDiagnostics
import app.resound.update.UpdateRemote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

internal class AndroidCardsStore(context: Context) : CardsStore {
    private val prefs = context.getSharedPreferences("device-cards", Context.MODE_PRIVATE)
    // 不进入备份；恢复旧备份不应覆盖本机已验证表。
    private val file = AtomicFile(File(context.noBackupFilesDir, "device-cards-cache.json"))
    override fun cached(): List<SignedCards> {
        if (!file.baseFile.exists()) return emptyList()
        val bytes = file.openRead().use { input ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (out.size() <= RemoteDeviceCards.MAX_CACHE * 2) {
                val n = input.read(buffer); if (n < 0) break; out.write(buffer, 0, n)
            }
            require(out.size() <= RemoteDeviceCards.MAX_CACHE * 2); out.toByteArray()
        }
        val a = JSONArray(bytes.toString(Charsets.UTF_8))
        return (0 until a.length()).map { val j = a.getJSONObject(it); SignedCards(Base64.getDecoder().decode(j.getString("json")), Base64.getDecoder().decode(j.getString("sig"))) }
    }
    override fun save(tables: List<SignedCards>) {
        val array = JSONArray().apply { tables.forEach { put(JSONObject().put("json", Base64.getEncoder().encodeToString(it.json)).put("sig", Base64.getEncoder().encodeToString(it.signature))) } }
        val output = file.startWrite()
        try { output.write(array.toString().toByteArray()); file.finishWrite(output) } catch(e: Exception) { file.failWrite(output); throw e }
    }
    override fun enabled(id: String): Boolean? = if (prefs.contains("enabled:$id")) prefs.getBoolean("enabled:$id", false) else null
    override fun enable(id: String, enabled: Boolean) { check(prefs.edit().putBoolean("enabled:$id", enabled).commit()) }
    override var lastAttempt: Long
        get() = prefs.getLong("last-attempt", 0)
        set(value) { check(prefs.edit().putLong("last-attempt", value).commit()) }
}

internal fun createDeviceCards(context: Context): RemoteDeviceCards {
    val report: (String) -> Unit = { UpdateDiagnostics.record(context, "device_cards $it") }
    val bundled = context.assets.list("devices").orEmpty().filter { it.endsWith(".json") }.mapNotNull { name ->
        runCatching { context.assets.open("devices/$name").bufferedReader().use { it.readText() } }.getOrElse { report("asset_failed"); null }
    }
    val key = runCatching { context.assets.open("device-cards-public-key.hex").bufferedReader().use { it.readText().trim() }
        .also { require(it.matches(Regex("(?:[0-9a-f]{2}){1,256}"))) }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }.getOrElse { report("official_key_missing"); byteArrayOf() }
    val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
    return RemoteDeviceCards(bundled, AndroidCardsStore(context), key, version, report, app.resound.devices.core.CardParser(version)::parse)
}

class DeviceCardsWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val cards = AppGraph.deviceCards as RemoteDeviceCards
        cards.refresh(System.currentTimeMillis()) { accept -> UpdateRemote(log = { UpdateDiagnostics.record(applicationContext, it) }).deviceCards(accept) }
        Result.success()
    }
    companion object {
        fun schedule(context: Context) {
            val manager = WorkManager.getInstance(context)
            val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            manager.enqueueUniqueWork("device-cards-start", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DeviceCardsWork>().setConstraints(network).build())
            manager.enqueueUniquePeriodicWork("device-cards-daily", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DeviceCardsWork>(24, TimeUnit.HOURS).setInitialDelay(24, TimeUnit.HOURS).setConstraints(network).build())
        }
    }
}
