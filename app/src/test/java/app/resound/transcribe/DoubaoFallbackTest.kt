package app.resound.transcribe

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import app.resound.backup.PreferenceBackupPolicy
import app.resound.data.CaptureDatabase
import app.resound.data.SegmentEntity
import app.resound.digest.HeaderEntry
import app.resound.digest.ServiceHeaders
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class DoubaoFallbackTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: CaptureDatabase
    private val dao get() = db.transcribe()
    private lateinit var file: File
    private val primary = AsrResult(emptyList(), 3000, "primaryLog")
    private var doubao: () -> AsrResult = { primary }
    private var custom: () -> AsrResult = { primary.copy(logId = "secondaryLog") }
    private val calls = mutableListOf<TranscribeSource>()
    private val audios = mutableListOf<AsrAudio>()
    private val speeds = mutableListOf<TranscribeSource?>()
    private val provider = object : SourceAsrProvider {
        override suspend fun recognize(audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit) =
            recognize(TranscribeSource.Doubao, audio, key, beforeAudio)

        override suspend fun recognize(source: TranscribeSource, audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit): AsrResult {
            calls += source
            audios += audio
            beforeAudio()
            return when (source) {
                TranscribeSource.Doubao -> { assertEquals("doubao-key", key); doubao() }
                TranscribeSource.Custom -> { assertEquals("custom-key", key); custom() }
                else -> error("来源错误")
            }
        }
    }

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
            .setDriver(AndroidSQLiteDriver()).allowMainThreadQueries().build()
        file = File.createTempFile("fallback", ".ogg", context.cacheDir)
    }
    @After fun close() { db.close(); file.delete() }

    private suspend fun insert(source: TranscribeSource? = null, redo: Boolean = false): Long {
        db.capture().segment(SegmentEntity(startAt = 1000, endAt = 4000, sourceId = "mic", path = file.path,
            sizeBytes = 0, separateSpeakers = false, speakerCount = 2, asrSource = source?.name, redoPending = redo))
        return dao.observeSegments().first().single().id
    }
    private fun runner(enabled: Boolean = true, customNetwork: Boolean = true, customKey: () -> String? = { "custom-key" }) =
        TranscribeRunner(dao, provider, { "doubao-key" }, { _, _ -> },
        sourceNetwork = { it != TranscribeSource.Custom || customNetwork },
        defaultSource = { TranscribeSource.Doubao }, doubaoFallback = { enabled }, customFallbackCredential = customKey,
        recordSpeed = { source, _, _ -> speeds += source })

    @Test fun customTextCompletesSegmentAndReservesEachUpload() = runBlocking {
        val id = insert()
        custom = { AsrResult(listOf(AsrSentence(500, 2600, "远处的声音", null)), 3000, "secondaryLog", 11, 3) }
        assertFalse(runner().run())
        assertEquals(listOf(TranscribeSource.Doubao, TranscribeSource.Custom), calls)
        assertEquals(calls, speeds)
        assertSame(audios.first(), audios.last())
        assertFalse(audios.last().separateSpeakers)
        assertEquals(2, audios.last().speakerCount)
        assertEquals("Done", dao.segment(id).status)
        assertEquals("secondaryLog", dao.segment(id).logId)
        val line = dao.linesForRedo(id).single()
        assertEquals("远处的声音", line.text)
        assertEquals(1500L, line.startAt)
        val attempts = dao.recentAttempts().reversed()
        assertEquals(listOf("Empty", "CustomFallbackDone"), attempts.map { it.result })
        assertEquals(listOf("primaryLog", "secondaryLog"), attempts.map { it.logId })
        assertEquals(listOf(3000L, 3000L), attempts.map { it.sentMillis })
        val usage = dao.observeUsage().first().single()
        assertEquals(6000L, dao.segment(id).sentMillis)
        assertEquals(6000L, usage.sentMillis)
        assertEquals(11L, usage.inputTokens)
        assertEquals(3L, usage.outputTokens)
    }

    @Test fun customFailureKeepsPrimaryEmptyAndNeverQueuesRetry() = runBlocking {
        val id = insert()
        custom = { throw AsrException(AsrError.Network, "custom-key") }
        assertFalse(runner().run())
        assertEquals("Empty", dao.segment(id).status)
        assertEquals("primaryLog", dao.segment(id).logId)
        assertNull(dao.segment(id).nextRetryAt)
        assertTrue(dao.candidates(Long.MAX_VALUE).isEmpty())
        val failure = dao.recentAttempts().first()
        assertEquals("CustomFallbackFailed", failure.result)
        assertEquals("Network", failure.errorType)
        assertNull(failure.logId)
    }

    @Test fun customSilenceCompletesEmptyAndAccountsForReturnedTokens() = runBlocking {
        val id = insert()
        custom = { primary.copy(logId = "secondaryLog", inputTokens = 7) }
        assertFalse(runner().run())
        assertEquals("Empty", dao.segment(id).status)
        assertEquals("primaryLog", dao.segment(id).logId)
        assertTrue(dao.linesForRedo(id).isEmpty())
        assertEquals("CustomFallbackEmpty", dao.recentAttempts().first().result)
        assertEquals(7L, dao.observeUsage().first().single().inputTokens)
    }

    @Test fun primaryNetworkFailureKeepsExistingRetry() = runBlocking {
        val id = insert()
        doubao = { throw AsrException(AsrError.Network) }
        assertTrue(runner().run())
        assertEquals(listOf(TranscribeSource.Doubao), calls)
        assertEquals("Failed", dao.segment(id).status)
        assertNotNull(dao.segment(id).nextRetryAt)
        assertEquals("Network", dao.recentAttempts().single().errorType)
    }

    @Test fun disabledOrUnconfiguredFallbackCompletesWithJustDoubao() = runBlocking {
        val id = insert()
        assertFalse(runner(enabled = false) { error("开关关闭时不应读取自定义凭据") }.run())
        assertEquals("Empty", dao.segment(id).status)
        dao.update(dao.segment(id).copy(status = "Pending", completedAt = null))
        assertFalse(runner { null }.run())
        assertEquals("Empty", dao.segment(id).status)
        assertEquals(listOf(TranscribeSource.Doubao, TranscribeSource.Doubao), calls)
    }

    @Test fun manualDoubaoAndSettingsTestEachMakeOnlyTheirOwnRequest() = runBlocking {
        val id = insert(TranscribeSource.Doubao)
        val runner = runner()
        assertFalse(runner.run())
        assertTrue(runner.test(AsrAudio(file, "ogg", 3000), "doubao-key").isSuccess)
        assertEquals(listOf(TranscribeSource.Doubao, TranscribeSource.Doubao), calls)
        assertEquals(listOf(null, id), dao.recentAttempts().map { it.segmentId })
    }

    @Test fun redoAndMeteredNetworkSkipFallback() = runBlocking {
        val id = insert(redo = true)
        assertFalse(runner().run())
        assertEquals(listOf(TranscribeSource.Doubao), calls)
        dao.update(dao.segment(id).copy(status = "Pending", completedAt = null, redoPending = false))
        assertFalse(runner(customNetwork = false).run())
        assertEquals(listOf(TranscribeSource.Doubao, TranscribeSource.Doubao), calls)
        assertEquals("Empty", dao.segment(id).status)
    }

    @Test fun preferencePersistsAndCustomNeedsEndpointAndModel() {
        val values = mutableMapOf<String, String>()
        val headers = ServiceHeaders({ values[it].orEmpty() }, { k, v -> values[k] = v }, { values.remove(it) })
        val settings = AndroidTranscribeSettings(context, headers)
        settings.setSource(TranscribeSource.Doubao)
        assertFalse(settings.doubaoFallback.value)
        settings.setDoubaoFallback(true)
        assertTrue(AndroidTranscribeSettings(context, headers).doubaoFallback.value)
        assertTrue(PreferenceBackupPolicy.setting("transcribe", "doubaoFallback"))
        settings.saveHeaders("Doubao", listOf(HeaderEntry("X-Api-Key", "primary")))
        settings.saveCustom("https://example.invalid/asr", "asr", CustomEndpointMode.Full)
        assertEquals("", settings.customFallbackCredential()) // 免密自建服务也能补
        settings.saveHeaders("Custom", listOf(HeaderEntry("Authorization", "Basic custom")))
        assertEquals("", settings.customFallbackCredential())
        context.getSharedPreferences("transcribe", 0).edit().remove("model").commit()
        assertNull(settings.customFallbackCredential())
    }
}
