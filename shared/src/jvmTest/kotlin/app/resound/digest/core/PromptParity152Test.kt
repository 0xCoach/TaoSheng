package app.resound.digest.core

import app.resound.digest.core.DigestPrompt as NewDigest
import app.resound.digest.core.MergePrompt as NewMerge
import app.resound.digest.core.ManualTodoPrompt as NewManual
import app.resound.digest.core.ReviewPrompt as NewReview
import app.resound.review.ReviewSection
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

/** 期望文件由 HEAD 迁移前源码在 Robolectric API 29 下录制；保留字段顺序和转义，逐字比较。 */
class PromptParity152Test {
    private val day = LocalDate(2026, 10, 28)
    private val zone = TimeZone.of("Asia/Shanghai")
    private val at = Instant.parse("2026-10-28T01:02:03.456Z").toEpochMilli()
    private val line = TranscriptLineData(7, 1, at, at + 1000, "你好\n测试/路径", "0")
    private val event = MemoryEventData(2, at, at + 1000, "工作", "标题/测试", "摘要", "[\"要点\"]", "[\"小王\"]", null, null, null, true, createdAt = at, model = "test")
    private val item = MemoryItemData(3, 2, "待办", "我要做", "交报告", null, null, null, 7, "Pending", at)
    private fun same(name: String, actual: String) {
        val expected = checkNotNull(javaClass.getResource("/parity152/$name.txt")).readText()
        assertEquals(name, expected, actual)
    }
    @Test fun digestSystem() = same("digest-system", NewDigest.system("{{APP_NAME}}\n{{CALENDAR}}", day, "涛声"))
    @Test fun digestYearBoundary() = same("digest-year", NewDigest.calendar(LocalDate(2027,1,1)))
    @Test fun transcriptVoice() {
        val lines = listOf(line.copy(voiceInputJson = """{"app":"聊天","package_name":"test","title":"A/B","before":"你好","after":null}"""), line.copy(segmentId = 2))
        same("transcript-voice", NewDigest.transcript(lines, zone).first)
    }
    @Test fun transcriptUnknownSpeaker() {
        val lines = listOf(line, line.copy(segmentId = 2, speakerTag = "uncertain:1"))
        same("transcript-unknown", NewDigest.transcript(lines, zone, setOf(1), mapOf(0 to "【当时在：办公室】")).first)
    }
    @Test fun todoContextNull() = same("todo-null", NewDigest.withTodoContext("原话", listOf(item), emptyList()))
    @Test fun todoContextEscapes() = same("todo-escape", NewDigest.withTodoContext("原话", listOf(item.copy(who="A/B", dueText="2026-10-29")), listOf(item)))
    @Test fun mergeSystem() = same("merge-system", NewMerge.system("{{APP_NAME}}\n{{CALENDAR}}", day, "涛声"))
    @Test fun mergeInput() = same("merge-input", NewMerge.input(listOf(event.copy(startAt=at+2000), event), zone, mapOf(2L to "【周围：音乐】")))
    @Test fun manualNoon() = same("manual-noon", NewManual.system(day))
    @Test fun manualMidnight() = same("manual-midnight", NewManual.system(day, LocalTime(23,59)))
    @Test fun reviewInput() {
        val input = ReviewInput(listOf(event), listOf(item, item.copy(id=4,eventId=null,origin="manual",sourceText="记一下")), listOf(event.copy(starred=true,starredAt=at)))
        same("review-input", NewReview.input(day,input,zone))
    }
    @Test fun reviewDst() {
        val date = LocalDate(2026,3,8); val tz = TimeZone.of("America/New_York"); val input = ReviewInput(emptyList(), emptyList())
        same("review-dst", NewReview.input(date,input,tz,150))
    }
    @Test fun reviewSystem() = same("review-system", NewReview.system("{{APP_NAME}} 回顾", "涛声"))
    @Test fun reviewSections() {
        val sections = listOf(ReviewSection("一句话", listOf("A/B\n你好😀"), listOf(null)))
        same("review-sections", NewReview.encode(sections))
    }
}
