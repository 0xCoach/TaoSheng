@file:OptIn(kotlin.time.ExperimentalTime::class)
package app.resound.envsound

import app.resound.data.*
import app.resound.platform.FileRef
import app.resound.platform.ZoneProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.*

/** 环境音查询、设置、恢复与纠错；推理/下载执行、偏好及文件探测由安卓接入。 */
@OptIn(ExperimentalCoroutinesApi::class)
class EnvSoundStoreRepository(
    private val db: CaptureDatabase, private val scope: CoroutineScope,
    modelState: StateFlow<EnvModelState>, private val download: suspend () -> Unit,
    private val schedule: () -> Unit, private val now: () -> Long,
    private val zoneProvider: ZoneProvider, private val today: (String) -> LocalDate,
    private val isFile: (FileRef) -> Boolean, private val audioFile: (String) -> FileRef?, private val io: CoroutineDispatcher,
    readBoolean: (String, Boolean) -> Boolean, readString: (String) -> String?,
    private val saveSettings: (EnvSoundSettings, Long?) -> Boolean,
) : EnvSoundRepository {
    private val dao = db.envSound()
    override val settings = MutableStateFlow(EnvSoundSettings(readBoolean("enabled", false),
        enumValue(readString("skip"), SkipMode.MarkOnly), enumValue(readString("media"), MediaMode.Keep),
        readBoolean("tags",true), readBoolean("specialSounds",true), readBoolean("showInSummaries",true)))
    override val model = modelState
    // 冷流：仅展示/导出订阅时监听失效；不保留整张环境音、事件表的快照。
    fun presentationChanges(): Flow<Unit> = db.invalidationTracker
        .createFlow("memory_events", "env_sounds").map { Unit }

    /** 展示/导出按需挂起查询；不占线程等待数据库。 */
    suspend fun presentationSnapshot(start: Long, end: Long): Pair<List<MemoryEventEntity>, Map<Long, EventSounds>> = withContext(io) {
        val events = dao.presentationEvents(start, end)
        events to if (events.isEmpty()) emptyMap() else mergeSounds(events,
            dao.presentationRows(events.minOf { it.startAt }, events.maxOf { it.endAt }))
    }
    suspend fun eventSound(id: Long): EventSounds? = withContext(io) {
        val event = dao.presentationEvent(id) ?: return@withContext null
        mergeSounds(listOf(event), dao.presentationRows(event.startAt, event.endAt))[id]
    }
    // 安卓同步入口仍持有原监视器；一次事务回调提交所有设置后才发布。
    override fun update(settings: EnvSoundSettings) {
        val enabledAt = if (settings.enabled && !this.settings.value.enabled) now() else null
        check(saveSettings(settings, enabledAt))
        this.settings.value = settings
        if (settings.enabled) downloadModel()
    }
    private var downloading: Job? = null
    override fun downloadModel() {
        if(!settings.value.enabled || downloading?.isActive == true) return
        downloading = scope.launch { download() }
    }
    private fun bounds(date: LocalDate, zone: TimeZone): Pair<Long, Long> =
        date.atStartOfDayIn(zone).toEpochMilliseconds() to date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds()
    override fun flagged(date: kotlinx.datetime.LocalDate): Flow<List<SegmentSound>> {
        val (from,to) = bounds(date, TimeZone.of(zoneProvider.zoneId()))
        return combine(dao.observeBetween(from, to).distinctUntilChanged(), tick()) { rows, _ -> rows.filter { it.verdict != "Keep" }.map { it.view(isFile) } }.flowOn(io)
    }
    override fun eventSounds(date: kotlinx.datetime.LocalDate): Flow<Map<Long, EventSounds>> {
        val (from,to) = bounds(date, TimeZone.of(zoneProvider.zoneId()))
        return dao.eventsBetween(from, to).distinctUntilChanged().flatMapLatest { events ->
            if (events.isEmpty()) flowOf(emptyMap())
            else dao.observeBetween(events.minOf { it.startAt }, events.maxOf { it.endAt })
                .distinctUntilChanged().map { rows -> mergeSounds(events, rows) }
        }.distinctUntilChanged().flowOn(io)
    }
    override fun eventsWith(tag: EnvTag?, sound: SpecialSound?): Flow<List<Long>> = combine(dao.events().distinctUntilChanged(), dao.observe().distinctUntilChanged()) { events, rows ->
        val merged = mergeSounds(events,rows)
        events.filter { e -> merged[e.id]?.let { (tag == null || tag in it.tags) && (sound == null || sound in it.sounds) } == true }.map { it.id }
    }
    override fun hourly(date: kotlinx.datetime.LocalDate): Flow<List<HourSound>> {
        val zone = TimeZone.of(zoneProvider.zoneId())
        val (from, to) = bounds(date, zone)
        return combine(db.transcribe().observeOverlapping(from, to).distinctUntilChanged(),
            dao.observeBetween(from, to).distinctUntilChanged()) { segments, rows ->
            hourlySoundRows(date, segments, rows, zone)
        }.distinctUntilChanged().flowOn(io)
    }
    override fun stats(days: Int): Flow<EnvStats> {
        val n = days.coerceAtLeast(1)
        // 保留原来五秒检查文件/时钟的可见时机，跨午夜后切换查询范围。
        val start = tick().map { TimeZone.of(zoneProvider.zoneId()).let { zone -> bounds(today(zone.id).minus(n - 1, DateTimeUnit.DAY), zone).first } }.distinctUntilChanged()
        return start.flatMapLatest { from ->
            combine(dao.observeSince(from).distinctUntilChanged(), tick()) { rows, _ ->
                val selected = rows.filter { it.startAt <= now() }
                EnvStats(n, selected.count { it.verdict != "Keep" || it.reportedWrong }, selected.count { it.restored }, selected.count { it.reportedWrong },
                    (selected.filter { it.skipped && !it.restored && !it.reportedWrong }.sumOf { (it.endAt-it.startAt).coerceAtLeast(0) }/60000).toInt())
            }
        }.distinctUntilChanged().flowOn(io)
    }
    override suspend fun restore(segmentId: Long): Result<Unit> = withContext(io) {
        try {
            db.writeTransaction {
                val row = requireNotNull(dao.get(segmentId)) { "未找到这段录音" }
                check(isFile(FileRef(row.path))) { "这段录音已过保留期" }
                if(row.skipped && !row.restored) {
                    check(dao.requeue(segmentId) > 0) { "这段录音尚未跳过，请稍后重试" }
                    dao.restored(segmentId)
                }
            }
            schedule(); Result.success(Unit)
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) { Result.failure(e) }
    }
    override fun reportWrong(segmentId: Long) {
        scope.launch {
            db.writeTransaction {
                val row = dao.get(segmentId) ?: return@writeTransaction
                dao.wrong(segmentId)
                if((row.skipped || row.verdict == "Skippable") && isFile(FileRef(row.path))) {
                    if(dao.requeueCorrection(segmentId) > 0 && row.skipped) dao.restored(segmentId)
                }
            }
            schedule()
        }
    }
    override suspend fun audio(segmentId: Long): FileRef? = withContext(io) { dao.get(segmentId)?.let { audioFile(it.path) } }
    suspend fun diagnostics(): String {
        val recent = dao.since(now()-7*86_400_000L)
        return "设置=${settings.value}\n模型=${model.value}\n最近7天=${recent.groupingBy { it.verdict }.eachCount()}\n平均耗时=${recent.map { it.elapsedMs }.average().takeIf { it.isFinite() } ?: 0.0}ms"
    }
    private fun tick() = flow { while(currentCoroutineContext().isActive) { emit(Unit); delay(5000) } }
    private inline fun <reified T: Enum<T>> enumValue(value: String?, fallback: T) = enumValues<T>().firstOrNull { it.name == value } ?: fallback
}
