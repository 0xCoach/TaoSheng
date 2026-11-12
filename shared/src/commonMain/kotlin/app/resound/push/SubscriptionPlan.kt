package app.resound.push

import app.resound.platform.NowMillis
import app.resound.platform.ZoneProvider

/** 键值持久化由适配层原子提交；null 表示删除。时刻为 Unix 毫秒。 */
object SubscriptionPlan {
    val deadlines = listOf("scheduleAt", "fullAt", "changeAt", "summaryAt", "batchAt", "reminderAt")
    fun deadline(values: Map<String, Long>) = deadlines.mapNotNull { values[it]?.takeIf { at -> at > 0 } }.minOrNull()
    fun hasRecords(s: PushSubscription) = s.records.isNotEmpty() || s.lines != LinesMode.Off
    fun configure(s: PushSubscription, fresh: Boolean, values: Map<String, Long>, now: NowMillis, zone: ZoneProvider): Map<String, Long?> = buildMap {
        val trigger = s.trigger
        val fullCheckAt = s.fullCheckAt
        if (fresh || "scheduleAt" !in values) {
            put("scheduleAt", null)
            if (hasRecords(s) && trigger is RecordTrigger.Schedule) put("scheduleAt", SubscriptionRules.nextSchedule(trigger, now.now(), zone.zoneId()))
        }
        if (fresh || "fullAt" !in values) {
            put("fullAt", null)
            if (hasRecords(s) && s.incremental && fullCheckAt != null && trigger != RecordTrigger.Manual)
                put("fullAt", SubscriptionRules.nextSchedule(RecordTrigger.Schedule(listOf(fullCheckAt)), now.now(), zone.zoneId()))
        }
        if (fresh) {
            put("changeAt", null); put("summaryAt", null); put("reminderAt", if (s.reminders) now.now() else null)
            if (hasRecords(s)) SubscriptionRules.changeAt(trigger, values["lastRecordsAt"]?.takeIf { it > 0 }, now.now())?.let { put("changeAt", it) }
        }
    }
    fun changed(s: PushSubscription, summary: Boolean, values: Map<String, Long>, running: () -> Boolean, now: NowMillis): Map<String, Long?> = buildMap {
        if (summary && s.summaries) put("summaryAt", now.now())
        else if (hasRecords(s)) SubscriptionRules.changeAt(s.trigger, values["lastRecordsAt"]?.takeIf { it > 0 }, now.now())?.let { at ->
            if (running() || "changeAt" !in values) put("changeAt", if (running()) maxOf(now.now() + 1, at) else at)
        }
    }
    fun completed(s: PushSubscription, values: Map<String, Long>, due: Map<String, Long>, started: Long,
        more: Boolean, summaryOnly: Boolean, reminderOnly: Boolean, now: NowMillis, zone: ZoneProvider): Map<String, Long?> = buildMap {
        listOf("changeAt", "summaryAt", "batchAt").forEach { key -> if ((values[key] ?: 0) == (due[key] ?: 0)) put(key, null) }
        if (more) put("batchAt", now.now() + 30_000)
        val trigger = s.trigger
        if (!summaryOnly && !reminderOnly) {
            put("lastRecordsAt", started)
            val changeAt = if (containsKey("changeAt")) get("changeAt") else values["changeAt"]
            if (changeAt != null && trigger is RecordTrigger.OnChange) put("changeAt", maxOf(changeAt, started + trigger.minGapMinutes * 60_000L))
        }
        if (due["scheduleAt"]?.let { it > 0 && it <= started } == true && trigger is RecordTrigger.Schedule)
            put("scheduleAt", SubscriptionRules.nextSchedule(trigger, now.now(), zone.zoneId()))
        s.fullCheckAt?.let { full -> if (due["fullAt"]?.let { it > 0 && it <= started } == true)
            put("fullAt", SubscriptionRules.nextSchedule(RecordTrigger.Schedule(listOf(full)), now.now(), zone.zoneId())) }
    }
}
