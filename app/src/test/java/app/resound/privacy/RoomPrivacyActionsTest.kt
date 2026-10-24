package app.resound.privacy

import android.app.Application
import androidx.room.Room
import app.resound.data.*
import app.resound.transcribe.*
import app.resound.voiceprint.VoiceprintFeatureCache
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class RoomPrivacyActionsTest {
    private lateinit var db: CaptureDatabase
    private lateinit var directory: File
    private lateinit var privacy: RoomPrivacyActions
    private val now = 2_000_000L
    private var discarded = 0
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        directory = File(context.cacheDir, "privacy-${System.nanoTime()}").apply { mkdirs() }
        privacy = RoomPrivacyActions(db, discardRecording = { discarded++ }, now = { now })
    }
    @After fun close() { db.close(); directory.deleteRecursively() }
    private suspend fun segment(id: Long, start: Long, status: String = "Done") {
        val file = File(directory, "$id.ogg").apply { writeText("test") }
        db.capture().segment(SegmentEntity(id, start, start + 1000, "mic", file.path, file.length(), status))
    }
    private fun eventRow(id: Long) = MemoryEventEntity(id, 1, now, "其他", "事件", "摘要", "[]", "[]", null, null, null, false, createdAt = now, model = "test")
    private suspend fun event(id: Long) { db.memory().insertEvent(eventRow(id)) }
    private suspend fun line(id: Long, segment: Long, event: Long?) {
        db.transcribe().insertLines(listOf(TranscriptLineEntity(id, segment, now, now + 1, "原话", "0", eventId = event)))
    }
    private fun item(id: Long, event: Long, line: Long?) = MemoryItemEntity(id, event, "Memo", null, "条目", null, null, null, line, "Pending", now)
    private fun count(table: String): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    @Test fun recentKeepsCrossingEventAndDeletesSourceItemsAndPendingAudio() = runBlocking {
        event(1); segment(1, now - 600_001); segment(2, now - 600_000); segment(3, now - 1, "Pending")
        line(1, 1, 1); line(2, 2, 1)
        db.memory().insertItems(listOf(item(1, 1, 1), item(2, 1, 2), item(3, 1, null)))
        assertEquals(ForgetResult(2, 1, 0, 1), privacy.deleteRecent(10))
        assertEquals(1, discarded)
        assertEquals(listOf(1L), db.transcribe().observeSegments().first().map { it.id })
        assertEquals(listOf(1L), db.voiceprint().lines(1).map { it.id })
        assertEquals(1, count("memory_events")); assertEquals(2, count("memory_items"))
        assertTrue(File(directory, "1.ogg").exists()); assertFalse(File(directory, "2.ogg").exists())
        assertFalse(File(directory, "3.ogg").exists()); assertTrue(db.transcribe().candidates(now).isEmpty())
    }
    @Test fun recentDeletesWholeEventAndItsUnlinkedItems() = runBlocking {
        event(1); segment(1, now - 1); line(1, 1, 1)
        db.memory().insertItems(listOf(item(1, 1, 1), item(2, 1, null)))
        assertEquals(ForgetResult(1, 1, 1, 2), privacy.deleteRecent(10))
        assertEquals(0, count("memory_events")); assertEquals(0, count("memory_items"))
    }
    @Test fun forgetEventRemovesAudioSamplesExclusionsAndFeaturesButKeepsEnrollment() = runBlocking {
        event(1); segment(1, 1); line(1, 1, 1)
        db.memory().insertItems(listOf(item(1, 1, 1)))
        db.voiceprint().saveSample(VoiceprintSampleEntity(source = "line", sourceLineId = 1, segmentId = 1, groupKey = "tag:0", vector = byteArrayOf(1), durationMillis = 1000, createdAt = now))
        db.voiceprint().saveSample(VoiceprintSampleEntity(source = "enroll", sourceLineId = null, segmentId = null, groupKey = null, vector = byteArrayOf(1), durationMillis = 1000, createdAt = now))
        db.voiceprint().exclude(VoiceprintExclusionEntity(1, "tag:0", now))
        val cache = VoiceprintFeatureCache(File(directory, "features"))
        val key = cache.key(db.transcribe().segment(1), db.voiceprint().lines(1))
        cache.write(key, VoiceprintFeatureCache.Feature(null, 1000))
        val actions = RoomPrivacyActions(db, removeFeatures = { segment, lines ->
            assertEquals(0, count("segments")) // 文件清理发生在事务提交之后。
            cache.remove(cache.key(segment, lines))
        })
        assertEquals(ForgetResult(1, 1, 1, 1), actions.forgetEvent(1))
        assertEquals(0, count("transcript_lines")); assertEquals(0, count("voiceprint_exclusions"))
        assertEquals("enroll", db.voiceprint().allSamples().single().source)
        assertNull(cache.read(key)); assertFalse(File(directory, "1.ogg").exists())
        assertEquals(ForgetResult(0, 0, 0, 0), actions.forgetEvent(1))
        // 同一录音分属两件事时，不顺带抹掉另一件事的文字。
        event(2); event(3); segment(2, 2); line(2, 2, 2); line(3, 2, 3)
        db.memory().insertItems(listOf(item(2, 2, 2), item(3, 3, 3)))
        assertEquals(ForgetResult(0, 1, 1, 1), privacy.forgetEvent(2))
        assertEquals(3L, db.voiceprint().lines(2).single().id)
        assertEquals(1, count("memory_events")); assertEquals(1, count("memory_items"))
        assertFalse(File(directory, "2.ogg").exists())
    }
    @Test fun forgetSegmentRemovesEmptyEventsAndPreservesOtherEventLines() = runBlocking {
        event(1); event(2); segment(1, 1); segment(2, 2)
        line(1, 1, 1); line(2, 1, 2); line(3, 2, 2)
        db.memory().insertItems(listOf(item(1, 1, null), item(2, 2, 2), item(3, 2, 3)))
        assertEquals(ForgetResult(1, 2, 1, 2), privacy.forgetSegment(1))
        assertEquals(1, count("memory_events")); assertEquals(1, count("memory_items"))
        assertEquals(3L, db.voiceprint().lines(2).single().id)
    }
    @Test fun deletedQueuedAndInflightSegmentsCannotBeTranscribedOrRecreatedByOldResults() = runBlocking {
        segment(1, 1, "Pending"); segment(2, 2, "Pending")
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var calls = 0
        val runner = TranscribeRunner(db.transcribe(), object : AsrProvider {
            override suspend fun recognize(audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit): AsrResult {
                calls++; beforeAudio(); entered.complete(Unit); release.await()
                return AsrResult(emptyList(), 1000, null)
            }
        }, { "test" }, { _, _ -> }, deferAudioCleanup = true)
        val work = async { runner.run() }
        entered.await()
        privacy.forgetSegment(1); privacy.forgetSegment(2)
        release.complete(Unit); work.await()
        assertEquals(1, calls); assertEquals(0, count("segments")); assertEquals(0, count("transcript_lines"))
        db.transcribe().complete(SegmentEntity(1, 1, 2, "mic", "missing", 0), listOf(TranscriptLineEntity(1, 1, 1, 2, "旧结果", null)))
        db.memory().saveDigest(listOf(eventRow(1) to listOf(1)), { listOf(item(1, it.single(), 1)) }, listOf(1), now)
        assertEquals(0, count("memory_events")); assertEquals(0, count("memory_items"))
    }
}
