package app.resound.transcribe

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.work.*
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.resound.data.*
import app.resound.platform.ZoneProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.LocalDate
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class TranscriptRepository177Test {
    private lateinit var context: Context
    private lateinit var db: CaptureDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var dispatcher: StartGate
    private class StartGate : CoroutineDispatcher() {
        private val initial = mutableListOf<Pair<CoroutineContext, Runnable>>()
        private var started = false
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (started) Dispatchers.IO.dispatch(context, block) else initial += context to block
        }
        fun start() { started = true; initial.toList().also { initial.clear() }.forEach { Dispatchers.IO.dispatch(it.first, it.second) } }
    }
    private class Settings : TranscribeSettings {
        override val hasKey = MutableStateFlow(false)
        override val intervalMinutes = MutableStateFlow(30)
        override val wifiOnly = MutableStateFlow(true)
        override fun saveKey(key: String) { hasKey.value = true }
        override fun clearKey() { hasKey.value = false }
        override fun setIntervalMinutes(minutes: Int) { intervalMinutes.value = minutes }
        override fun setWifiOnly(value: Boolean) { wifiOnly.value = value }
        override suspend fun test() = Result.success("合成服务")
    }
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("transcribe-queue", 0).edit().clear().commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).build()
        dispatcher = StartGate(); scope = CoroutineScope(SupervisorJob() + dispatcher)
    }
    @After fun close() = runBlocking {
        scope.coroutineContext[Job]!!.cancelAndJoin(); db.close()
        WorkManager.getInstance(context).cancelAllWork().result.get(); WorkManagerTestInitHelper.closeWorkDatabase()
    }
    private fun fixture(name: String, output: String) {
        val file = File("src/test/resources/repo177/RoomTranscriptRepository/$name.txt")
        assertEquals(file.readText(), output)
    }
    @Test fun queryQueueAndSubscriptionMatchOldRepository() = runBlocking {
        val prefs = context.getSharedPreferences("transcribe-queue", 0)
        prefs.edit().putLong("lastRunAt", 1000).putString("lastError", "合成旧错误").commit()
        val settings = Settings(); val scheduler = TranscribeScheduler(context, settings)
        val repo = RoomTranscriptRepository(context, db.transcribe(), settings, scheduler, scope, ZoneProvider { "America/New_York" }, app.resound.platform.SystemNow, Dispatchers.IO)
        val out = StringBuilder(); fun save(name: String, value: Any?) { out.appendLine("$name=$value") }
        save("initial", repo.queue.value); save("clock.noSubscriber", repo.clock.replayCache.isEmpty())
        val at = java.time.LocalDate.of(2026, 11, 1).atStartOfDay(java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli()
        val audio = File(context.cacheDir, "synthetic177-transcript.ogg").apply { writeBytes(byteArrayOf(1)) }
        try {
            listOf("Done", "Pending", "Running", "Failed", "SkippedEnv", "SkippedPlayback").forEachIndexed { i, status ->
                db.capture().segment(SegmentEntity((i + 1).toLong(), at + i * 10_000, at + i * 10_000 + 1000, "mic", if (i == 0) audio.path else "${audio.path}-$i", 1, status,
                    firstFailedAt = if (status == "Failed") System.currentTimeMillis() else null))
            }
            db.capture().segment(SegmentEntity(7, at + 90_000_000, at + 90_001_000, "mic", "${audio.path}-next", 1, "Done"))
            db.transcribe().insertLines(listOf(TranscriptLineEntity(2, 1, at + 200, at + 300, "后一句", "1"),
                TranscriptLineEntity(1, 1, at + 100, at + 200, "先一句", "0", voiceInputJson = """{"app":"合成应用","package_name":"synthetic","title":" ","before":null,"after":"后文"}""")))
            val today = java.time.LocalDate.now().toString(); db.transcribe().reserveAudio(today, 2345, null)
            dispatcher.start()
            save("queue.loaded", withTimeout(5000) { repo.queue.first { it.pending == 1 && it.failed == 1 && it.usedSecondsToday == 2L } })
            settings.saveKey("synthetic"); save("ready", withTimeout(5000) { repo.queue.first { it.lastError == "合成旧错误" } })
            repo.updateState(5000, "新错误"); repo.updateProgress(4, 2)
            save("progress", withTimeout(5000) { repo.queue.first { it.lastRunAt == 5000L && it.runDone == 2 } })
            save("persisted", prefs.getLong("lastRunAt", 0) to prefs.getString("lastError", null))
            save("day", repo.day(LocalDate(2026, 11, 1)).first())
            withTimeout(5000) { while (repo.clock.replayCache.isNotEmpty()) delay(10) }
            save("clock.afterCancel", repo.clock.replayCache.isEmpty())
            audio.delete(); save("day.fileGone", repo.day(LocalDate(2026, 11, 1)).first().map { it.segmentId to it.audioAvailable })
            repo.updateProgress(0, 0); withTimeout(5000) { repo.queue.first { it.runTotal == 0 } }
            scheduler.schedule()
            val expected = WorkManager.getInstance(context).getWorkInfosByTag(TranscribeScheduler.TAG).get()
                .filter { it.state == WorkInfo.State.ENQUEUED }.map { it.nextScheduleTimeMillis }.filter { it > 0 && it != Long.MAX_VALUE }.min()
            val scheduled = withTimeout(5000) { repo.queue.first { it.nextRunAt == expected } }
            // 外部 WorkManager 提供的绝对时刻每次运行不同；比较同次真实输入与仓储输出的差值。
            save("scheduled.deltaFromInput", scheduled.nextRunAt!! - expected)
            fixture("queries", out.toString())
        } finally { audio.delete() }
    }
    @Test fun commandsPreserveQueueTransitionsAndFileFailure() = runBlocking {
        val settings = Settings(); val scheduler = TranscribeScheduler(context, settings)
        val repo = RoomTranscriptRepository(context, db.transcribe(), settings, scheduler, scope, ZoneProvider { "UTC" }, app.resound.platform.SystemNow, Dispatchers.IO)
        val root = File(context.cacheDir, "transcript177-commands").apply { mkdirs() }
        val out = StringBuilder()
        suspend fun seed(id: Long, status: String, path: File) {
            db.capture().segment(SegmentEntity(id, id * 1000, id * 1000 + 500, "mic", path.path, 1, status, firstFailedAt = System.currentTimeMillis()))
        }
        suspend fun status(id: Long, expected: String) = withTimeout(5000) { db.transcribe().observeSegments().first { rows -> rows.any { it.id == id && it.status == expected } } }
        try {
            val first = File(root, "first").apply { writeText("synthetic") }
            val failedDelete = File(root, "nonempty").apply { mkdirs(); File(this, "child").writeText("synthetic") }
            seed(1, "Failed", first); seed(2, "Failed", failedDelete)
            dispatcher.start()
            repo.ignoreFailed(); status(1, "Quiet"); status(2, "Quiet")
            out.appendLine("ignored=${db.transcribe().observeSegments().first().sortedBy { it.id }.map { it.id to it.status }}")
            repo.retrySegment(1); status(1, "Pending"); out.appendLine("retry.one=${db.transcribe().segment(1).status}")
            seed(3, "Failed", File(root, "absent")); repo.retryFailed(); status(3, "Pending")
            out.appendLine("retry.all=${db.transcribe().observeSegments().first().sortedBy { it.id }.map { it.id to it.status }}")
            val existingJobs = scope.coroutineContext[Job]!!.children.toSet()
            repo.deleteFailed(2)
            // 等待这一次命令；失败删除不会产生数据库通知。
            withTimeout(5000) { scope.coroutineContext[Job]!!.children.filter { it !in existingJobs }.toList().forEach { it.join() } }
            out.appendLine("delete.denied=${db.transcribe().segment(2).status to failedDelete.isDirectory}")
            seed(4, "Abandoned", File(root, "delete").apply { writeText("synthetic") })
            repo.deleteFailed(4); withTimeout(5000) { db.transcribe().observeSegments().first { rows -> rows.none { it.id == 4L } } }
            out.appendLine("delete.ok=${db.transcribe().findSegment(4) == null && !File(root, "delete").exists()}")
            seed(5, "SkippedPlayback", File(root, "playback").apply { writeText("synthetic") })
            repo.transcribeAnyway(5); status(5, "Pending"); out.appendLine("anyway=${db.transcribe().segment(5).status}")
            repo.runNow()
            withTimeout(5000) { while (WorkManager.getInstance(context).getWorkInfosForUniqueWork(TranscribeScheduler.IMMEDIATE).get().isEmpty()) delay(10) }
            out.appendLine("scheduled=true")
            fixture("commands", out.toString())
        } finally { root.deleteRecursively() }
    }
    @Test fun failedAtomicSaveDoesNotPublishNewState() = runBlocking {
        val settings = Settings(); val base = context.getSharedPreferences("transcribe-queue", 0)
        base.edit().putLong("lastRunAt", 12).putString("lastError", "旧值").commit()
        val failing = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = object : SharedPreferences by base {
                override fun edit(): SharedPreferences.Editor {
                    val edit = base.edit()
                    return object : SharedPreferences.Editor by edit {
                        override fun putLong(key: String, value: Long) = apply { edit.putLong(key, value) }
                        override fun putString(key: String, value: String?) = apply { edit.putString(key, value) }
                        override fun commit() = false
                    }
                }
            }
        }
        val repo = RoomTranscriptRepository(failing, db.transcribe(), settings, TranscribeScheduler(context, settings), scope, ZoneProvider { "UTC" }, app.resound.platform.SystemNow, Dispatchers.IO)
        try {
            assertThrows(IllegalStateException::class.java) { repo.updateState(99, "不能发布") }
            fixture("save-failure", "state=${repo.queue.value}\nprefs=${base.getLong("lastRunAt", 0) to base.getString("lastError", null)}\n")
        } finally { dispatcher.start() }
    }
}
