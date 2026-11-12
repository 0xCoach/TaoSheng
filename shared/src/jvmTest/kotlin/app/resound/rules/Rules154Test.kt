package app.resound.rules

import app.resound.push.*
import app.resound.privacy.PrivacyRules
import app.resound.usage.*
import app.resound.things.*
import app.resound.platform.NowMillis
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class Rules154Test {
    @Test fun scheduleMatchesOldCalendarAcrossWeekAndDst() {
        for (stamp in listOf("2026-11-12T22:00:00+08:00[Asia/Shanghai]", "2026-03-08T00:00:00-05:00[America/New_York]", "2026-11-01T01:45:00-04:00[America/New_York]")) {
            val after = ZonedDateTime.parse(stamp)
            val t = RecordTrigger.Schedule(listOf(90, 150, 1320), setOf(1, 4, 7))
            val old = (0L..7L).flatMap { offset -> val d = after.toLocalDate().plusDays(offset)
                if (d.dayOfWeek.value !in t.weekdays) emptyList() else t.times.map { d.atTime(it / 60, it % 60).atZone(after.zone) }
            }.filter { it.isAfter(after) }.minOf { it.toInstant().toEpochMilli() }
            assertEquals(old, SubscriptionRules.nextSchedule(t, after.toInstant().toEpochMilli(), after.zone.id))
        }
    }
    @Test fun manualAndChangeKeepOriginalThrottle() {
        val s = PushSubscription.ForAgents.copy(trigger = RecordTrigger.Manual)
        assertNull(SubscriptionPlan.configure(s, true, emptyMap(), { 1000 }, { "Asia/Shanghai" })["fullAt"])
        assertNull(SubscriptionRules.changeAt(RecordTrigger.Manual, 10, 20))
        assertEquals(300010L, SubscriptionRules.changeAt(RecordTrigger.OnChange(5), 10, 20))
    }
    @Test fun completionPreservesChangesArrivingDuringSend() {
        val s = PushSubscription.ForAgents.copy(trigger = RecordTrigger.OnChange(5))
        val edits = SubscriptionPlan.completed(s, mapOf("changeAt" to 101L, "summaryAt" to 80L), mapOf("changeAt" to 100L, "summaryAt" to 80L),
            100, true, false, false, { 200 }, { "Asia/Shanghai" })
        assertEquals(300100L, edits["changeAt"]); assertNull(edits["summaryAt"]); assertEquals(30200L, edits["batchAt"])
        assertEquals(100L, SubscriptionPlan.deadline(mapOf("fullAt" to 100L, "changeAt" to 0L)))
    }
    @Test fun reviewBodyMatchesRecordedTextAndJson() {
        val sections = listOf(PushReviewSection("一句话", listOf("今天完成了方案，明确下一步。")), PushReviewSection("要紧的", listOf("确认方案")))
        assertEquals("**10月23日 星期五 · 回顾**\n\n今天完成了方案，明确下一步。\n\n### 要紧的\n- 确认方案",
            PushBodyRules.body(LocalDate.parse("2026-10-23"), "备用", sections, PushFormat.Markdown))
        assertEquals("10月23日 星期五 · 回顾\n\n今天完成了方案，明确下一步。\n\n【要紧的】\n确认方案",
            PushBodyRules.body(LocalDate.parse("2026-10-23"), "备用", sections, PushFormat.Text))
        assertEquals("{\"date\":\"2026-10-23\",\"brief\":\"摘要\",\"sections\":[{\"title\":\"一句话\",\"lines\":[\"好\"]}]}",
            PushBodyRules.reviewJson("2026-10-23", "摘要", listOf(PushReviewSection("一句话", listOf("好")))).toString())
    }
    @Test fun deltaOrdersUpsertsThenDeletesAndProtectsSkipped() {
        val current = mapOf("event:2" to buildJsonObject { put("id", 2) })
        val delta = RecordDeltaRules.changes(current, mapOf("event:1" to "old", "event:3" to "old"), setOf("event:3")) { "new" }
        assertEquals("{\"type\":\"records_delta\",\"version\":1,\"changes\":[{\"op\":\"upsert\",\"kind\":\"event\",\"id\":2,\"data\":{\"id\":2}},{\"op\":\"delete\",\"kind\":\"event\",\"id\":1,\"data\":{}}]}", delta.toString())
        assertEquals(0, RecordDeltaRules.changes(current, mapOf("event:2" to "new"), emptySet()) { "new" }["changes"]!!.jsonArray.size)
    }
    @Test fun dailyEnvelopeKeepsExplicitNullAndOptionalLines() {
        val empty = JsonArray(emptyList())
        assertEquals("{\"type\":\"daily_memory\",\"version\":1,\"app\":\"resound\",\"date\":\"2026-11-12\",\"generatedAt\":1,\"markdown\":\"正文\",\"data\":{\"review\":null,\"events\":[],\"items\":[],\"cards\":[]}}",
            PushBodyRules.dailyMemory("2026-11-12", 1, "正文", JsonNull, empty, empty, empty, null).toString())
        assertTrue(PushBodyRules.dailyMemory("x", 1, "", JsonNull, empty, empty, empty, empty)["data"]!!.jsonObject.containsKey("lines"))
    }
    @Test fun summaryMergePreservesUnsentTimelineAndCounts() {
        val path = "daily/2026-11/2026-11-12-Thu_daily-record.md"
        val old = "条数: 3\n原时间线\n<!-- resound:reviews:start -->旧回顾<!-- resound:reviews:end -->\n<!-- resound:cards:start -->旧卡<!-- resound:cards:end -->"
        val fresh = old.replace("原时间线", "不该覆盖").replace("旧回顾", "新回顾").replace("旧卡", "新卡")
        val result = SummaryTemplates.merge(mapOf(path to fresh, "README.md" to "说明"), mapOf(path to old), mapOf(path to 2), mapOf("2026-11-12" to 4))
        assertEquals(old.replace("条数: 3", "条数: 5").replace("旧回顾", "新回顾").replace("旧卡", "新卡"), result[path])
    }
    private val line = ThingLine(50, 1000, 2000, 9, null, 0, "钥匙放在鞋柜", "1970-01-01T08:00:01+08:00[Asia/Shanghai]")
    private val proposal = """{"name":"钥匙","action":"Placed","owner":"Mine","place":"鞋柜","who":null,"lineId":1,"knownId":null,"confident":true,"explicit":false}"""
    @Test fun extractionRejectsMediaAndUnknownSourceWithoutLosingValidSibling() {
        val parsed = DigestThingRules.parse("""{"things":[$proposal,${proposal.replace("\"lineId\":1", "\"lineId\":2")}]}""", listOf(line), setOf(50), emptySet(), emptySet(), NowMillis { 10 })
        assertEquals(1, parsed.rejected); assertEquals(50L, parsed.proposals.single().row.sourceLineId)
        assertTrue(DigestThingRules.parse("""{"things":[$proposal]}""", listOf(line), setOf(50), emptySet(), setOf(50), NowMillis { 10 }).proposals.isEmpty())
    }
    @Test fun extractionRechecksRevisionAndLiveText() {
        val p = DigestThingRules.parse("""{"things":[$proposal]}""", listOf(line), setOf(50), emptySet(), emptySet(), NowMillis { 10 }).proposals
        assertEquals(p, DigestThingRules.valid(p, ThingWatchData(), 0, listOf(line)))
        assertTrue(DigestThingRules.valid(p, ThingWatchData(revision = 1), 0, listOf(line)).isEmpty())
        assertTrue(DigestThingRules.valid(p, ThingWatchData(), 0, listOf(line.copy(text = "重转写"))).isEmpty())
    }
    @Test fun contextUsesLastTwentyAndTenMinuteBoundary() {
        val previous = (1L..30L).map { line.copy(id = it, startAt = 0, endAt = 500) }
        assertEquals((11L..30L).toList(), ThingPromptRules.context(listOf(line), previous).map { it.id })
        assertTrue(ThingPromptRules.context(listOf(line.copy(startAt = 700000)), previous).isEmpty())
    }
    @Test fun findKeepsPendingSeparateAndCapsResults() {
        val records = (1L..41L).map { ThingRecordData(id = it, name = "钥匙$it", action = "Placed", place = "鞋柜", who = null,
            owner = "Mine", at = it, sourceLineId = it, sourceText = "原话", createdAt = it) }
        val result = FindThingRules.select(emptyList(), records, "钥匙", "鞋柜")
        assertEquals(40, result.matches.size); assertTrue(result.truncated); assertTrue(result.matches.all { it.thing == null })
        assertTrue(FindThingRules.select(emptyList(), records, "钥匙", "卧室").matches.isEmpty())
    }
    @Test fun privacyRangeAndAudioOwnershipAreUnchanged() {
        assertEquals(40000L, PrivacyRules.recentStart(1, 100000))
        assertTrue(PrivacyRules.discardRecording(100, 100)); assertFalse(PrivacyRules.discardRecording(99, 100))
        assertFalse(PrivacyRules.cleanupFile("voice.ogg", "voice-input")); assertTrue(PrivacyRules.cleanupFile("mic.ogg", "mic"))
        assertFalse(PrivacyRules.deleteWholeSegment(2, 1))
        try { PrivacyRules.validateRange(2, 1); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun priceDistinguishesNeverConfiguredFromCleared() {
        assertEquals(2.0, UsageRules.price(false, null, 2.0)!!, 0.0)
        assertNull(UsageRules.price(true, "", 2.0)); assertNull(UsageRules.price(true, "NaN", 2.0))
        UsageRules.validate(UsagePrice(0.0, null, 0.9))
        try { UsageRules.validate(UsagePrice(-1.0)); fail() } catch (_: IllegalArgumentException) { }
    }
}
