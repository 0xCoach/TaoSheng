package app.resound.importer

import app.resound.audio.SegmentEncoder
import app.resound.capture.CaptureTuning
import app.resound.capture.SpeechSegmenter
import app.resound.data.CaptureDatabase
import app.resound.data.SegmentEntity
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun importStartAt(epoch: Long, sample: Long) = epoch + sample * 1000 / CaptureTuning.SAMPLE_RATE

/** VAD/切段只持有前滚；完整片段写 PCM 暂存，再复用录音编码器。 */
internal class ImportSegments(private val folder: File, private val id: String, private val epoch: Long) : SpeechSegmenter.Sink, AutoCloseable {
    internal data class Piece(val pcm: File, val start: Long, val end: Long, val index: Int)
    val ready = ArrayDeque<Piece>()
    private var output: OutputStream? = null
    private var pcm: File? = null
    private var start = 0L
    private var index = 0
    init { folder.mkdirs() }
    override fun begin(startSample: Long) {
        start = importStartAt(epoch, startSample)
        pcm = File(folder, "$id-${index++}.pcm")
        output = pcm!!.outputStream().buffered()
    }
    override fun write(samples: ShortArray) {
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asShortBuffer().put(samples); output!!.write(bytes.array())
    }
    override fun end(endSample: Long, keep: Boolean) {
        output?.close(); output = null
        val file = pcm ?: return
        if (keep) ready.addLast(Piece(file, start, importStartAt(epoch, endSample), index - 1)) else file.delete()
        pcm = null
    }
    suspend fun save(db: CaptureDatabase, destination: File, asr: app.resound.transcribe.TranscribeSource? = null): Int {
        var saved = 0
        destination.mkdirs()
        while (ready.isNotEmpty()) {
            val piece = ready.removeFirst()
            val base = File(destination, "$id-${piece.index}")
            try {
                // 重启重放时按导入 ID + 段号幂等，已转写且删除的音频也不会重新生成。
                if (!db.transcribe().hasImportPath(base.path + ".ogg", base.path + ".m4a")) {
                    val audio = SegmentEncoder.encode(piece.pcm, base)
                    db.capture().segment(SegmentEntity(startAt = piece.start, endAt = piece.end, sourceId = "import",
                        path = audio.path, sizeBytes = audio.length(), playbackMillis = 0, asrSource = asr?.name, separateSpeakers = if (app.resound.AppGraph.isAsrSpeedReady) app.resound.AppGraph.asrSpeed.effectiveSeparateSpeakers(piece.start) else null))
                }
                saved++
            } catch (e: Exception) {
                // 编码完成但入库失败不能留下没有清理策略的孤立录音。
                if (!db.transcribe().hasImportPath(base.path + ".ogg", base.path + ".m4a")) {
                    File(base.path + ".ogg").delete(); File(base.path + ".m4a").delete()
                }
                throw e
            } finally { piece.pcm.delete() }
        }
        return saved
    }
    override fun close() { output?.close(); pcm?.delete(); ready.forEach { it.pcm.delete() }; ready.clear() }
}
