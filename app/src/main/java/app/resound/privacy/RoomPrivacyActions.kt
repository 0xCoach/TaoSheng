package app.resound.privacy

import androidx.room.withTransaction
import app.resound.data.CaptureDatabase
import app.resound.data.SegmentEntity
import app.resound.data.TranscriptLineEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    private val scheduleCleanup: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) : PrivacyActions, app.resound.transcribe.SegmentDeleter {
    private val gate = Mutex()
    private val dao = db.privacy()
    private val requests = mutableMapOf<Long, CompletableDeferred<ForgetResult>>()
    private val progress = MutableStateFlow<Map<Long, EventDeletionPhase>>(emptyMap())
    val eventProgress = progress.asStateFlow()

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
        val completion = CompletableDeferred<ForgetResult>()
        val existing = synchronized(requests) {
            val pending = requests[eventId]
            if (pending == null) requests[eventId] = completion
            pending
        }
        if (existing != null) return@withContext existing.await()
        var phase = EventDeletionPhase.Waiting
        var at = android.os.SystemClock.elapsedRealtime()
        fun record(next: EventDeletionPhase? = null) {
            val current = android.os.SystemClock.elapsedRealtime()
            // 只记阶段与耗时，不记录事件 ID、路径或原话。
            android.util.Log.i("PrivacyDelete", "phase=${phase.name} elapsed_ms=${current - at}")
            if (next != null) { phase = next; at = current; progress.update { it + (eventId to next) } }
        }
        progress.update { it + (eventId to phase) }
        try {
            val result = gate.withLock { erase(eventId, onStage = { record(it) }) { dao.eventSegments(eventId) } }
            completion.complete(result)
            result
        } catch (e: Throwable) {
            completion.completeExceptionally(e)
            throw e
        } finally {
            record()
            progress.update { it - eventId }
            synchronized(requests) { requests.remove(eventId) }
        }
    }

    override suspend fun forgetSegment(segmentId: Long): ForgetResult = withContext(Dispatchers.IO + NonCancellable) {
        gate.withLock { erase { listOfNotNull(dao.segment(segmentId)) } }
    }

    override suspend fun deleteSegments(segmentIds: List<Long>): Int = withContext(Dispatchers.IO + NonCancellable) {
        gate.withLock {
            erase(keepEvents = true) { segmentIds.distinct().mapNotNull { dao.segment(it) } }.segments
        }
    }

    private suspend fun erase(explicitEvent: Long? = null, keepEvents: Boolean = false,
                              onStage: (EventDeletionPhase) -> Unit = {}, select: suspend () -> List<SegmentEntity>): ForgetResult {
        onStage(EventDeletionPhase.WaitingForAudio)
        return voiceprintGuard {
            onStage(EventDeletionPhase.WaitingForDatabase)
            val files = mutableListOf<Pair<SegmentEntity, List<TranscriptLineEntity>>>()
            val result = db.withTransaction {
                onStage(EventDeletionPhase.Records)
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
                    if (segment.path.isNotBlank() && segment.sourceId != "voice-input")
                        dao.queueCleanup(app.resound.data.PrivacyFileCleanup(segment.path))
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
                segments.mapNotNull { it.importRecordId }.distinct().forEach { db.imports().invalidate(it) }
                for (id in events) if (!keepEvents && dao.eventLineCount(id) == 0) {
                    items += dao.deleteEventItems(id)
                    deletedEvents += dao.deleteEvent(id)
                }
                if (!keepEvents) db.memory().repairMergedItems()
                ForgetResult(deletedSegments, lines, deletedEvents, items)
            }
            onStage(EventDeletionPhase.Files)
            if (files.isNotEmpty()) runCatching { scheduleCleanup() }
            for ((segment, lines) in files) {
                cleanup("声纹缓存删除失败") { removeFeatures(segment, lines) }
            }
            if (!retryFileCleanup()) throw FileCleanupPending()
            result
        }
    }

    /** 队列与数据库删除同事务落盘；重复执行、进程重启后执行均安全。 */
    suspend fun retryFileCleanup(): Boolean = withContext(Dispatchers.IO) {
        var complete = true
        for (row in dao.pendingCleanup()) {
            try {
                val file = File(row.path)
                check(file.delete() || !file.exists())
                dao.cleaned(row.path)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
              catch (_: Exception) { complete = false; diagnostic("录音文件删除失败") }
        }
        complete
    }

    private fun cleanup(message: String, block: () -> Unit) {
        try { block() } catch (_: Exception) { diagnostic(message) } // 不记录路径、原话或异常原文。
    }
}

class FileCleanupPending : IllegalStateException("记录已删除，但部分录音文件尚未清理，将自动重试。")

enum class EventDeletionPhase(val label: String) {
    Waiting("等待前一项删除完成…"),
    WaitingForAudio("等待音频任务释放…"),
    WaitingForDatabase("等待数据处理…"),
    Records("正在删除记录…"),
    Files("记录已删除，正在清理录音…"),
}
