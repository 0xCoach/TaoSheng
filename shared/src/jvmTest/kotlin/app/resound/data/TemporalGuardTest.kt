package app.resound.data

import org.junit.Assert.*
import org.junit.Test

class TemporalGuardTest {
    private val said = 1_700_000_000_000L // 2023-11-14

    @Test fun plausibleDueDatesPass() {
        // 明天、一周内都保留
        assertEquals(said + 86_400_000, TemporalGuard.sanitizeDue(said + 86_400_000, said))
        assertEquals(said + 7 * 86_400_000L, TemporalGuard.sanitizeDue(said + 7 * 86_400_000L, said))
        // 一天内的"昨天就该交"合法
        assertEquals(said - 20 * 3600_000L, TemporalGuard.sanitizeDue(said - 20 * 3600_000L, said))
    }

    @Test fun impossibleDueDatesAreDroppedButOnlyWhenRecordedKnown() {
        // 半年前/三年后的"期限"基本是模型写错日期字面量
        assertNull(TemporalGuard.sanitizeDue(said - 200L * 86_400_000, said))
        assertNull(TemporalGuard.sanitizeDue(said + 900L * 86_400_000, said))
        assertTrue(TemporalGuard.dueViolated(said - 200L * 86_400_000, said))
        assertFalse(TemporalGuard.dueViolated(said + 30L * 86_400_000, said))
        // 手打条目没有录音时间，不做判断原样放行
        assertEquals(said - 400L * 86_400_000, TemporalGuard.sanitizeDue(said - 400L * 86_400_000, null))
        assertNull(TemporalGuard.sanitizeDue(null, said))
    }
}
