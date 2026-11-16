package app.resound.diagnostics

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class FailureNotes190Test {
    @Test fun maintenanceStageUsesExistingProblemWriterAndThrottling() {
        var now = 1000L
        val written = mutableListOf<String>()
        FailureNotes.install(now={now},write={written += it})
        try {
            repeat(3) { FailureNotes.note("devices/maintenance-test:Completed") }
            assertEquals(1,written.size)
            now += FailureNotes.PERSIST_INTERVAL_MILLIS
            FailureNotes.note("devices/maintenance-test:Completed")
            assertEquals(2,written.size)
            assertTrue(written.last().contains("2"))
        } finally { FailureNotes.install { } }
    }
    @Test fun recordsOnlySiteAndTypeAndBoundsMemory() {
        FailureNotes.install { }
        repeat(205) { FailureNotes.record("test-site-$it", IllegalStateException("private-key-content")) }
        val text = FailureNotes.dump()
        assertTrue(text.lines().size <= 200)
        assertTrue(text.contains("test-site-204 IllegalStateException"))
        assertFalse(text.contains("private-key-content"))
        assertFalse(text.contains("test-site-0 "))
    }

    @Test fun brokenDiagnosticWriterDoesNotBreakBusinessAndKeepsFallback() {
        FailureNotes.install { error("disk unavailable") }
        try {
            FailureNotes.record("writer-fallback", IllegalArgumentException("private"))
            assertTrue(FailureNotes.dump().contains("writer-fallback IllegalArgumentException"))
        } finally { FailureNotes.install { } }
    }
}
