package app.resound.digest

import app.resound.context.PlaceAt
import app.resound.context.SituationLookup
import app.resound.data.MemoryEventEntity
import app.resound.data.TranscriptLineEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SituationPrompt130Test {
    private val zone = ZoneId.of("UTC")
    private val lines = listOf(
        TranscriptLineEntity(1, 1, 0, 1000, "第一句", null),
        TranscriptLineEntity(2, 1, 1000, 2000, "第二句", null),
        TranscriptLineEntity(3, 2, 3000, 4000, "第三句", null),
        TranscriptLineEntity(4, 3, 5000, 6000, "第四句", null),
    )
    private fun event(id: Long, start: Long, end: Long) = MemoryEventEntity(
        id = id, startAt = start, endAt = end, scene = "工作", title = "记录$id", summary = "摘要", pointsJson = "[]",
        peopleJson = "[]", place = null, emotion = null, intensity = null, important = false, createdAt = 0, model = "test")
    private val events = listOf(event(1, 0, 2000), event(2, 3000, 4000), event(3, 5000, 6000))

    @Test fun disabledKeepsBothPromptsByteForByteAndNeverQueries() = runBlocking {
        val forbidden = object : SituationLookup {
            override suspend fun placeDuring(start: Long, end: Long): PlaceAt? = error("关闭不应查询")
            override suspend fun envDuring(start: Long, end: Long, limit: Int): List<String> = error("关闭不应查询")
            override suspend fun staysAt(placeName: String, from: Long, to: Long) = emptyList<PlaceAt>()
        }
        val prompt = SituationPrompt(false, forbidden)
        val today = LocalDate.of(2026, 11, 10)
        assertEquals(DigestPrompt.system("原模板", today, "涛声"), prompt.system(DigestPrompt.system("原模板", today, "涛声")))
        assertEquals(MergePrompt.system("合并模板", today, "涛声"), prompt.system(MergePrompt.system("合并模板", today, "涛声")))
        assertEquals(DigestPrompt.transcript(lines, zone, setOf(1)), prompt.transcript(lines, setOf(1), zone))
        assertEquals(MergePrompt.input(events, zone), prompt.merge(events, zone))
    }

    @Test fun enabledAddsOneHeadingPerSegmentOrMergedEventAndOmitsEmptyParts() = runBlocking {
        val queried = mutableListOf<Pair<Long, Long>>()
        val lookup = object : SituationLookup {
            override suspend fun placeDuring(start: Long, end: Long): PlaceAt? {
                queried += start to end
                return if (start == 0L) PlaceAt("公司", false, start, end) else null
            }
            override suspend fun envDuring(start: Long, end: Long, limit: Int) = when (start) {
                0L -> listOf("键盘声", "附近有车流")
                3000L -> listOf("雨")
                else -> emptyList()
            }
            override suspend fun staysAt(placeName: String, from: Long, to: Long) = emptyList<PlaceAt>()
        }
        val prompt = SituationPrompt(true, lookup)
        val (text, indexed) = prompt.transcript(lines, zone = zone)
        assertEquals(lines, indexed)
        assertEquals(listOf(0L to 2000L, 3000L to 4000L, 5000L to 6000L), queried)
        val headings = listOf("【当时在：公司｜周围：键盘声、附近有车流】", "【周围：雨】")
        assertEquals(headings, text.lines().filter { it.startsWith("【") })
        assertEquals(DigestPrompt.transcript(lines, zone).first, text.lines().filterNot { it.startsWith("【") }.joinToString("\n"))
        val merge = prompt.merge(events, zone)
        assertEquals(headings, merge.lines().filter { it.startsWith("【当时") || it.startsWith("【周围") })
        assertTrue(merge.startsWith(headings.first() + "\n【记录 1】"))
        assertTrue(prompt.system("模板").contains("不要把情境行当成原话"))
        assertTrue(prompt.system("模板").contains("以说话内容为准"))
    }
}
