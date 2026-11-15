package app.resound.diagnostics

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class FailureNotesTest {
    @After fun reset() = FailureNotes.resetForTest()

    @Test fun sameFailureWritesOncePerIntervalThenReportsSuppressedCount() {
        var now = 1_000L
        val written = mutableListOf<String>()
        FailureNotes.install(write = { written += it }, now = { now })
        repeat(50) { FailureNotes.record("capture/X.kt:1", IllegalStateException()) }
        FailureNotes.record("push/Y.kt:2", IllegalStateException())
        assertEquals(listOf("capture/X.kt:1 IllegalStateException", "push/Y.kt:2 IllegalStateException"), written)
        now += FailureNotes.PERSIST_INTERVAL_MILLIS
        FailureNotes.record("capture/X.kt:1", IllegalStateException())
        assertEquals("capture/X.kt:1 IllegalStateException（期间另有 49 次）", written.last())
        assertEquals(52, FailureNotes.dump().lines().size)
    }
}
