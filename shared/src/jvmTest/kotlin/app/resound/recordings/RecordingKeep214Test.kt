package app.resound.recordings

import app.resound.push.*
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*

class RecordingKeep214Test {
    private val sub = PushSubscription(audio = AudioPush.Speech)
    private val row = KeepRecording(1, "2026-11-01", 100, 1000, speech = true)
    @Test fun retentionUsesScopeAndAnyVerifiedDestination() {
        val channel = RecordingChannel(1, sub, "2026-10-31", emptySet())
        val rows = listOf(row, row.copy(id=2, media=true), row.copy(id=3, speech=false), row.copy(id=4, day="2026-10-01"))
        assertTrue(RecordingKeepRules.held(rows, emptyList(), "2026-11-20").isEmpty())
        assertEquals(setOf(1L), RecordingKeepRules.held(rows, listOf(channel), "2026-11-20"))
        assertTrue(RecordingKeepRules.held(rows, listOf(channel, channel.copy(id=2, uploaded=setOf(1))), "2026-11-20").isEmpty())
        assertTrue(RecordingKeepRules.verified(true, null)); assertFalse(RecordingKeepRules.verified(true, 0))
    }
    @Test fun summaryCountsExpiredDaysAndSevenDaysAfterExpiry() {
        val rows = listOf(row, row.copy(id=2), row.copy(id=3, day="2026-11-02", expiresAt=Long.MAX_VALUE))
        assertEquals(HeldSummary(2, 1, 200, "2026-11-01", true), RecordingKeepRules.summary(rows, setOf(1,2,3), 1000+7*86400000L))
        assertFalse(RecordingKeepRules.summary(rows, setOf(1), 7*86400000L).remind)
    }
    @Test fun defaultsMigrationSourcesAndTemplates() {
        val legacy = JsonObject(SubscriptionJson.encode(sub).filterKeys { it != "audioText" })
        assertEquals(AudioText.PerSegment, SubscriptionJson.read(legacy).audioText)
        assertEquals(AudioText.None, SubscriptionJson.read(JsonObject(legacy + ("audioWithText" to JsonPrimitive(false)))).audioText)
        assertEquals(AudioText.Daily, sub.audioText)
        assertFalse(RecordingKeepRules.eligible(row.copy(imported=true), sub, null, "2026-11-20"))
        assertTrue(RecordingKeepRules.eligible(row.copy(imported=true,call=true), sub, null, "2026-11-20"))
        assertFalse(RecordingKeepRules.eligible(row.copy(call=true), sub.copy(audioCalls=false), null, "2026-11-20"))
        assertTrue(PushTemplates.keepRecordings().audioChargingOnly); assertNull(PushTemplates.keepRecordings().backfillDays)
        assertEquals(AudioPush.Off, PushTemplates.textOnly().audio)
    }
    @Test fun privacyNamesAndQuotaAreStrict() {
        val path = "录音/2026-11-01/2026-11-01 08.30 内容.ogg"
        assertEquals(listOf(path, path.removeSuffix(".ogg")+".txt"), RecordingPrivacyRules.files(path))
        assertTrue(RecordingPrivacyRules.files("录音/../x.ogg").isEmpty())
        assertTrue(RecordingPrivacyRules.files("录音/2026-11-01/*.ogg").isEmpty())
        assertNull(RecordingKeepRules.quotaHint(false,900000000)); assertNull(RecordingKeepRules.quotaHint(true,799999999))
        assertNotNull(RecordingKeepRules.quotaHint(true,800000000))
    }
}
