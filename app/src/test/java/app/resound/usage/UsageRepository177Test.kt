package app.resound.usage

import android.app.Application
import androidx.room.Room
import app.resound.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.datetime.LocalDate
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class UsageRepository177Test {
    @Test fun bothQueryFlowsMatchRecordedBytes() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
            .setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).build()
        val repository = RoomUsageRepository(db.memory(), db.transcribe(), context.getSharedPreferences("synthetic177-usage", 0))
        val from = LocalDate(2026, 11, 1); val to = LocalDate(2026, 11, 2)
        val llm = Channel<List<LlmUsageRow>>(Channel.UNLIMITED)
        val asr = Channel<List<AsrUsageRow>>(Channel.UNLIMITED)
        val jobs = listOf(launch { repository.llm(from, to).collect { llm.send(it) } }, launch { repository.asr(from, to).collect { asr.send(it) } })
        val out = StringBuilder()
        fun record(label: String, value: Any?) { out.appendLine("$label=$value") }
        try {
            record("llm.empty", withTimeout(5000) { llm.receive() }); record("asr.empty", withTimeout(5000) { asr.receive() })
            db.memory().addUsage("2026-11-01", 10, 2, UsagePurpose.Ask)
            record("llm.changed", withTimeout(5000) { llm.receive() })
            db.transcribe().reserveAudio("2026-11-01", 1234, null)
            record("asr.changed", withTimeout(5000) { asr.receive() })
            db.memory().addUsage("2026-11-02", 30, 6, UsagePurpose.Digest)
            db.memory().addUsage("2026-10-31", 99, 99); db.memory().addUsage("2026-11-03", 99, 99)
            db.transcribe().reserveAudio("2026-11-02", 2345, null)
            db.transcribe().reserveAudio("2026-10-31", 9999, null); db.transcribe().reserveAudio("2026-11-03", 9999, null)
            record("llm.bounds", repository.llm(from, to).first()); record("asr.bounds", repository.asr(from, to).first())
            val file = File("src/test/resources/repo177/RoomUsageRepository/outputs.txt")
            assertEquals(file.readText(), out.toString())
        } finally { jobs.forEach { it.cancelAndJoin() }; llm.close(); asr.close(); db.close() }
    }
}
