package app.resound.digest

import app.resound.data.TranscriptLineEntity
import org.junit.Assert.*
import org.junit.Test

class DigestSplitTest {
    private fun line(index: Int, start: Long) = TranscriptLineEntity(
        id = index + 1L, segmentId = 1, startAt = start, endAt = start + 500, text = "合成原话", speakerTag = null,
    )

    @Test fun fewerThanFortyLinesCannotSplit() {
        assertNull(splitForRetry((0 until 39).map { line(it, it * 1000L) }))
    }

    @Test fun longestMiddlePauseUsesPrefixEndAndBreaksTiesNearCenter() {
        val lines = (0 until 40).map { i ->
            line(i, i * 1000L + (if (i >= 12) 10_000 else 0) + (if (i >= 24) 2000 else 0) +
                (if (i >= 28) 2000 else 0) + (if (i >= 36) 100_000 else 0))
        }.toMutableList()
        // 第 4 句覆盖到第 21 句开始，不能把第 12 句后的表面停顿当作真正停顿。
        lines[3] = lines[3].copy(endAt = lines[20].startAt)
        val (first, second) = splitForRetry(lines)!!
        // 第 24、28 句后的停顿并列，选靠中间的 24；第 36 句后的大停顿不在候选区间。
        assertEquals(lines.take(24), first)
        assertEquals(lines.drop(24), second)
    }
}
