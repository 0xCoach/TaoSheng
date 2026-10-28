package app.resound.transcribe

import android.content.Context
import androidx.work.WorkInfo
import app.resound.data.TranscribeDao
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class RoomTranscriptRepository(context: Context, private val dao: TranscribeDao,
    private val settings: TranscribeSettings, private val scheduler: TranscribeScheduler, private val scope: CoroutineScope) : TranscriptRepository {
    private val prefs = context.getSharedPreferences("transcribe-queue", Context.MODE_PRIVATE)
    private val last = MutableStateFlow(prefs.getLong("lastRunAt", 0).takeIf { it > 0 } to prefs.getString("lastError", null))
    // 仅存本进程当前轮次，进程重启不能恢复旧进度。
    private val progress = MutableStateFlow(0 to 0)
    private val effectiveLast = combine(last, settings.ready) { state, hasKey ->
        state.first to if (hasKey) state.second else "转写未就绪，请检查密钥或下载本地模型"
    }
    // 每分钟刷新自然日用量和文件存在状态，避免跨午夜或清理后界面停在旧快照。
    private val clock = flow { while (true) { emit(System.currentTimeMillis()); delay(60_000) } }.shareIn(scope, SharingStarted.Eagerly, replay = 1)
    private val initial = TranscribeQueueState(0, 0, false, last.value.first, null, if (settings.ready.value) last.value.second else "转写未就绪，请检查密钥或下载本地模型", 0, 0)
    override val queue: StateFlow<TranscribeQueueState> = combine(dao.observeSegments(), dao.observeUsage(), effectiveLast, scheduler.observe(), clock) { segments, usage, state, work, _ ->
        val next = work.filter { it.state == WorkInfo.State.ENQUEUED }.map { it.nextScheduleTimeMillis }
            .filter { it > 0 && it != Long.MAX_VALUE }.minOrNull()
            ?: state.first?.plus(settings.intervalMinutes.value * 60_000L)
        TranscribeQueueState(segments.count { it.status == "Pending" }, segments.count { it.status == "Failed" },
            segments.any { it.status == "Running" }, state.first, next, state.second,
            usage.firstOrNull { it.day == LocalDate.now().toString() }?.sentMillis?.div(1000) ?: 0,
            usage.sumOf { it.sentMillis } / 1000)
    }.combine(progress) { state, (total, done) ->
        state.copy(runTotal = total, runDone = done)
    }.stateIn(scope, SharingStarted.Eagerly, initial)
    fun updateProgress(total: Int, done: Int) { progress.value = total to done }
    fun updateState(at: Long?, error: String?) {
        check(prefs.edit().putLong("lastRunAt", at ?: 0).putString("lastError", error).commit())
        last.value = at to error
    }
    override fun day(date: LocalDate): Flow<List<SegmentTranscript>> {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return combine(dao.day(start, end), clock) { rows, _ -> rows.map { (segment, lines) ->
            SegmentTranscript(segment.id, segment.startAt, segment.endAt, TranscriptStatus.valueOf(segment.status),
                lines.sortedWith(compareBy({ it.startAt }, { it.id })).map { TranscriptLine(it.id, it.startAt, it.endAt, it.text, it.speakerTag, it.speakerName, it.speakerScore, it.segmentId, app.resound.transcribe.VoiceInputMetadata.read(it.voiceInputJson)) },
                segment.error, segment.sourceId != "voice-input" && File(segment.path).isFile)
        } }.flowOn(Dispatchers.IO)
    }
    override fun transcribeAnyway(segmentId: Long) { scope.launch {
        if (File(dao.segment(segmentId).path).isFile && dao.transcribeAnyway(segmentId) > 0) scheduler.runNow()
    } }
    override fun runNow() = scheduler.runNow()
    override fun retryFailed() { scope.launch { dao.retryFailed(); scheduler.runNow() } }
    suspend fun diagnostics(): String {
        val state = queue.value
        return buildString {
            appendLine("外放期间 ${dao.skippedPlaybackCount()} 段未转写")
            appendLine("转写队列 pending=${state.pending} failed=${state.failed} running=${state.running} lastRunAt=${state.lastRunAt} nextRunAt=${state.nextRunAt} hasError=${state.lastError != null} usedSecondsToday=${state.usedSecondsToday} usedSecondsTotal=${state.usedSecondsTotal}")
            dao.recentAttempts().forEach { appendLine("转写元数据 $it") }
        }
    }
}
