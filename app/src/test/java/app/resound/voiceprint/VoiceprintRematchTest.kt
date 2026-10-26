package app.resound.voiceprint

import android.app.Application
import androidx.room.Room
import app.resound.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** 标注后只重配录音还在的段，且只重跑声纹、连续标注合并。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class VoiceprintRematchTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scheduled = Channel<Unit>(Channel.UNLIMITED)
    private val audio = mutableMapOf<String, ShortArray>()
    private val files = mutableListOf<File>()
    private val repository = LocalVoiceprintRepository(db, scope, { audio.getValue(it.path) },
        { SpeakerEmbedding { FloatArray(192).apply { this[0] = 1f } } }, { scheduled.trySend(Unit); Unit }, { 100_000_000L })

    @After fun close() = runBlocking {
        scope.coroutineContext[Job]!!.cancelAndJoin(); db.close(); files.forEach { it.delete() }
    }

    private suspend fun segment(start: Long): Pair<SegmentEntity, List<TranscriptLineEntity>> {
        val file = File.createTempFile("rematch-", ".ogg", context.cacheDir).also { files += it }
        audio[file.path] = ShortArray(5 * 16000) { 1000 }
        db.capture().segment(SegmentEntity(startAt = start, endAt = start + 5000, sourceId = "mic", path = file.path,
            sizeBytes = 1, status = "Done", completedAt = 1))
        val segment = db.transcribe().observeSegments().first().first { it.path == file.path }
        db.transcribe().insertLines(listOf(
            TranscriptLineEntity(segmentId = segment.id, startAt = start, endAt = start + 2500, text = "第一句", speakerTag = "0"),
            TranscriptLineEntity(segmentId = segment.id, startAt = start + 2500, endAt = start + 5000, text = "第二句", speakerTag = "0")))
        return segment to db.voiceprint().lines(segment.id)
    }

    @Test fun markMeOnlyRequeuesSegmentsWhoseAudioIsStillKept() = runBlocking {
        repeat(3) { repository.enrollPcm(ShortArray(160_000) { 1000 }).getOrThrow(); scheduled.receive() }
        val (kept, lines) = segment(1000)
        val (expired, _) = segment(20_000)
        assertFalse(repository.run())
        assertTrue(db.voiceprint().pending().isEmpty())
        File(expired.path).delete()

        repository.markMe(lines[0].id); withTimeout(5000) { scheduled.receive() }

        assertEquals(listOf(kept.id), db.voiceprint().pending().map { it.id })
    }

    @Test fun burstOfRequestsRunsVoiceprintOnceAndFallsBackOnlyWhenItKeepsFailing() = runBlocking {
        val runs = AtomicInteger()
        val fallbacks = AtomicInteger()
        val ok = VoiceprintRerun(scope, { runs.incrementAndGet(); false }, { fallbacks.incrementAndGet() }, settleMillis = 100)
        repeat(5) { ok.request() }
        delay(600)
        assertEquals(1, runs.get())
        assertEquals(0, fallbacks.get())

        val failing = AtomicInteger()
        val bad = VoiceprintRerun(scope, { failing.incrementAndGet(); true }, { fallbacks.incrementAndGet() }, settleMillis = 50)
        bad.request()
        delay(500)
        assertEquals(3, failing.get())
        assertEquals(1, fallbacks.get())
    }
}
