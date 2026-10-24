package app.resound.push

import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime

internal object Subscriptions {
    fun migrate(topics: Set<PushTopic>, lines: Boolean) = PushSubscription(
        summaries = PushTopic.DailyReview in topics || PushTopic.SkillCard in topics,
        reminders = PushTopic.TodoReminder in topics,
        records = if (PushTopic.DailyMemory in topics) RecordKind.entries.toSet() else emptySet(),
        lines = if (PushTopic.DailyMemory in topics && lines) LinesMode.All else LinesMode.Off,
        trigger = if (PushTopic.DailyMemory in topics) RecordTrigger.Schedule(listOf(1320)) else RecordTrigger.OnChange(),
        incremental = false, fullCheckAt = null,
    )
    fun topics(s: PushSubscription): Set<PushTopic> = buildSet {
        if (s.summaries) { add(PushTopic.DailyReview); add(PushTopic.SkillCard) }
        if (s.reminders) add(PushTopic.TodoReminder)
        if (s.records.isNotEmpty() || s.lines != LinesMode.Off) add(PushTopic.DailyMemory)
    }
    fun tighten(s: PushSubscription, c: PushCapabilities): PushSubscription {
        require(s.rangeDays == null || s.rangeDays > 0) { "范围天数须大于 0" }
        require(s.backfillDays == null || s.backfillDays >= 0) { "补齐天数不能为负数" }
        require(s.fullCheckAt == null || s.fullCheckAt in 0..1439) { "时刻须为 0～1439 分钟" }
        when (val t = s.trigger) {
            is RecordTrigger.OnChange -> require(t.minGapMinutes >= 5) { "最短间隔至少 5 分钟" }
            is RecordTrigger.Schedule -> require(t.times.isNotEmpty() && t.times.all { it in 0..1439 } && t.weekdays.all { it in 1..7 }) { "请填写有效的时刻和星期" }
            RecordTrigger.Manual -> Unit
        }
        return s.copy(records = if (c.records) s.records else emptySet(), lines = if (c.records) s.lines else LinesMode.Off,
            incremental = c.incremental && s.incremental, fullCheckAt = s.fullCheckAt.takeIf { c.incremental && s.incremental })
    }
    fun json(s: PushSubscription) = JSONObject().put("summaries", s.summaries).put("reminders", s.reminders)
        .put("records", JSONArray(s.records.sortedBy { it.ordinal }.map { it.name })).put("lines", s.lines.name)
        .put("trigger", when (val t = s.trigger) {
            is RecordTrigger.OnChange -> JSONObject().put("type", "change").put("gap", t.minGapMinutes)
            is RecordTrigger.Schedule -> JSONObject().put("type", "schedule").put("times", JSONArray(t.times.distinct().sorted())).put("weekdays", JSONArray(t.weekdays.sorted()))
            RecordTrigger.Manual -> JSONObject().put("type", "manual")
        }).put("incremental", s.incremental).put("fullCheckAt", s.fullCheckAt ?: JSONObject.NULL)
        .put("layout", s.layout.name).put("rangeDays", s.rangeDays ?: JSONObject.NULL).put("excludeMedia", s.excludeMedia)
        .put("backfillDays", s.backfillDays ?: JSONObject.NULL)
    fun read(j: JSONObject): PushSubscription {
        val t = j.getJSONObject("trigger")
        fun number(key: String) = if (j.isNull(key)) null else j.getInt(key)
        return PushSubscription(j.getBoolean("summaries"), j.getBoolean("reminders"),
            j.getJSONArray("records").strings().map { RecordKind.valueOf(it) }.toSet(), LinesMode.valueOf(j.getString("lines")),
            when (t.getString("type")) {
                "change" -> RecordTrigger.OnChange(t.getInt("gap"))
                "schedule" -> RecordTrigger.Schedule(t.getJSONArray("times").strings().map(String::toInt), t.getJSONArray("weekdays").strings().map(String::toInt).toSet())
                else -> RecordTrigger.Manual
            }, j.getBoolean("incremental"), number("fullCheckAt"), FolderLayout.valueOf(j.getString("layout")), number("rangeDays"), j.getBoolean("excludeMedia"), number("backfillDays"))
    }
    fun nextSchedule(t: RecordTrigger.Schedule, after: ZonedDateTime): Long = (0L..7L).flatMap { offset ->
        val date = after.toLocalDate().plusDays(offset)
        if (t.weekdays.isNotEmpty() && date.dayOfWeek.value !in t.weekdays) emptyList() else t.times.map {
            date.atTime(it / 60, it % 60).atZone(after.zone)
        }
    }.filter { it.isAfter(after) }.minOf { it.toInstant().toEpochMilli() }
    fun changeAt(t: RecordTrigger, last: Long?, now: Long): Long? = (t as? RecordTrigger.OnChange)?.let {
        maxOf(now, (last ?: 0L) + it.minGapMinutes * 60_000L)
    }
}
internal fun JSONArray.strings() = (0 until length()).map { get(it).toString() }
internal fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
