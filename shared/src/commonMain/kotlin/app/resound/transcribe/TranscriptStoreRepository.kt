package app.resound.transcribe

import app.resound.data.TranscribeDao
import app.resound.platform.*
import app.resound.scheduling.BackgroundRefreshPolicy
import app.resound.scheduling.refreshAtDeadline
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.*

/** 平台任务的状态快照；时间为 Unix 毫秒，保留无效/未知时间供原规则过滤。 */
data class TranscriptWorkSchedule(val enqueued: Boolean, val nextScheduleTimeMillis: Long)

/** 原话查询、队列合成与命令规则。文件事务及偏好原子提交由平台回调执行。 */
@OptIn(ExperimentalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)
class TranscriptStoreRepository(
    private val dao: TranscribeDao,
    private val settings: TranscribeSettings,
    private val scope: CoroutineScope,
    private val zoneProvider: ZoneProvider,
    private val now: NowMillis,
    private val io: CoroutineDispatcher,
    calendarEvents: Flow<Long>,
    workStates: Flow<List<TranscriptWorkSchedule>>,
    private val today: () -> String,
    initialLast: Pair<Long?, String?>,
    private val saveLast: (Long?, String?) -> Unit,
    private val isAudioFile: (FileRef) -> Boolean,
    private val readVoiceInput: (String?) -> VoiceInputMeta?,
    private val runNowAction: () -> Unit,
    private val deleteFailedAction: suspend (Long) -> Unit,
) : TranscriptRepository {
    private val last = MutableStateFlow(initialLast)
    // 仅存本进程当前轮次，进程重启不能恢复旧进度。
    private val progress = MutableStateFlow(0 to 0)
    private val effectiveLast = combine(last, settings.ready) { state, hasKey ->
        state.first to if (hasKey) state.second else "转写未就绪，请检查密钥或下载本地模型"
    }
    // 只有原话页/引导提示的订阅者需要分钟文件校对，常驻队列不用这个时钟。
    val clock = flow { while (true) { emit(now.now()); delay(60_000) } }
        .shareIn(scope, SharingStarted.WhileSubscribed(0, replayExpirationMillis = 0), replay = 1)
    private val calendar = calendarEvents.shareIn(scope, SharingStarted.Eagerly, replay = 1)
    init {
        scope.launch {
            combine(dao.observeFailedSegments().distinctUntilChanged(), calendar) { rows, _ -> rows }
                .collectLatest { rows ->
                    if (rows.isEmpty()) return@collectLatest
                    val deadline = BackgroundRefreshPolicy.failureDeadline(rows.map { it.firstFailedAt })
                    flowOf(deadline).refreshAtDeadline(now::now) { dao.refreshFailures(now.now()) }
                }
        }
    }
    private val usage = calendar.map { today() }.distinctUntilChanged()
        .flatMapLatest { dao.observeUsageTotals(it).distinctUntilChanged() }
    private val initial = TranscribeQueueState(0, 0, false, last.value.first, null, if (settings.ready.value) last.value.second else "转写未就绪，请检查密钥或下载本地模型", 0, 0)
    override val queue: StateFlow<TranscribeQueueState> = combine(dao.observeQueueCounts().distinctUntilChanged(), usage, effectiveLast, workStates, calendar) { counts, usage, state, work, _ ->
        val next = work.filter { it.enqueued }.map { it.nextScheduleTimeMillis }
            .filter { it > 0 && it != Long.MAX_VALUE }.minOrNull()
            ?: state.first?.plus(settings.intervalMinutes.value * 60_000L)
        TranscribeQueueState(counts.pending, counts.failed, counts.running, state.first, next, state.second,
            usage.todayMillis / 1000, usage.totalMillis / 1000)
    }.combine(progress) { state, (total, done) ->
        state.copy(runTotal = total, runDone = done)
    }.stateIn(scope, SharingStarted.Eagerly, initial)
    fun updateProgress(total: Int, done: Int) { progress.value = total to done }
    fun updateState(at: Long?, error: String?) {
        saveLast(at, error)
        last.value = at to error
    }
    override fun day(date: kotlinx.datetime.LocalDate): Flow<List<SegmentTranscript>> {
        val zone = TimeZone.of(zoneProvider.zoneId())
        val start = date.atStartOfDayIn(zone).toEpochMilliseconds()
        val end = date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds()
        return combine(dao.day(start, end), clock) { rows, _ -> rows.filter { it.segment.status != "SkippedEnv" }.map { (segment, lines) ->
            SegmentTranscript(segment.id, segment.startAt, segment.endAt, TranscriptStatus.valueOf(segment.status),
                lines.sortedWith(compareBy({ it.startAt }, { it.id })).map { TranscriptLine(it.id, it.startAt, it.endAt, it.text, it.speakerTag, it.speakerName, it.speakerScore, it.segmentId, readVoiceInput(it.voiceInputJson)) },
                segment.error, segment.sourceId != "voice-input" && isAudioFile(FileRef(segment.path)))
        } }.flowOn(io)
    }
    override fun transcribeAnyway(segmentId: Long) { scope.launch {
        if (isAudioFile(FileRef(dao.segment(segmentId).path)) && dao.transcribeAnyway(segmentId) > 0) runNowAction()
    } }
    override fun runNow() = runNowAction()
    override fun retryFailed() { scope.launch { dao.retryFailed(now.now()); runNowAction() } }
    override fun ignoreFailed() { scope.launch { dao.ignoreFailed(now.now()) } }
    override fun retrySegment(segmentId: Long) { scope.launch {
        if (dao.retrySegment(segmentId) > 0) runNowAction()
    } }
    override fun deleteFailed(segmentId: Long) { scope.launch { deleteFailedAction(segmentId) } }
    suspend fun diagnostics(): String {
        val state = queue.value
        return buildString {
            appendLine("外放期间 ${dao.skippedPlaybackCount()} 段未转写")
            appendLine("转写队列 pending=${state.pending} failed=${state.failed} running=${state.running} lastRunAt=${state.lastRunAt} nextRunAt=${state.nextRunAt} hasError=${state.lastError != null} usedSecondsToday=${state.usedSecondsToday} usedSecondsTotal=${state.usedSecondsTotal}")
            dao.recentAttempts().forEach { appendLine("转写元数据 $it") }
        }
    }
}
