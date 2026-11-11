package app.resound.devices.plugins.d3200

import android.util.AtomicFile
import app.resound.ResoundApp
import app.resound.devices.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Durable live coverage only; downloads and ingestion progress belong to official device_transfers. */
internal class D3200Journal(private val app: ResoundApp, private val address: String) {
    private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private val folder = File(app.filesDir, "d3200-coverage/${hash(address)}").apply { mkdirs() }
    private fun ranges(a: JSONArray?): List<D3200HistoryCore.Range> = (0 until (a?.length() ?: 0)).map {
        a!!.getJSONArray(it).let { r -> D3200HistoryCore.Range(r.getLong(0), r.getLong(1)) }
    }
    private fun read(file: File): JSONObject = if (!file.exists() && !File(file.path + ".bak").exists()) JSONObject()
        else JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
    fun cover(file: Long, range: D3200HistoryCore.Range) = synchronized(lock) {
        val target = File(folder, "$file.json")
        val json = read(target)
        val merged = D3200HistoryCore.merge(ranges(json.optJSONArray("live")) + range)
        json.put("live", JSONArray(merged.map { JSONArray(listOf(it.from, it.until)) }))
        val atomic = AtomicFile(target); val output = atomic.startWrite()
        try { output.write(json.toString().toByteArray()); atomic.finishWrite(output) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
    }
    suspend fun seed(files: List<DeviceFile>) {
        app.segmentFiles.awaitReady()
        for (file in files) {
            val epoch = requireNotNull(file.startedAt); val duration = requireNotNull(file.durationMillis)
            val covered = mutableListOf<D3200HistoryCore.Range>()
            val local = synchronized(lock) { read(File(folder, "${file.id}.json")) }
            covered += ranges(local.optJSONArray("live"))
            // Reconstruct the last window when a process died before its coverage journal commit.
            val sources = setOf("${ManagedDevices.deviceKey(address)}:${file.id}")
            app.database.capture().segments(epoch, epoch + duration).first().filter { it.sourceId in sources }.forEach {
                add(covered, epoch, it.startAt, it.endAt)
            }
            File(app.filesDir, "segment-spool").listFiles().orEmpty().filter { it.extension == "source" }.forEach { meta ->
                val source = runCatching { meta.readText() }.getOrNull()
                if (source in sources) {
                    val start = meta.name.substringBefore('_').toLongOrNull() ?: return@forEach
                    val pcm = File(meta.parentFile, meta.nameWithoutExtension + ".pcm")
                    val active = File(meta.parentFile, meta.nameWithoutExtension + ".recording")
                    val audio = if (pcm.exists()) pcm else active
                    if (audio.exists()) add(covered, epoch, start, start + audio.length() / 32)
                }
            }
            for (r in D3200HistoryCore.merge(covered)) {
                val from = (r.from * 20).coerceAtMost(duration)
                val to = (r.until * 20).coerceAtMost(duration)
                if (to <= from) continue
                val row = DeviceTransfer(deviceTransferKey(address, file, from, to), address, file.id, file.name, file.bytes, file.startedAt,
                    file.durationMillis, file.format.name, from, to, epoch + from, imported = true)
                app.database.deviceSync().save(row)
            }
        }
    }
    private fun add(out: MutableList<D3200HistoryCore.Range>, epoch: Long, start: Long, end: Long) {
        val from = ((start - epoch).coerceAtLeast(0) + 19) / 20
        val until = (end - epoch).coerceAtLeast(0) / 20
        if (until > from) out += D3200HistoryCore.Range(from, until)
    }
    companion object { private val lock = Any() }
}
