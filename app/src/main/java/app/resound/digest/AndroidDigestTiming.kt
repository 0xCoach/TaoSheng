package app.resound.digest

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId

/** 与整理服务/密钥分开持有，仍使用原来的 digest 设置文件。 */
class AndroidDigestTiming(private val context: Context) : DigestTiming {
    private val prefs = context.getSharedPreferences("digest", Context.MODE_PRIVATE)
    private val mutableMode = MutableStateFlow(runCatching {
        DigestMode.valueOf(prefs.getString("digestMode", "Interval")!!)
    }.getOrDefault(DigestMode.Interval))
    override val mode = mutableMode.asStateFlow()
    private val mutablePoints = MutableStateFlow(normalize(prefs.getString("timePoints", "")!!
        .split(',').mapNotNull(String::toIntOrNull)))
    override val timePoints = mutablePoints.asStateFlow()
    private val mutableNext = MutableStateFlow<Long?>(null)
    override val nextDigestAt = mutableNext.asStateFlow()
    var onChanged: () -> Unit = {}

    init {
        // 首次升级从现在开始跟踪，避免把启用前的历史时间点误当待办。
        if (!prefs.contains("timedGeneration")) {
            check(prefs.edit().putString("timedGeneration", java.util.UUID.randomUUID().toString())
                .putLong("timedSince", System.currentTimeMillis()).commit())
        }
    }
    private fun android.content.SharedPreferences.Editor.newTimedGeneration() = apply {
        putString("timedGeneration", java.util.UUID.randomUUID().toString())
        putLong("timedSince", System.currentTimeMillis())
    }
    internal fun pendingTimed(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): app.resound.scheduling.TimedDigestRecovery.Ticket? {
        // 一次快照同时读取配置和完成凭据，避免并发修改时间点时混用新旧设置。
        val state = prefs.all
        val generation = state["timedGeneration"] as? String ?: return null
        val points = (state["timePoints"] as? String).orEmpty().split(',').mapNotNull(String::toIntOrNull)
        return app.resound.scheduling.TimedDigestRecovery.pending(generation,
            state["digestMode"] == DigestMode.TimePoints.name,
            state["timedSince"] as? Long ?: now,
            state["timedCompleted:$generation"] as? Long ?: 0L,
            now, DigestTime.recent(points, now, zone))
    }
    internal fun completeTimed(ticket: app.resound.scheduling.TimedDigestRecovery.Ticket) {
        // 完成凭据按配置代次隔离；旧任务结束不能覆盖用户刚改的设置。
        check(prefs.edit().putLong("timedCompleted:${ticket.generation}", ticket.target).commit())
    }

    @Synchronized override fun setMode(mode: DigestMode) {
        if (mode == mutableMode.value) return
        check(prefs.edit().putString("digestMode", mode.name).newTimedGeneration().commit())
        mutableMode.value = mode
        onChanged()
    }
    @Synchronized override fun addTimePoint(minutes: Int) {
        if (minutes !in 0..1439 || minutes in timePoints.value || timePoints.value.size >= DigestTiming.MAX_TIME_POINTS) return
        save(timePoints.value + minutes)
    }
    @Synchronized override fun removeTimePoint(minutes: Int) {
        if (minutes !in timePoints.value) return
        save(timePoints.value - minutes)
    }
    private fun save(points: List<Int>) {
        val normalized = normalize(points)
        check(prefs.edit().putString("timePoints", normalized.joinToString(",")).newTimedGeneration().commit())
        mutablePoints.value = normalized
        onChanged()
    }
    internal fun next(at: Long?) { mutableNext.value = at }
    override fun canScheduleExact() = Build.VERSION.SDK_INT < 31 ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    companion object {
        private fun normalize(points: List<Int>) = points.filter { it in 0..1439 }.distinct().sorted().take(DigestTiming.MAX_TIME_POINTS)
    }
}

internal object DigestTime {
    /** 严格晚于 now；按当地日历构造，跨午夜和时区变化都重新计算。 */
    fun next(points: List<Int>, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return (0L..2L).flatMap { offset ->
            points.filter { it in 0..1439 }.map { minute ->
                date.plusDays(offset).atTime(minute / 60, minute % 60).atZone(zone).toInstant().toEpochMilli()
            }
        }.filter { it > now }.minOrNull()
    }
    /** 只需今天和昨天：更早的漏点会合并进最近一次到期任务。 */
    fun recent(points: List<Int>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Long> {
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return (0L..1L).flatMap { days -> points.filter { it in 0..1439 }.map { minute ->
            date.minusDays(days).atTime(minute / 60, minute % 60).atZone(zone).toInstant().toEpochMilli()
        } }
    }
}
