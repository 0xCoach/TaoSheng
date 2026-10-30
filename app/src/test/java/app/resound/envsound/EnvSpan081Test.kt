package app.resound.envsound

import app.resound.data.SegmentEntity
import org.junit.Assert.*
import org.junit.Test
import java.time.*

/** 节奏卡环境图标：按时长算、够多才画、连着相同的合成一段 */
class EnvSpan081Test {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val date = LocalDate.of(2026, 10, 31)
    private fun at(h: Int, m: Int) = date.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private var id = 0L
    private val segs = mutableListOf<SegmentEntity>()
    private val rows = mutableListOf<EnvSoundRow>()
    private fun clip(h: Int, m: Int, minutes: Int, tags: String = "", sounds: String = "") {
        val s = SegmentEntity(++id, at(h, m), at(h, m) + minutes * 60_000L, "phone", "/x/$id", 0)
        segs += s
        rows += EnvSoundRow(s.id, s.startAt, s.endAt, s.path, "Keep", null, tags, sounds, "", false, false)
    }
    private fun hours() = hourlySounds(date, segs, rows, zone).associateBy { it.hour }
    private fun speech(vararg p: Pair<Int, Int>) = List(24) { h -> p.firstOrNull { it.first == h }?.second?.times(60) ?: 0 }

    @Test fun oneMinuteOfTypingDoesNotMarkTheHour() {
        clip(9, 0, 1, "Keyboard"); clip(9, 10, 20)
        assertEquals(60_000L, hours()[9]!!.tagMs[EnvTag.Keyboard])
        assertTrue(envSpans(speech(9 to 21), hours()).isEmpty())
    }

    @Test fun longestEnvironmentWinsNotMostSegments() {
        // 三小段车流共 3 分钟，一长段雨 12 分钟：雨
        clip(14, 0, 1, "Traffic"); clip(14, 5, 1, "Traffic"); clip(14, 10, 1, "Traffic"); clip(14, 20, 12, "Rain")
        assertEquals(EnvTag.Rain, hours()[14]!!.tag)
        assertEquals(listOf(EnvTag.Rain), envSpans(speech(14 to 15), hours()).map { it.tag })
    }

    @Test fun needsEnoughSpeechAndShare() {
        clip(8, 0, 4, "Music")
        assertTrue("说话不到 5 分钟不画", envSpans(speech(8 to 4), hours()).isEmpty())
        clip(20, 0, 4, "Pet")
        assertTrue("4 分钟占 40 分钟说话不到四分之一", envSpans(speech(20 to 40), hours()).isEmpty())
        assertEquals(1, envSpans(speech(20 to 12), hours()).size)
    }

    @Test fun clipAcrossHoursCountsOnlyItsPartInEachHour() {
        clip(10, 50, 20, "Water")
        assertEquals(10 * 60_000L, hours()[10]!!.tagMs[EnvTag.Water])
        assertEquals(10 * 60_000L, hours()[11]!!.tagMs[EnvTag.Water])
    }

    @Test fun consecutiveSameHoursMergeOthersSplit() {
        clip(9, 0, 30, "Keyboard"); clip(10, 0, 30, "Keyboard"); clip(11, 0, 30, "Keyboard")
        clip(12, 0, 30, "Rain"); clip(14, 0, 30, "Rain")
        val spans = envSpans(speech(9 to 40, 10 to 40, 11 to 40, 12 to 40, 14 to 40), hours())
        assertEquals(listOf(Triple(EnvTag.Keyboard, 9, 11), Triple(EnvTag.Rain, 12, 12), Triple(EnvTag.Rain, 14, 14)),
            spans.map { Triple(it.tag, it.from, it.to) })
        assertEquals(90 * 60_000L, spans.first().ms)
    }
}
