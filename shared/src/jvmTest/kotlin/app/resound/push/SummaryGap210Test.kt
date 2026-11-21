package app.resound.push

import app.resound.platform.NowMillis
import org.junit.Assert.*
import org.junit.Test

/** 卡 210：文档类通道的摘要按间隔合并（原先每出一张卡就同步一次） */
class SummaryGap210Test {
    private val minute = 60_000L
    private val s = PushSubscription(summaries = true, trigger = RecordTrigger.OnChange(30))
    @Test fun summaryWaitsForGapAndKeepsEarlierSchedule() {
        val now = NowMillis { 100 * minute }
        assertEquals(100 * minute, SubscriptionPlan.changed(s, true, emptyMap(), { false }, now)["summaryAt"])
        val after = mapOf("lastSummaryAt" to 90 * minute)
        assertEquals(120 * minute, SubscriptionPlan.changed(s, true, after, { false }, now)["summaryAt"])
        assertFalse("已排着的不改", "summaryAt" in SubscriptionPlan.changed(s, true, after + ("summaryAt" to 120 * minute), { false }, now))
        val scheduled = s.copy(trigger = RecordTrigger.Schedule(listOf(600)))
        assertEquals(105 * minute, SubscriptionPlan.changed(scheduled, true, mapOf("lastRecordsAt" to 90 * minute), { false }, now)["summaryAt"])
    }
    @Test fun summaryRunRecordsItsTime() {
        val edits = SubscriptionPlan.completed(s, mapOf("summaryAt" to 5L), mapOf("summaryAt" to 5L), 7L, false, true, false, NowMillis { 7L }) { "UTC" }
        assertEquals(7L, edits["lastSummaryAt"]); assertFalse("lastRecordsAt" in edits)
    }
}
