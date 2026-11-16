package app.resound.devices

import org.junit.Test
import org.junit.Assert.*

class DeviceExperienceTest {
    @Test fun connectedStoppedIsWaitingAndTransitionsDoNotSpamHistory() {
        val waiting = deviceActivity(DeviceLinkPhase.Connected,DeviceRecorderPhase.Stopped,true,false,false)
        assertEquals(DeviceActivity.WaitingForRecording,waiting)
        val first = DeviceExperience().advance(waiting,1000)
        assertEquals(first,first.advance(waiting,2000))
        val offline = first.advance(DeviceActivity.WaitingReconnect,3000)
        val restored = offline.advance(DeviceActivity.ReceivingAudio,4000)
        assertEquals(3000L,restored.lastDisconnectedAt)
        assertEquals(4000L,restored.lastConnectedAt)
        assertEquals(3,restored.events.size)
        assertEquals(DeviceActivity.ConnectedIdle,deviceActivity(DeviceLinkPhase.Connected,DeviceRecorderPhase.Recording,false,false,false))
    }
    @Test fun cacheCannotResumeUncommittedOrMissingBytesAndReadingsExpire() {
        assertEquals(100L,deviceDurablePrefix(150,100,200,true))
        assertEquals(80L,deviceDurablePrefix(80,100,200,true))
        assertEquals(0L,deviceDurablePrefix(100,100,200,false))
        assertEquals(DeviceReadingFreshness.Stale,deviceReadingFreshness(1000,122000))
        assertEquals(DeviceReadingFreshness.Unknown,deviceReadingFreshness(200000,1000))
    }
    @Test fun sourceBytesAreCountedOnceAcrossRangesAndCachedTimeDoesNotInventSpeed() {
        val meter = DeviceSyncMeter(listOf(DeviceSyncTask("a","file",100000,2000),DeviceSyncTask("b","file",100000,3000)))
        meter.begin("a","test",0,0)
        val downloading = meter.bytes(50000,2000)
        assertEquals(2000L,downloading.downloadEtaMillis)
        assertEquals(100000L,downloading.totalBytes)
        assertEquals(5000L,downloading.remainingAudioMillis)
        meter.bytes(100000,3000);meter.completed("a",3000)
        val cached = meter.begin("b","test",100000,4000)
        assertEquals(100000L,cached.receivedBytes)
        assertEquals(1,cached.sourceFiles)
        assertTrue(cached.fromCache)
        assertEquals(3000L,cached.remainingAudioMillis)
        assertNull(meter.phase(DeviceSyncPhase.Importing,4000).downloadEtaMillis)
    }
}
