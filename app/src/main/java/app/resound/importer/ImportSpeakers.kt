package app.resound.importer

import androidx.room.withTransaction
import app.resound.data.*
import app.resound.voiceprint.*
import kotlinx.coroutines.CancellationException
import java.io.File

internal object ImportSpeakerGroups {
    /** 先以最不相似的两组为中心，再按声纹相似度归并；不按编号取模或猜身份。 */
    fun two(vectors: List<FloatArray>): List<Int> {
        if (vectors.size <= 2) return vectors.indices.toList()
        var first = 0; var second = 1; var similarity = Float.MAX_VALUE
        for (i in vectors.indices) for (j in 0 until i) {
            val score = VoiceprintMath.cosine(vectors[i], vectors[j])
            if (score < similarity) { similarity = score; first = j; second = i }
        }
        var centers = listOf(vectors[first], vectors[second])
        var assignment = emptyList<Int>()
        repeat(8) {
            val next = vectors.map { if (VoiceprintMath.cosine(it, centers[0]) >= VoiceprintMath.cosine(it, centers[1])) 0 else 1 }
            if (next == assignment) return next
            assignment = next
            centers = (0..1).map { group -> vectors.filterIndexed { i, _ -> next[i] == group }
                .takeIf { it.isNotEmpty() }?.let(VoiceprintMath::representative) ?: centers[group] }
        }
        return assignment
    }
    fun channelNames(lines: List<TranscriptLineEntity>): List<TranscriptLineEntity> {
        val mine = lines.filter { it.speakerName == "我" }.mapNotNull { it.speakerTag }.distinct().singleOrNull()
        return lines.sortedWith(compareBy({ it.startAt }, { it.speakerTag }, { it.id })).map { line ->
            val name = if (mine == null) if (line.speakerTag == "channel:0") "左声道" else "右声道"
                else if (line.speakerTag == mine) "我" else "对方"
            line.copy(speakerName = name, speakerPersonId = if (name == "我") 0L else null,
                speakerScore = if (name == "我") line.speakerScore else null)
        }
    }
}

internal class ImportSpeakers(
    private val db: CaptureDatabase,
    private val readPcm: suspend (File) -> ShortArray,
    private val createEngine: () -> SpeakerEmbedding,
) {
    suspend fun prepare(record: ImportRecordRow): String? {
        val dao = db.imports()
        val lines = dao.lines(record.id)
        if (record.byChannel) {
            db.withTransaction { if (dao.get(record.id)?.revision == record.revision)
                ImportSpeakerGroups.channelNames(lines).forEach { dao.line(it) } }
            return null
        }
        if (record.kind != ImportKind.Call.name || lines.isEmpty()) return null
        // 每段编号均从零重新开始，使用声纹对齐整份文件里的身份。
        val groups = lines.groupBy { it.segmentId to VoiceprintAudio.groupKey(it) }.values.toList()
        var model: SpeakerEmbedding? = null
        return try {
            val vectors = mutableMapOf<Pair<Long, String>, FloatArray>()
            for (segment in dao.segments(record.id)) {
                val selected = groups.filter { it.first().segmentId == segment.id }
                if (selected.isEmpty()) continue
                val pcm = readPcm(File(segment.path))
                for (group in selected) {
                    val windows = WindowedVoiceprint.windows(segment.startAt, segment.endAt, group).take(3)
                    val features = windows.mapNotNull { w ->
                        val from = ((w.start - segment.startAt) * 16).coerceIn(0, pcm.size.toLong()).toInt()
                        val to = ((w.end - segment.startAt) * 16).coerceIn(from.toLong(), pcm.size.toLong()).toInt()
                        val speech = VoiceprintAudio.trimSilence(pcm.copyOfRange(from, to))
                        if (speech.size < VoiceprintAudio.MIN_SAMPLES) null else {
                            val engine = model ?: createEngine().also { model = it }
                            engine.compute(speech)
                        }
                    }
                    if (features.isEmpty()) return "部分说话人有效人声不足，已保留原分人结果，未强行合并为两人"
                    vectors[segment.id to VoiceprintAudio.groupKey(group.first())] = VoiceprintMath.representative(features)
                }
            }
            val assignment = ImportSpeakerGroups.two(groups.map { vectors.getValue(it.first().segmentId to VoiceprintAudio.groupKey(it.first())) })
            db.withTransaction {
                if (dao.get(record.id)?.revision == record.revision) groups.forEachIndexed { index, group ->
                    group.forEach { dao.line(it.copy(speakerTag = "call:${assignment[index]}")) }
                }
            }
            null
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { "声纹合并暂不可用，已保留原分人结果，未强行合并为两人" }
        finally { (model as? AutoCloseable)?.close() }
    }
}
