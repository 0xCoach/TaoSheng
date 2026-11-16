package app.resound.devices

import org.junit.Assert.*
import org.junit.Test

class DeviceArchivePrivacyTest {
    @Test fun overlappingDayErasesWholeCrossingArchiveButKeepsOtherDays() {
        assertTrue(deviceArchiveOverlaps(90,30,100,199))
        assertTrue(deviceArchiveOverlaps(199,10,100,199))
        assertFalse(deviceArchiveOverlaps(80,20,100,199))
        assertFalse(deviceArchiveOverlaps(200,20,100,199))
    }
    @Test fun unknownAndOverflowDatesCannotLeavePrivateAudioOutsideSelection() {
        assertTrue(deviceArchiveOverlaps(null,100,100,199))
        assertTrue(deviceArchiveOverlaps(Long.MAX_VALUE-10,100,Long.MAX_VALUE-5,Long.MAX_VALUE))
        assertTrue(deviceArchiveOverlaps(100,0,100,100))
    }
    @Test fun persistedFenceBlocksOldCopiesWhileAllowingNewRecordingsAfterDeletion() {
        val fence = DeviceArchiveErasure(Long.MIN_VALUE,1000,setOf("existing-future-clock-file"))
        assertTrue(fence.excludes(null,999,10))
        assertFalse(fence.excludes(null,1001,10))
        assertTrue(fence.excludes("existing-future-clock-file",2000,10))
        assertFalse(DeviceArchiveErasure(2000,1000).excludes(null,3000,10))
    }
}
