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
    @Test fun priceReadsAtomicWritesAndFailureMatchOldRepository() {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).build()
        val prefs = context.getSharedPreferences("synthetic177-price", 0)
        prefs.edit().clear().putString("promptPerMillion", "").putString("completionPerMillion", "2.500").commit()
        var commits = 0; var allow = true
        val monitored = object : android.content.SharedPreferences by prefs {
            override fun edit(): android.content.SharedPreferences.Editor {
                val edit = prefs.edit()
                return object : android.content.SharedPreferences.Editor by edit {
                    override fun putString(key: String, value: String?) = apply { edit.putString(key, value) }
                    override fun commit(): Boolean { commits++; return if (allow) edit.commit() else false }
                }
            }
        }
        val output = StringBuilder(); fun save(key: String, value: Any?) { output.appendLine("$key=$value") }
        try {
            val repo = RoomUsageRepository(db.memory(), db.transcribe(), monitored)
            save("old.read", repo.price.value)
            repo.setPrice(UsagePrice(1.23456789, 0.0, null)); save("new.read", repo.price.value); save("old.format", prefs.all.toSortedMap()); save("commits", commits)
            save("reopened", RoomUsageRepository(db.memory(), db.transcribe(), prefs).price.value)
            allow = false
            save("error", runCatching { repo.setPrice(UsagePrice(9.0, 8.0, 7.0)) }.exceptionOrNull()?.let { it::class.simpleName to it.message })
            save("failed.state", repo.price.value); save("failed.prefs", prefs.all.toSortedMap()); save("failed.commits", commits)
            val fixture = File("src/test/resources/repo177/RoomUsageRepository/prices.txt")
            assertEquals(fixture.readText(), output.toString())
        } finally { db.close() }
    }
}
