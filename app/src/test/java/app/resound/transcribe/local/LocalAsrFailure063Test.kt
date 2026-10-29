package app.resound.transcribe.local

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.room.Room
import app.resound.capture.RoomCaptureStatsRepository
import app.resound.data.*
import app.resound.diagnostics.*
import app.resound.transcribe.*
import app.resound.update.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class LocalAsrFailure063Test {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private lateinit var db: CaptureDatabase
    private lateinit var directory: File
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        directory = File(context.cacheDir, "failure063").apply { mkdirs() }
        context.getSharedPreferences("pipeline-times", 0).edit().clear().commit()
    }
    @After fun close() { db.close(); directory.deleteRecursively() }
    private fun failure(phase: String, error: Throwable) = Bundle().apply { LocalAsrFailure.write(this, phase, error) }

    @Test fun missingAudioIsSpecificAndDoesNotStopLaterSegmentsOrRetryForever() = runBlocking {
        val missing = File(directory, "missing.ogg")
        val good = File(directory, "good.ogg").apply { writeText("fixture") }
        for ((index, file) in listOf(missing, good).withIndex()) db.capture().segment(SegmentEntity(
            id = index + 1L, startAt = index * 1000L, endAt = index * 1000L + 500, sourceId = "mic", path = file.path, sizeBytes = 7))
        val calls = mutableListOf<String>()
        val provider = object : AsrProvider {
            override suspend fun recognize(audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit): AsrResult {
                calls += audio.file.name
                try { readLocalAudio(audio.file.path, context.filesDir, context.cacheDir) { shortArrayOf(1) } }
                catch (e: Exception) { throw LocalAsrFailure.exception(failure("audio", e)) }
                return AsrResult(listOf(AsrSentence(0, 100, "成功", null)), 500, null)
            }
        }
        val runner = TranscribeRunner(db.transcribe(), provider, { "" }, { _, _ -> }, deferAudioCleanup = true)
        assertFalse(runner.run())
        assertEquals(listOf("missing.ogg", "good.ogg"), calls)
        val failed = db.transcribe().segment(1)
        assertEquals("Failed", failed.status); assertEquals("录音文件已不存在", failed.error)
        assertEquals(1, failed.attempts); assertNull(failed.nextRetryAt)
        assertEquals("Done", db.transcribe().segment(2).status)
        assertEquals("Audio", db.transcribe().recentAttempts().single { it.segmentId == 1L }.errorType)
        assertTrue(db.transcribe().candidates(Long.MAX_VALUE).isEmpty())
        assertFalse(AsrError.Audio in TranscribePolicy.ROUND_STOPPERS)
    }

    @Test fun invalidAudioDecodeMapsToAudioAndFailedRecordingIsKept() = runBlocking {
        val file = File(directory, "invalid.m4a").apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }
        val error = try {
            readLocalAudio(file.path, context.filesDir, context.cacheDir, AsrAudioReader(context.cacheDir)::readPcm)
            fail("非音频不得识别成功"); error("unreachable")
        } catch (e: Exception) { e }
        val mapped = LocalAsrFailure.exception(failure("audio", error))
        assertEquals(AsrError.Audio, mapped.type)
        assertEquals(AsrError.Audio.human, mapped.detail)
        db.capture().segment(SegmentEntity(1, 0, 100, "mic", file.path, 4, "Failed", attempts = 1, nextRetryAt = 1))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repo = LocalOriginalAudio(db.transcribe(), context.getSharedPreferences("failure063", 0), scope,
                playback = PlaybackCaptureHold(scope, {}))
            assertEquals(0, repo.clear(true).segments); assertTrue(file.exists())
        } finally { scope.cancel() }
    }

    @Test fun recognitionAndSpeakerErrorsKeepTheirOriginalMapping() {
        val asr = failure("asr", IllegalStateException("private model detail"))
        assertEquals(AsrError.LocalEngine, LocalAsrFailure.exception(asr).type)
        assertEquals(AsrError.LocalEngine.human, LocalAsrFailure.exception(asr).detail)
        assertEquals(AsrError.Speakers, LocalAsrFailure.exception(failure("speakers", IllegalStateException())).type)
        assertEquals("java.lang.IllegalStateException", asr.getString("exceptionClass"))
    }

    @Test fun exportedProblemReportContainsExceptionClassButNoMessageOrPath() = runBlocking {
        val sensitive = "secret recording /private/recording.ogg"
        val data = failure("audio", IllegalArgumentException(sensitive))
        assertEquals(setOf("phase", "exceptionClass", "missingAudio"), data.keySet())
        PipelineTimes.record(context, "123 ${LocalAsrFailure.diagnostic(data)}")
        val updater = object : AppUpdater {
            override val currentVersion = "test"
            override val state = MutableStateFlow<UpdateState>(UpdateState.Idle)
            override val autoCheck = MutableStateFlow(false)
            override fun check(manual: Boolean) {}
            override fun download() {}
            override fun install() {}
            override fun later() {}
            override fun skip() {}
            override fun setAutoCheck(enabled: Boolean) {}
            override fun canInstall() = false
        }
        val report = LocalProblemReports(context, CrashRecords(context), RoomCaptureStatsRepository(context, db.capture()), updater).export()
        try {
            val text = report.readText()
            assertTrue(text.contains("phase=audio errorType=Audio exceptionClass=java.lang.IllegalArgumentException"))
            assertFalse(text.contains(sensitive)); assertFalse(text.contains("recording.ogg"))
        } finally { report.delete() }
    }
}
