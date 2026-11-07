package app.resound.digest

import android.app.Application
import androidx.room.Room
import app.resound.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class DigestVoiceprintGateTest {
    private lateinit var db: CaptureDatabase
    private lateinit var runner: DigestRunner
    private var localReady = false
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        val client = LlmClient()
        val settings = AndroidDigestSettings(context, client)
        settings.setPauseMinutes(5)
        settings.setMaxWaitMinutes(30)
        runner = DigestRunner(context, db.memory(), client, settings, { "" }, { true },
            now = { 10_000_000L }, voiceprintReady = { localReady })
    }
    @After fun close() = db.close()
    private suspend fun add(id: Long, remote: Boolean = false, done: Boolean = false, source: String = "mic", start: Long = id * 1000): TranscriptLineEntity {
        db.capture().segment(SegmentEntity(id = id, startAt = start, endAt = start + 1000,
            sourceId = source, path = "/test-$id", sizeBytes = 1, status = "Done", separateSpeakers = true,
            remoteSpeakerScope = if (remote) "test-scope" else null, voiceprintProcessedAt = if (done) 1L else null))
        val line = TranscriptLineEntity(id = id, segmentId = id, startAt = start, endAt = start + 1000, text = "测试原话", speakerTag = null)
        db.transcribe().insertLines(listOf(line))
        return line
    }
    @Test fun oldLocalAndCompletedRemoteCanOrganizeWithoutInventingIdentity() = runBlocking {
        val lines = listOf(add(1), add(2, remote = true, done = true))
        assertTrue(runner.closed(lines, true))
        assertTrue(db.memory().refreshLines(listOf(1, 2)).all { it.speakerName == null && it.digestedAt == null })
        assertNull(db.transcribe().segment(1).voiceprintProcessedAt)
    }
    @Test fun localSamplesStillRequireLocalCompletion() = runBlocking {
        localReady = true
        val line = add(1)
        assertFalse(runner.closed(listOf(line), true))
        db.voiceprint().processed(1, 1)
        assertTrue(runner.closed(listOf(line), true))
    }
    @Test fun unfinishedRemoteStillWaitsWithoutLocalSamples() = runBlocking {
        assertFalse(runner.closed(listOf(add(1, remote = true)), true))
    }
    @Test fun importsStillWaitForFeatureCachingWithoutLocalSamples() = runBlocking {
        assertFalse(runner.closed(listOf(add(1, source = "import")), true))
    }
    @Test fun settledPrefixUsesTheSameSourceAwareGate() = runBlocking {
        val lines = listOf(add(1, start = 1000), add(2, remote = true, done = true, start = 400000), add(3, remote = true, done = true, start = 600000))
        assertFalse(runner.settledPrefix(lines).isEmpty())
        localReady = true
        assertTrue(runner.settledPrefix(lines).isEmpty())
    }
}
