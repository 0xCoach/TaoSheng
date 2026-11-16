package app.resound.devices

import org.junit.Test
import org.junit.Assert.*
import kotlinx.serialization.json.Json

class DeviceRetentionTest {
    private val day = 86_400_000L
    private val settings = DeviceRetentionSettings(true,1,7)
    private val backup = DeviceArchiveBackupReceipt("remote","object","manifest","b".repeat(64),300,3*day)
    private fun record() = DeviceArchiveRecord("c".repeat(64),"driver","stable","file",day,1000,2*day,200,"a".repeat(64),
        300,"b".repeat(64),"Ogg",16000,2*day,"install",imported=true,backup=backup)
    private fun block(row: DeviceArchiveRecord, policy: DeviceRetentionSettings = settings, now: Long = 40*day,
                      stopped: Boolean = true, battery: Int? = 80) =
        deviceRetentionBlock(row,policy,now,"stable","install","remote",stopped,battery)
    @Test fun fullArchiveImportAndIndependentReadbackAreAllRequired() {
        assertEquals(DeviceRetentionBlock.None,block(record()))
        assertEquals(DeviceRetentionBlock.IndependentBackupRequired,block(record().copy(backup=null)))
        assertEquals(DeviceRetentionBlock.ImportPending,block(record().copy(imported=false)))
        assertEquals(DeviceRetentionBlock.IncompleteArchive,block(record().copy(decodedSamples=0)))
        assertEquals(DeviceRetentionBlock.IndependentBackupRequired,block(record().copy(backup=backup.copy(archiveSha256="d".repeat(64)))))
        assertFalse(deviceImportCoverageComplete(1000,listOf(0L to 400L,500L to 1000L)))
        assertTrue(deviceImportCoverageComplete(1000,listOf(0L to 400L,400L to 1000L)))
    }
    @Test fun historyProtectionUnknownStateAndPendingIntentFailClosed() {
        assertEquals(DeviceRetentionBlock.Disabled,block(record(),settings.copy(enabled=false)))
        assertEquals(DeviceRetentionBlock.HistoricalNotAdopted,block(record(),settings.copy(enabledAtMillis=3*day)))
        assertEquals(DeviceRetentionBlock.Protected,block(record().copy(protected=true)))
        assertEquals(DeviceRetentionBlock.RecordingOrUnknown,block(record(),stopped=false))
        assertEquals(DeviceRetentionBlock.LowBatteryOrUnknown,block(record(),battery=null))
        assertEquals(DeviceRetentionBlock.ResultNeedsReconciliation,block(record().copy(deletion=DeviceDeleteState.Intent)))
        val restored = DeviceArchiveOptions(true,settings,"old-phone").forInstallation("new-phone")
        assertFalse(restored.retention.enabled);assertFalse(restored.archiveEnabled)
    }
    @Test fun retentionAndBackupObservationAreIndependentAndClockReversalIsNotEligibility() {
        assertEquals(DeviceRetentionBlock.RetentionPeriod,block(record(),now=5*day))
        assertEquals(DeviceRetentionBlock.ObservationPeriod,block(record().copy(backup=backup.copy(verifiedAtMillis=40*day)),now=40*day+1000))
        assertEquals(DeviceRetentionBlock.ObservationPeriod,block(record().copy(backup=backup.copy(verifiedAtMillis=41*day))))
        assertEquals(DeviceRetentionBlock.DifferentInstallation,block(record().copy(installation="other")))
    }
    @Test fun verifiedBackupCanReleaseNewRecordingImmediatelyWithoutTranscript() {
        val now = 2*day
        val fresh = record().copy(startedAtMillis=now-1000,backup=backup.copy(verifiedAtMillis=now))
        assertEquals(DeviceRetentionBlock.None,block(fresh,settings.copy(cleanupDelayHours=0),now))
        assertEquals(DeviceRetentionBlock.RetentionPeriod,block(fresh,settings,now))
    }
    @Test fun selectedDelayStartsAtBackupVerificationAndEndsAtItsBoundary() {
        val verified = 40*day
        val row = record().copy(backup=backup.copy(verifiedAtMillis=verified))
        val policy = settings.copy(cleanupDelayHours=72)
        assertEquals(DeviceRetentionBlock.ObservationPeriod,block(row,policy,verified+3*day-1))
        assertEquals(DeviceRetentionBlock.None,block(row,policy,verified+3*day))
        assertEquals(DeviceRetentionBlock.ObservationPeriod,block(row,settings.copy(cleanupDelayHours=0),verified-1))
    }
    @Test fun immediateModeRetainsCompleteBackupProtectionAndStoppedStateGuards() {
        val policy = settings.copy(cleanupDelayHours=0)
        assertEquals(DeviceRetentionBlock.IndependentBackupRequired,block(record().copy(backup=null),policy))
        assertEquals(DeviceRetentionBlock.Protected,block(record().copy(protected=true),policy))
        assertEquals(DeviceRetentionBlock.RecordingOrUnknown,block(record(),policy,stopped=false))
    }
    @Test fun oldSerializedPolicyIsPreservedAndNewDisabledOptionsOfferImmediateMode() {
        val old = Json.decodeFromString<DeviceArchiveOptions>("""{"archiveEnabled":true,"retention":{"enabled":true,"enabledAtMillis":1,"keepDays":14,"observationHours":48},"installation":"install"}""")
        assertNull(old.retention.cleanupDelayHours)
        assertEquals(14,old.retention.keepDays);assertEquals(48,old.retention.observationHours)
        val fresh = DeviceArchiveOptions()
        assertFalse(fresh.retention.enabled);assertEquals(0,fresh.retention.cleanupDelayHours)
        val reset = old.forInstallation("another-phone")
        assertFalse(reset.retention.enabled);assertTrue(reset.retention.adoptedHistory.isEmpty())
    }
}
