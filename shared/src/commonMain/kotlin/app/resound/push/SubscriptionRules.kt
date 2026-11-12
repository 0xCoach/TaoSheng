@file:OptIn(kotlin.time.ExperimentalTime::class)

package app.resound.push

import kotlinx.datetime.*
import kotlin.time.Instant

object SubscriptionRules {
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
        if (s.footprint) add(PushTopic.Footprint)
    }
    fun tighten(s: PushSubscription, c: PushCapabilities): PushSubscription {
        val rangeDays = s.rangeDays
        val backfillDays = s.backfillDays
        require(rangeDays == null || rangeDays > 0) { "范围天数须大于 0" }
        require(backfillDays == null || backfillDays >= 0) { "补齐天数不能为负数" }
        require(s.fullCheckAt == null || s.fullCheckAt in 0..1439) { "时刻须为 0～1439 分钟" }
        require(s.footprintAt in 0..1439) { "足迹推送时刻须为 0～1439 分钟" }
        when (val t = s.trigger) {
            is RecordTrigger.OnChange -> require(t.minGapMinutes >= 5) { "最短间隔至少 5 分钟" }
            is RecordTrigger.Schedule -> require(t.times.isNotEmpty() && t.times.all { it in 0..1439 } && t.weekdays.all { it in 1..7 }) { "请填写有效的时刻和星期" }
            RecordTrigger.Manual -> Unit
        }
        return s.copy(audio = if (c.audio) s.audio else AudioPush.Off, records = if (c.records) s.records else emptySet(), lines = if (c.records) s.lines else LinesMode.Off,
            incremental = c.incremental && s.incremental, fullCheckAt = s.fullCheckAt.takeIf { c.incremental && s.incremental })
    }
    fun changeAt(t: RecordTrigger, last: Long?, now: Long): Long? = (t as? RecordTrigger.OnChange)?.let {
        maxOf(now, (last ?: 0L) + it.minGapMinutes * 60_000L)
    }
    /** after 毫秒，zoneId 为 IANA 时区；按当地墙上时刻排程。 */
    fun nextSchedule(t: RecordTrigger.Schedule, after: Long, zoneId: String): Long {
        val zone = TimeZone.of(zoneId)
        val date = Instant.fromEpochMilliseconds(after).toLocalDateTime(zone).date
        return (0..7).flatMap { offset ->
            val target = date.plus(offset, DateTimeUnit.DAY)
            if (t.weekdays.isNotEmpty() && target.dayOfWeek.isoDayNumber !in t.weekdays) emptyList()
            else t.times.map { LocalDateTime(target, LocalTime(it / 60, it % 60)).toInstant(zone).toEpochMilliseconds() }
        }.filter { it > after }.min()
    }
}
