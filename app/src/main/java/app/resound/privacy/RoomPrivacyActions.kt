package app.resound.privacy

import androidx.room.withTransaction
import app.resound.data.CaptureDatabase
import app.resound.data.SegmentEntity
import app.resound.data.TranscriptLineEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** 数据库先原子删除，提交成功后才清文件；回顾和问问历史保持原样。 */
class RoomPrivacyActions(
    private val db: CaptureDatabase,
    private val discardRecording: suspend () -> Unit = {},
    private val recentFiles: suspend (Long, Long, suspend () -> ForgetResult) -> ForgetResult = { _, _, block -> block() },
    private val voiceprintGuard: suspend (suspend () -> ForgetResult) -> ForgetResult = { block -> block() },
    private val removeFeatures: (SegmentEntity, List<TranscriptLineEntity>) -> Unit = { _, _ -> },
    private val diagnostic: (String) -> Unit = { android.util.Log.w("Privacy", it); Unit },
    private val now: () -> Long = System::currentTimeMillis,
) : PrivacyActions, app.resound.transcribe.SegmentDeleter {
    private val gate = Mutex()
    private val dao = db.privacy()

    override suspend fun deleteRecent(minutes: Int): ForgetResult {
        require(minutes > 0)
        return withContext(Dispatchers.IO + NonCancellable) { gate.withLock {
            val until = now()
            val from = until - minutes.toLong() * 60_000
            discardRecording()
            recentFiles(from, until) { erase { dao.recent(from, until) } }
        } }
    }

    override suspend fun deleteRange(fromMillis: Long, untilMillis: Long): ForgetResult {
        require(fromMillis <= untilMillis)
        return withContext(Dispatchers.IO + NonCancellable) { gate.withLock {
            if (untilMillis >= now()) discardRecording()
            recentFiles(fromMillis, untilMillis) { erase { dao.recent(fromMillis, untilMillis) } }
        } }
    }

    override suspend fun previewRange(fromMillis: Long, untilMillis: Long): ForgetPreview = withContext(Dispatchers.IO) {
        dao.recent(fromMillis, untilMillis).let { rows -> ForgetPreview(rows.size, rows.sumOf { (it.endAt - it.startAt).coerceAtLeast(0) }) }
    }

    override suspend fun forgetEvent(eventId: Long): ForgetResult = withContext(Dispatchers.IO + NonCancellable) {
        gate.withLock { erase(eventId) { dao.eventSegments(eventId) } }
    }

    override suspend fun forgetSegment(segmentId: Long): ForgetResult = withContext(Dispatchers.IO + NonCancellable) {
        gate.withLock { erase { listOfNotNull(dao.segment(segmentId)) } }
    }

    override suspend fun deleteSegments(segmentIds: List<Long>): Int = withContext(Dispatchers.IO + NonCancellable) {
        gate.withLock {
            erase(keepEvents = true) { segmentIds.distinct().mapNotNull { dao.segment(it) } }.segments
        }
    }

    private suspend fun erase(explicitEvent: Long? = null, keepEvents: Boolean = false, select: suspend () -> List<SegmentEntity>): ForgetResult = voiceprintGuard {
        val files = mutableListOf<Pair<SegmentEntity, List<TranscriptLineEntity>>>()
        val result = db.withTransaction {
            val segments = select()
            val events = mutableSetOf<Long>()
            explicitEvent?.let(events::add)
            var lines = 0
            var items = 0
            var deletedEvents = 0
            var deletedSegments = 0
            for (segment in segments) {
                val source = db.voiceprint().lines(segment.id)
                files += segment to source
                val target = if (explicitEvent == null) source else source.filter { it.eventId == explicitEvent }
                events += target.mapNotNull { it.eventId }
                dao.deleteSamples(segment.id)
                dao.deleteExclusions(segment.id)
                if (target.size == source.size) {
                    if (!keepEvents) items += dao.deleteLineItems(segment.id)
                    dao.deleteLineVectors(segment.id)
                    lines += dao.deleteLines(segment.id)
                    deletedSegments += dao.deleteSegment(segment.id)
                } else {
                    // 一段录音可能分出多件事：音频整段清除，其他事件的文字保留。
                    for (line in target) {
                        items += dao.deleteLineItemsById(line.id)
                        lines += dao.deleteLine(line.id)
                    }
                }
            }
            for (id in events) if (!keepEvents && dao.eventLineCount(id) == 0) {
                items += dao.deleteEventItems(id)
                deletedEvents += dao.deleteEvent(id)
            }
            if (!keepEvents) db.memory().repairMergedItems()
            ForgetResult(deletedSegments, lines, deletedEvents, items)
        }
        for ((segment, lines) in files) {
            cleanup("录音文件删除失败") { val file = File(segment.path); check(file.delete() || !file.exists()) }
            cleanup("声纹缓存删除失败") { removeFeatures(segment, lines) }
        }
        result
    }

    private fun cleanup(message: String, block: () -> Unit) {
        try { block() } catch (_: Exception) { diagnostic(message) } // 不记录路径、原话或异常原文。
    }
}
