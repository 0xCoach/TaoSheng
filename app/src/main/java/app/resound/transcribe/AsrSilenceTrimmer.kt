package app.resound.transcribe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** 仅转写输入用临时 PCM；原录音、共享 PCM、环境音及认人输入保持原时间。 */
internal class AsrSilenceTrimmer(
    private val cacheDir: File,
    private val decode: suspend (File, File) -> Unit,
) {
    private class Prepared(val audio: AsrAudio, val plan: AsrSilencePlan) : AutoCloseable {
        override fun close() { audio.file.delete() }
    }

    suspend fun recognize(audio: AsrAudio, block: suspend (AsrAudio) -> AsrResult): AsrResult {
        val prepared = measureAsr(AsrPart.Prepare) { prepare(audio) }
        if (prepared == null) return block(audio)
        return prepared.use { it.plan.restore(block(it.audio), audio.durationMillis) }
    }

    private suspend fun prepare(audio: AsrAudio): Prepared? = withContext(Dispatchers.IO) {
        var temporary: File? = null
        var decoded: File? = null
        try {
            val pcm = audio.pcmFile?.takeIf { it.isFile }
                ?: if (audio.format == "pcm") audio.file else {
                    File.createTempFile("asr-shared-trim-source-", ".pcm", cacheDir).also {
                        decoded = it; decode(audio.file, it)
                    }
                }
            val plan = AsrSilencePlan.analyze(pcm)
            if (!plan.changed) return@withContext null
            val file = File.createTempFile("asr-shared-trim-", ".pcm", cacheDir)
            temporary = file
            file.outputStream().buffered().use { out -> RandomAccessFile(pcm, "r").use { input ->
                val buffer = ByteArray(8192)
                for (span in plan.spans) {
                    input.seek(span.originalStart * 2L)
                    var remaining = (span.originalEnd - span.originalStart) * 2L
                    while (remaining > 0) {
                        val count = minOf(buffer.size.toLong(), remaining).toInt()
                        input.readFully(buffer, 0, count)
                        out.write(buffer, 0, count); remaining -= count
                    }
                }
            } }
            saveMap(audio, plan)
            Prepared(audio.copy(file = file, format = "pcm", pcmFile = file,
                durationMillis = plan.trimmedSamples / 16L), plan)
        } catch (e: CancellationException) {
            temporary?.delete(); throw e
        } catch (_: Exception) {
            // 优化失败时仍用完整原录音，不因映射或缓存失败丢字。
            temporary?.delete(); null
        } finally { decoded?.delete() }
    }

    /** 有界缓存留存映射以便排查；最终数据库时间已还原，不依赖缓存维持试听正确性。 */
    private fun saveMap(audio: AsrAudio, plan: AsrSilencePlan) {
        val key = "${audio.file.absolutePath}:${audio.file.length()}:${audio.file.lastModified()}"
        val name = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        val directory = File(cacheDir, "asr-time-maps").apply { mkdirs() }
        val target = File(directory, "$name.json")
        val temp = File.createTempFile("map-", ".tmp", directory)
        try {
            temp.writeText(JSONObject().put("version", 1).put("sampleRate", 16000)
                .put("originalSamples", plan.originalSamples).put("trimmedSamples", plan.trimmedSamples)
                .put("spans", JSONArray(plan.spans.map { span -> JSONObject()
                    .put("originalStart", span.originalStart).put("originalEnd", span.originalEnd)
                    .put("trimmedStart", span.trimmedStart) })).toString())
            check(temp.renameTo(target))
        } finally { temp.delete() }
        directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }
            ?.drop(64)?.forEach { it.delete() }
    }
}
