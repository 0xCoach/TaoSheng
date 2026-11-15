package app.resound.update

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class UpdatePopupRuleTest {
    private val hour = 60 * 60_000L
    @Test fun laterSilencesSameVersionForOneDay() {
        assertFalse(UpdatePopupRule.autoOpen(80, 80, 0, 23 * hour))
        assertTrue(UpdatePopupRule.autoOpen(80, 80, 0, 24 * hour))
    }
    @Test fun newerVersionOpensImmediately() {
        assertTrue(UpdatePopupRule.autoOpen(81, 80, 0, hour))
    }
    @Test fun clockMovedBackDoesNotSilenceForever() {
        assertTrue(UpdatePopupRule.autoOpen(80, 80, 10 * hour, hour))
    }
}
