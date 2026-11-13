package app.resound.transcribe

import app.resound.data.retryFailed
import app.resound.data.ignoreFailed
import app.resound.data.deleteFailed

import app.resound.scheduling.BackgroundRefreshPolicy
import android.content.Context
import app.resound.scheduling.refreshAtDeadline
import androidx.work.WorkInfo
import app.resound.data.TranscribeDao
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class RoomTranscriptRepository(context: Context, private val dao: TranscribeDao,
    private val settings: TranscribeSettings, private val scheduler: TranscribeScheduler, private val scope: CoroutineScope) : TranscriptRepository {
    private val prefs = context.getSharedPreferences("transcribe-queue", Context.MODE_PRIVATE)
    private val last = MutableStateFlow(prefs.getLong("lastRunAt", 0).takeIf { it > 0 } to prefs.getString("lastError", null))
    // 仅存本进程当前轮次，进程重启不能恢复旧进度。
    private val progress = MutableStateFlow(0 to 0)
    private val effectiveLast = combine(last, settings.ready) { state, hasKey ->
        state.first to if (hasKey) state.second else "转写未就绪，请检查密钥或下载本地模型"
    }
    // 只有原话页/引导提示的订阅者需要分钟文件校对，常驻队列不用这个时钟。
    internal val clock = flow { while (true) { emit(System.currentTimeMillis()); delay(60_000) } }
        .shareIn(scope, SharingStarted.WhileSubscribed(0, replayExpirationMillis = 0), replay = 1)
    private val calendar = calendarChanges(context).shareIn(scope, SharingStarted.Eagerly, replay = 1)
    init {
        scope.launch {
            combine(dao.observeFailedSegments().distinctUntilChanged(), calendar) { rows, _ -> rows }
                .collectLatest { rows ->
                    if (rows.isEmpty()) return@collectLatest
                    val deadline = BackgroundRefreshPolicy.failureDeadline(rows.map { it.firstFailedAt })
                    flowOf(deadline).refreshAtDeadline(System::currentTimeMillis) { dao.refreshFailures(System.currentTimeMillis()) }
                }
        }
    }
    private val usage = calendar.map { LocalDate.now().toString() }.distinctUntilChanged()
        .flatMapLatest { dao.observeUsageTotals(it).distinctUntilChanged() }
    private val initial = TranscribeQueueState(0, 0, false, last.value.first, null, if (settings.ready.value) last.value.second else "转写未就绪，请检查密钥或下载本地模型", 0, 0)
    override val queue: StateFlow<TranscribeQueueState> = combine(dao.observeQueueCounts().distinctUntilChanged(), usage, effectiveLast, scheduler.observe(), calendar) { counts, usage, state, work, _ ->
        val next = work.filter { it.state == WorkInfo.State.ENQUEUED }.map { it.nextScheduleTimeMillis }
            .filter { it > 0 && it != Long.MAX_VALUE }.minOrNull()
            ?: state.first?.plus(settings.intervalMinutes.value * 60_000L)
        TranscribeQueueState(counts.pending, counts.failed, counts.running, state.first, next, state.second,
            usage.todayMillis / 1000, usage.totalMillis / 1000)
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
        return combine(dao.day(start, end), clock) { rows, _ -> rows.filter { it.segment.status != "SkippedEnv" }.map { (segment, lines) ->
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
    override fun ignoreFailed() { scope.launch { dao.ignoreFailed() } }
    override fun retrySegment(segmentId: Long) { scope.launch {
        if (dao.retrySegment(segmentId) > 0) scheduler.runNow()
    } }
    override fun deleteFailed(segmentId: Long) { scope.launch { dao.deleteFailed(segmentId) } }
    suspend fun diagnostics(): String {
        val state = queue.value
        return buildString {
            appendLine("外放期间 ${dao.skippedPlaybackCount()} 段未转写")
            appendLine("转写队列 pending=${state.pending} failed=${state.failed} running=${state.running} lastRunAt=${state.lastRunAt} nextRunAt=${state.nextRunAt} hasError=${state.lastError != null} usedSecondsToday=${state.usedSecondsToday} usedSecondsTotal=${state.usedSecondsTotal}")
            dao.recentAttempts().forEach { appendLine("转写元数据 $it") }
        }
    }
}
