package app.resound.ui

import app.resound.transcribe.TranscriptLine
import app.resound.ui.transcript.PlayTimeline
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayTimelineTest {
    private fun line(id: Long, seg: Long, s: Long, e: Long) = TranscriptLine(id, s, e, "话", null, segmentId = seg)
    // 段 1：1000–5000（4 秒）；段 2：60000–66000（6 秒）；中间 55 秒空隙不算
    private val t = PlayTimeline(listOf(line(1, 1, 1000, 3000), line(2, 1, 3000, 5000), line(3, 2, 60000, 66000)))

    @Test fun totalSkipsGaps() = assertEquals(10_000L, t.total)
    @Test fun elapsedInSecondSegment() = assertEquals(6_000L, t.elapsed(2, 62_000))
    @Test fun locateMapsBackToSegmentAndTime() {
        assertEquals(1L to 3_000L, t.locate(2_000))
        assertEquals(2L to 61_000L, t.locate(5_000))
    }
}
