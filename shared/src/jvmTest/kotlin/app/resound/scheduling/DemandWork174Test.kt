package app.resound.scheduling

import org.junit.Assert.*
import org.junit.Test

class DemandWork174Test {
    @Test fun embeddingsOnlyWaitOnTheMissingPrerequisite() {
        assertEquals(DemandWorkPolicy.Embedding.None, DemandWorkPolicy.embedding(false, false, false))
        assertEquals(DemandWorkPolicy.Embedding.DownloadModel, DemandWorkPolicy.embedding(true, false, true))
        assertEquals(DemandWorkPolicy.Embedding.WaitForPower, DemandWorkPolicy.embedding(true, true, false))
        assertEquals(DemandWorkPolicy.Embedding.Compute, DemandWorkPolicy.embedding(true, true, true))
    }
    @Test fun privacyOnlyNeedsWorkForNonemptyQueue() {
        assertFalse(DemandWorkPolicy.privacy(0))
        assertTrue(DemandWorkPolicy.privacy(1))
    }
    @Test fun stoppingCancelsWatchdogAndEnabledPauseKeepsIt() {
        assertFalse(DemandWorkPolicy.watchdog(false))
        assertTrue(DemandWorkPolicy.watchdog(true))
    }
}
