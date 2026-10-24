package app.resound.capture

import android.content.Context
import app.resound.audio.SegmentEncoder
import app.resound.data.CaptureDao
import app.resound.data.SegmentEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 先持久化完整 PCM 到队列，再串行编码；进程死亡后重试已经完成的片段。 */
class SegmentFiles(private val context: Context, private val dao: CaptureDao, scope: CoroutineScope) {
    private val spool = File(context.filesDir, "segment-spool").apply { mkdirs() }
    private val encoding = Mutex()
    private val discardedPrefs = context.getSharedPreferences("privacy-spool", Context.MODE_PRIVATE)
    private val discardLock = Any()
    private fun isDiscarded(base: String) = discardedPrefs.getStringSet("discarded", emptySet())!!.contains(base)
    private fun markDiscarded(bases: Set<String>) = synchronized(discardLock) {
        if (bases.isNotEmpty()) {
            val all = discardedPrefs.getStringSet("discarded", emptySet())!!.toSet() + bases
            check(discardedPrefs.edit().putStringSet("discarded", all).commit()) { "无法保存隐私暂存删除标记" }
        }
    }
    private fun deleteFile(file: File) {
        try { check(file.delete() || !file.exists()) }
        catch (_: Exception) { android.util.Log.w("Privacy", "录音暂存文件删除失败") }
    }
    private fun removeDiscarded(base: String) {
        spool.listFiles().orEmpty().filter { it.name.substringBefore('.') == base }.forEach(::deleteFile)
        // 编码成功但数据库写入失败时，可能同时留下压缩录音与 PCM。
        val start = base.substringBefore('_').toLongOrNull() ?: return
        val local = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault())
        val path = File(context.filesDir, "segments/${local.toLocalDate()}/${local.format(DateTimeFormatter.ofPattern("HH-mm-ss-SSS"))}_$base")
        val encoded = listOf(".ogg", ".m4a").map { File(path.path + it) }
        encoded.forEach(::deleteFile)
        // 已清干净时不保留标记，避免全天录音的短片段让偏好文件无限增长。
        if (spool.listFiles().orEmpty().none { it.name.substringBefore('.') == base } && encoded.none { it.exists() }) {
            synchronized(discardLock) {
                val remaining = discardedPrefs.getStringSet("discarded", emptySet())!!.toSet() - base
                discardedPrefs.edit().putStringSet("discarded", remaining).commit()
            }
        }
    }
    private val ready = CompletableDeferred<Unit>()
    suspend fun awaitReady() = ready.await()
    private val queue = Channel<File>(Channel.UNLIMITED)
    init {
        scope.launch(Dispatchers.IO) {
            try {
                // confirmed 表示已经累积至少一秒人声；进程死亡后可回收其已写入部分。
                spool.listFiles()?.filter { it.extension == "recording" }?.forEach { partial ->
                    if (isDiscarded(partial.nameWithoutExtension)) {
                        removeDiscarded(partial.nameWithoutExtension)
                        return@forEach
                    }
                    val confirmed = File(partial.path + ".confirmed")
                    if (confirmed.exists() && partial.length() >= CaptureTuning.MIN_SPEECH_SAMPLES * 2) {
                        if (partial.renameTo(File(spool, partial.nameWithoutExtension + ".pcm"))) confirmed.delete()
                    } else { partial.delete(); confirmed.delete() }
                }
                ready.complete(Unit)
            } catch (e: Exception) {
                ready.completeExceptionally(e)
                return@launch
            }
            spool.listFiles()?.filter { it.extension == "pcm" }?.sortedBy { it.name }?.forEach { saveSafely(it) }
            for (file in queue) saveSafely(file)
        }
    }
    private suspend fun saveSafely(pcm: File) = encoding.withLock {
        try { save(pcm) } catch (e: Exception) {
            if (e is CancellationException) throw e
            // 保留 PCM，下次启动重试；诊断可看到失败原因。
            val detail = "${System.currentTimeMillis()} 片段保存失败，${pcm.name}: ${e.javaClass.simpleName}: ${e.message}"
            context.getSharedPreferences("capture", Context.MODE_PRIVATE).edit().putString("lastSegmentError", detail).commit()
            android.util.Log.e("SegmentFiles", detail)
        }
    }
    private suspend fun save(pcm: File) {
        if (isDiscarded(pcm.nameWithoutExtension)) { removeDiscarded(pcm.nameWithoutExtension); return }
        if (!pcm.exists()) return
        val start = pcm.nameWithoutExtension.substringBefore('_').toLong()
        val end = start + pcm.length() / 2 * 1000 / CaptureTuning.SAMPLE_RATE
        val local = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault())
        val folder = File(context.filesDir, "segments/${local.toLocalDate()}").apply { check(mkdirs() || isDirectory) }
        val base = File(folder, "${local.format(DateTimeFormatter.ofPattern("HH-mm-ss-SSS"))}_${pcm.nameWithoutExtension}")
        val file = listOf(File(base.path + ".ogg"), File(base.path + ".m4a")).firstOrNull { it.exists() && it.length() > 0 }
            ?: SegmentEncoder.encode(pcm, base)
        val metadata = File(spool, pcm.nameWithoutExtension + ".playback")
        val values = metadata.takeIf { it.exists() }?.readText()?.split(":")
        val playbackMillis = (values?.firstOrNull()?.toLongOrNull() ?: 0).coerceIn(0, end - start)
        val skip = PlaybackPolicy.skip(end - start, playbackMillis, values?.getOrNull(1) != "false")
        dao.segment(SegmentEntity(startAt = start, endAt = end, sourceId = "mic", path = file.absolutePath, sizeBytes = file.length(),
            playbackMillis = playbackMillis, status = if (skip) "SkippedPlayback" else "Pending",
            completedAt = if (skip) end else null))
        metadata.delete()
        check(pcm.delete())
    }
    /** 先等当前编码提交，再持锁删除数据库和暂存，队列旧引用之后只会跳过。 */
    suspend fun <T> withRecentDeletion(from: Long, until: Long, block: suspend () -> T): T {
        awaitReady()
        return encoding.withLock {
            val result = block()
            val pending = spool.listFiles().orEmpty().filter {
                it.name.substringBefore('_').toLongOrNull()?.let { start -> start in from..until } == true &&
                    it.extension != "recording" && !it.name.endsWith(".recording.confirmed")
            }
            val bases = pending.map { it.name.substringBefore('.') }.toSet()
            // 文件删不掉也不能在队列/下次启动时重新入库。
            markDiscarded(bases)
            bases.forEach(::removeDiscarded)
            result
        }
    }

    fun sink(epoch: Long): SpeechSegmenter.Sink = object : SpeechSegmenter.Sink {
        private var file: File? = null
        private var output: OutputStream? = null
        private var playbackSamples = 0L
        private var savedMillis = -1000L
        private fun savePlayback() {
            val current = file ?: return
            val ms = playbackSamples * 1000 / CaptureTuning.SAMPLE_RATE
            val enabled = context.getSharedPreferences("transcribe", Context.MODE_PRIVATE).getBoolean("skipPlayback", true)
            val target = android.util.AtomicFile(File(spool, current.nameWithoutExtension + ".playback"))
            val stream = target.startWrite()
            try { stream.write("$ms:$enabled".toByteArray()); target.finishWrite(stream) }
            catch (e: Exception) { target.failWrite(stream); throw e }
            savedMillis = ms
        }
        override fun playback(samples: Long) {
            playbackSamples = samples
            if (samples * 1000 / CaptureTuning.SAMPLE_RATE - savedMillis >= 1000) savePlayback()
        }
        override fun begin(startSample: Long) {
            val start = epoch + startSample * 1000 / CaptureTuning.SAMPLE_RATE
            file = File(spool, "${start}_${java.util.UUID.randomUUID()}.recording")
            output = file!!.outputStream().buffered()
            playbackSamples = 0; savedMillis = -1000
        }
        override fun write(samples: ShortArray) {
            val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asShortBuffer().put(samples)
            output!!.write(bytes.array())
        }
        override fun confirmed() {
            output!!.flush()
            File(file!!.path + ".confirmed").writeText("speech>=1s")
        }
        override fun end(endSample: Long, keep: Boolean) {
            output?.close(); output = null
            val current = file ?: return
            savePlayback()
            file = null
            if (!keep) {
                markDiscarded(setOf(current.nameWithoutExtension))
                removeDiscarded(current.nameWithoutExtension)
                return
            }
            val complete = File(spool, current.nameWithoutExtension + ".pcm")
            check(current.renameTo(complete))
            File(current.path + ".confirmed").delete()
            check(queue.trySend(complete).isSuccess)
        }
    }
}
