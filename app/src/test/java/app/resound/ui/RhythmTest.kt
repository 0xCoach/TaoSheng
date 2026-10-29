package app.resound.ui

import app.resound.digest.MemoryEvent
import app.resound.transcribe.TranscriptLine
import app.resound.ui.today.dayRhythm
import app.resound.ui.today.hourRange
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class RhythmTest {
    private val day = LocalDate.of(2026, 10, 20)
    private fun at(h: Int, m: Int = 0) = day.atTime(h, m).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun ev(id: Long, s: Long, e: Long, scene: String = "闲聊", people: List<String> = emptyList()) =
        MemoryEvent(id, s, e, scene, "事$id", "", emptyList(), people, null, null, null, false, emptyList())
    private fun line(id: Long, s: Long, e: Long) = TranscriptLine(id, s, e, "话", null)

    @Test fun mediaNotCountedAndLongestUsesSpeechTime() {
        // 事件 1 从 9:00 到 10:00，但只说了 2 分钟；事件 2 说了 5 分钟；事件 3 是媒体
        val events = listOf(ev(1, at(9), at(10), people = listOf("老王", "我")), ev(2, at(14), at(14, 10)), ev(3, at(20), at(21), "媒体"))
        val lines = listOf(line(1, at(9), at(9, 2)), line(2, at(14), at(14, 5)), line(3, at(20), at(20, 30)))
        val r = dayRhythm(events, lines)!!
        assertEquals(7, r.speechMinutes)
        assertEquals(2, r.events)
        assertEquals(1, r.people)
        assertEquals(2L, r.longest!!.first.id)
        assertEquals(1800, r.mediaByHour[20])
    }

    @Test fun hourRangeFollowsActivityWithAtLeastEightHours() {
        val r = dayRhythm(listOf(ev(1, at(13), at(14))), listOf(line(1, at(13), at(13, 20))))!!
        val range = hourRange(r, day)
        assertEquals(8, range.count())
        assert(13 in range)
    }
}
