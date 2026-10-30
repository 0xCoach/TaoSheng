package app.resound.transcribe

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.CaptureDatabase
import app.resound.data.SegmentEntity
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class ImportAudioCleanup078Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: CaptureDatabase
    private lateinit var root: File
    private val now = System.currentTimeMillis()
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        root = File(context.cacheDir, "cleanup078").apply { mkdirs() }
    }
    @After fun close() { db.close(); root.deleteRecursively() }
    private suspend fun row(id: Long, status: String, age: Long = IMPORT_RETENTION, first: Long = now): SegmentEntity {
        val file = File(root, "$id.ogg").apply { writeText("test-audio"); check(setLastModified(now - age)) }
        val row = SegmentEntity(id, 0, 1000, "import", file.path, file.length(), status,
            error = "原失败原因$id", attempts = if (id == 2L) 5 else 1,
            firstFailedAt = first, nextRetryAt = if (status == "Failed" && id != 2L) now + 60_000 else null)
        db.capture().segment(row)
        return row
    }
    @Test fun startupAbandonsExpiredFailuresWithOrWithoutRetryAndKeepsTheirReasons() = runBlocking {
        val rows = listOf(row(1, "Failed"), row(2, "Failed"), row(3, "Failed", first = now - IMPORT_RETENTION - 1),
            row(4, "Quiet"), row(5, "Abandoned"), row(6, "Pending"))
        val runner = TranscribeRunner(db.transcribe(), object : AsrProvider {
            override suspend fun recognize(audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit): AsrResult =
                error("启动清理不能上传音频")
        }, { "" }, { _, _ -> }, now = { now }, ready = { false }, networkAllowed = { false })
        runner.recoverOnStartup()
        for (before in rows) {
            val after = db.transcribe().segment(before.id)
            assertFalse(File(before.path).exists()); assertEquals("Abandoned", after.status)
            assertEquals(before.error, after.error); assertEquals(5, after.attempts)
            assertNull(after.nextRetryAt); assertEquals(now, after.completedAt)
        }
        assertTrue(db.transcribe().candidates(now + IMPORT_RETENTION).isEmpty())
    }
    @Test fun freshRetryAndActiveReadersAndCompletedImportsAreProtected() = runBlocking {
        val recent = row(1, "Failed", age = IMPORT_RETENTION - 1)
        val reading = row(2, "Failed")
        val rows = listOf(recent, reading, row(3, "Running"), row(4, "Done"), row(5, "Empty"),
            row(6, "SkippedPlayback"), row(7, "SkippedEnv"))
        cleanupImportedAudio(db.transcribe(), now, reading = setOf(reading.id))
        for (before in rows) {
            assertTrue(File(before.path).exists()); assertEquals(before, db.transcribe().segment(before.id))
        }
    }
}
