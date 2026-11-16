package app.resound.ui.screentime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncludeAppPlaybackVisibilityTest {
    @Test fun hiddenWhenBothOff() {
        assertFalse(showIncludeAppPlaybackSwitch(screenTimeOn = false, playbackTitleOn = false))
    }

    @Test fun shownWhenEitherOn() {
        assertTrue(showIncludeAppPlaybackSwitch(screenTimeOn = true, playbackTitleOn = false))
        assertTrue(showIncludeAppPlaybackSwitch(screenTimeOn = false, playbackTitleOn = true))
    }
}
