package app.resound.digest

import org.junit.Assert.assertEquals
import org.junit.Test

class AppPlaybackPromptTest {
    @Test fun closedGatesSkipQuery() {
        var n = 0
        assertEquals(emptyList<String>(), playbackRecordsForPrompt(false, true) { n++; listOf("x") })
        assertEquals(emptyList<String>(), playbackRecordsForPrompt(true, false) { n++; listOf("x") })
        assertEquals(emptyList<String>(), playbackRecordsForPrompt(false, false) { n++; listOf("x") })
        assertEquals(0, n)
        assertEquals(listOf("x"), playbackRecordsForPrompt(true, true) { n++; listOf("x") })
        assertEquals(1, n)
    }
}
