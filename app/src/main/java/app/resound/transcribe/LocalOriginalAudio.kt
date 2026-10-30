package app.resound.transcribe

import android.content.SharedPreferences
import app.resound.data.SegmentEntity
import app.resound.data.TranscribeDao
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** 清理与声纹取快照共用锁；只删文件，不改段、原话和事件。 */
class LocalOriginalAudio(
    private val dao: TranscribeDao,
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    private val guard: suspend (suspend (Set<Long>) -> Unit) -> Unit = { it(emptySet()) },
    private val playback: PlaybackCaptureHold,
    private val now: () -> Long = System::currentTimeMillis,
) : OriginalAudio {
    override val retentionDays = MutableStateFlow(prefs.getInt("originalRetentionDays", DEFAULT_RETENTION_DAYS).takeIf { it in RETENTION_DAY_RANGE } ?: DEFAULT_RETENTION_DAYS)
    override val keepStarred = MutableStateFlow(prefs.getBoolean("originalKeepStarred", true))
    override fun setRetentionDays(days: Int) {
        require(days in RETENTION_DAY_RANGE)
        val shorter = days < retentionDays.value
        check(prefs.edit().putInt("originalRetentionDays", days).commit())
        retentionDays.value = days
        if (shorter) scope.launch { cleanup() }
    }
    override fun setKeepStarred(keep: Boolean) {
        check(prefs.edit().putBoolean("originalKeepStarred", keep).commit())
        keepStarred.value = keep
        if (!keep) scope.launch { cleanup() }
    }
    private suspend fun existing() = dao.allAudio().filter { File(it.path).isFile }
    private fun pending(s: SegmentEntity) = when (s.status) {
        "Failed" -> s.nextRetryAt != null && s.attempts < 5
        "Done" -> s.completedAt == null || s.voiceprintProcessedAt == null
        "Empty", "SkippedPlayback", "SkippedEnv", "Abandoned", "Quiet" -> s.completedAt == null
        else -> true
    }
    private fun days(rows: List<SegmentEntity>): Int {
        val zone = ZoneId.systemDefault()
        return rows.flatMap { s ->
            val start = Instant.ofEpochMilli(s.startAt).atZone(zone).toLocalDate().toEpochDay()
            val end = Instant.ofEpochMilli(maxOf(s.startAt, s.endAt - 1)).atZone(zone).toLocalDate().toEpochDay()
            (start..end).toList()
        }.distinct().size
    }
    override fun usage(): Flow<AudioUsage> = flow {
        while (currentCoroutineContext().isActive) {
            val rows = existing(); val stars = dao.starredAudio().toSet()
            emit(AudioUsage(rows.sumOf { File(it.path).length() }, rows.filter { it.id in stars }.sumOf { File(it.path).length() },
                days(rows), rows.count(::pending)))
            delay(5_000)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    private suspend fun trim(retention: Int?, includeStarred: Boolean, delete: Boolean): AudioTrim {
        var result = AudioTrim(0, 0, 0, 0)
        val action: suspend (Set<Long>) -> Unit = { reading ->
            val timestamp = now(); dao.refreshFailures(timestamp)
            val rows = existing(); val stars = dao.starredAudio().toSet()
            val candidates = rows.filter { s ->
                !pending(s) && s.id !in reading && (includeStarred || s.id !in stars) &&
                    (retention == null || ((s.sourceId != "import" || s.status in setOf("Abandoned", "Quiet", "SkippedEnv")) && timestamp - (s.completedAt ?: s.firstFailedAt ?: timestamp) >= (if (s.status == "SkippedEnv") maxOf(1, retention) else retention) * 86_400_000L))
            }
            var bytes = 0L
            val removed = candidates.filter { s ->
                val file = File(s.path); val size = file.length()
                (!delete || file.delete()).also { if (it) bytes += size }
            }
            result = AudioTrim(bytes, days(removed), removed.size, rows.count { pending(it) || it.id in reading })
        }
        guard(action)
        return result
    }
    internal suspend fun cleanup(): AudioTrim = trim(retentionDays.value, !keepStarred.value, true)
    override suspend fun previewTrim(days: Int): AudioTrim {
        require(days in RETENTION_DAY_RANGE)
        return trim(days, !keepStarred.value, false)
    }
    override suspend fun previewClear(includeStarred: Boolean) = trim(null, includeStarred, false)
    override suspend fun clear(includeStarred: Boolean) = trim(null, includeStarred, true)
    override suspend fun locate(segmentId: Long): SegmentAudio? = dao.findSegment(segmentId)?.let {
        val file = File(it.path)
        if (file.isFile) SegmentAudio(it.id, file, it.startAt, it.endAt) else null
    }
    override suspend fun available(segmentIds: Set<Long>): Set<Long> = dao.allAudio().filter { it.id in segmentIds && File(it.path).isFile }.mapTo(mutableSetOf()) { it.id }
    override fun beginPlayback() = playback.begin()
    override fun endPlayback() = playback.end()
}

/** 只在进程内持有，进程重建不会遗留暂停。重复 begin 不延长最长让麦时间。 */
class PlaybackCaptureHold(private val scope: CoroutineScope, private val hold: (Boolean) -> Unit,
    private val timeoutMillis: Long = 30 * 60_000L) {
    private var active = false
    private var generation = 0L
    private var timeout: Job? = null
    @Synchronized fun begin() {
        if (active) return
        active = true
        val token = ++generation
        hold(true)
        timeout = scope.launch {
            try { delay(timeoutMillis) } finally { release(token) }
        }
    }
    @Synchronized private fun release(token: Long) { if (token == generation) end() }
    @Synchronized fun end() {
        if (!active) return
        active = false
        generation++
        timeout?.cancel(); timeout = null
        hold(false)
    }
}
