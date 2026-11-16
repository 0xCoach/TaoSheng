package app.resound.screentime

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackCollectionPolicyTest {
    @Test fun disabledNeverStartsAndReenableResamplesCurrentPlayback() {
        var collecting = false
        var samples = 0
        var releases = 0
        fun update(on: Boolean) = updatePlaybackCollection(on, collecting,
            start = { collecting = true; samples++ },
            stop = { collecting = false; releases++ })
        update(false)
        assertEquals(0, samples)
        update(true)
        update(true)
        assertEquals(1, samples)
        update(false)
        update(false)
        assertEquals(1, releases)
        update(true)
        assertEquals(2, samples)
    }
}
