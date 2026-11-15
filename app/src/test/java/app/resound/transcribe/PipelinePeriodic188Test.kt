package app.resound.transcribe

import org.junit.Assert.*
import org.junit.Test

class PipelinePeriodic188Test {
    @Test fun onlyPeriodicLockCollisionSucceeds() {
        assertEquals(androidx.work.ListenableWorker.Result.success(), TranscribeWork.busyResult(TranscribeWork.MODE_DIGEST, false))
        assertTrue(TranscribeWork.isPeriodic(TranscribeWork.MODE_TRANSCRIBE, false))
        assertEquals(androidx.work.ListenableWorker.Result.success(), TranscribeWork.busyResult(TranscribeWork.MODE_TIMED, true))
        assertEquals(androidx.work.ListenableWorker.Result.retry(), TranscribeWork.busyResult(TranscribeWork.MODE_TIMED, false))
        assertFalse(TranscribeWork.isPeriodic(TranscribeWork.MODE_REALTIME, false))
        assertFalse(TranscribeWork.isPeriodic(TranscribeWork.MODE_FULL, false))
    }
}
