package app.resound.digest

import app.resound.data.TranscriptLineEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DigestRunnerTest {
    private val base = LocalDate.of(2026, 10, 23).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()

    private fun line(id: Long, start: Long, end: Long) = TranscriptLineEntity(
        id = id, segmentId = id, startAt = base + start, endAt = base + end,
        text = "第${id}句", speakerTag = null,
    )

    @Test fun emptyInputHasNoSessions() {
        assertTrue(DigestRunner.sessions(emptyList()).isEmpty())
    }

    @Test fun mergesBelowAndExactlyTenMinutesButSplitsOneMillisecondAbove() {
        val first = line(1, 0, 1000)
        val below = line(2, 600_999, 601_999)
        val exact = line(3, 1_201_999, 1_202_999)
        val above = line(4, 1_803_000, 1_804_000)
        assertEquals(
            listOf(listOf(first, below, exact), listOf(above)),
            DigestRunner.sessions(listOf(first, below, exact, above)),
        )
    }

    @Test fun gapUsesMaximumEndInCurrentSessionAndResetsAfterSplit() {
        val long = line(1, 0, 900_000)
        val nested = line(2, 1000, 2000)
        val atBoundary = line(3, 1_500_000, 1_501_000)
        val split = line(4, 2_101_001, 2_102_001)
        val joinsNew = line(5, 2_702_001, 2_703_001)
        assertEquals(
            listOf(listOf(long, nested, atBoundary), listOf(split, joinsNew)),
            DigestRunner.sessions(listOf(long, nested, atBoundary, split, joinsNew)),
        )
    }
}
