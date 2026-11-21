package app.resound.update

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class UpdatePopupRuleTest {
    private val day = 86_400_000L

    @Test fun sameVersionStaysQuietInsideTheWindow() {
        assertFalse(UpdatePopupRule.autoOpen(80, 80, 0, day - 1))
        assertTrue(UpdatePopupRule.autoOpen(80, 80, 0, day))
    }

    @Test fun newerVersionInterruptsSnoozeAndRewoundClockReopens() {
        assertTrue(UpdatePopupRule.autoOpen(81, 80, 0, 1000))
        assertTrue(UpdatePopupRule.autoOpen(80, 80, 10_000, 1000))
    }
}
