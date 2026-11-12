package app.resound.rules

import app.resound.push.MemoryPaths
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/** 原 Subscription042Test 的纯路径用例随规则迁移。 */
class MemoryPaths154Test {
    private val date = LocalDate(2026, 10, 24)
    @Test fun filenamesUseEnglishWeekdayAndIsoWeekYear() {
        assertEquals("daily/2026-10/2026-10-24-Sat_daily-record.md", MemoryPaths.daily(date))
        assertEquals("transcripts/2026-10/2026-10-24-Sat_transcript.md", MemoryPaths.transcript(date))
        assertEquals("weekly/2020-W53_1228-0103_weekly-review.md", MemoryPaths.week(LocalDate(2021, 1, 1)))
        assertEquals("weekly/2026-W44_1026-1101_weekly-review.md", MemoryPaths.week(LocalDate(2026, 11, 1)))
    }
}
