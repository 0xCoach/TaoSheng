package app.resound.transcribe

import android.app.Application
import android.content.Context
import androidx.work.*
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class TranscribeSchedulerTest {
    private lateinit var context: Context
    private lateinit var manager: WorkManager
    private class Settings : TranscribeSettings {
        override val hasKey = MutableStateFlow(false)
        override val intervalMinutes = MutableStateFlow(30)
        override val wifiOnly = MutableStateFlow(true)
        override fun saveKey(key: String) { hasKey.value = true }
        override fun clearKey() { hasKey.value = false }
        override fun setIntervalMinutes(minutes: Int) { intervalMinutes.value = minutes }
        override fun setWifiOnly(value: Boolean) { wifiOnly.value = value }
        override suspend fun test() = Result.success("测试")
    }
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        manager = WorkManager.getInstance(context)
    }
    @After fun close() { manager.cancelAllWork().result.get(); WorkManagerTestInitHelper.closeWorkDatabase() }
    @Test fun playbackSkippedStaysOutOfQueueAndManualOverrideRequeues() = runBlocking {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, app.resound.data.CaptureDatabase::class.java).allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val audio = java.io.File.createTempFile("playback-test", ".ogg", context.cacheDir).apply { writeBytes(byteArrayOf(1)) }
        try {
            db.capture().segment(app.resound.data.SegmentEntity(id = 1, startAt = 1, endAt = 10_001, sourceId = "mic", path = audio.path, sizeBytes = 1,
                status = "SkippedPlayback", completedAt = 10_001, playbackMillis = 9_000))
            assertTrue(db.transcribe().candidates(Long.MAX_VALUE).isEmpty())
            assertEquals(1, db.transcribe().expired(10_001).size)
            assertTrue(TranscribePolicy.canDelete("SkippedPlayback", 10_001, 10_001 + TranscribePolicy.RETENTION_MILLIS))
            val settings = Settings()
            val repository = RoomTranscriptRepository(context, db.transcribe(), settings, TranscribeScheduler(context, settings), scope)
            repository.transcribeAnyway(1)
            withTimeout(3000) { db.transcribe().observeSegments().first { it.single().status == "Pending" } }
            assertEquals(1, db.transcribe().candidates(Long.MAX_VALUE).size)
            assertNull(db.transcribe().segment(1).completedAt)
            assertEquals(9_000L, db.transcribe().segment(1).playbackMillis)
            withTimeout(3000) { while (manager.getWorkInfosForUniqueWork(TranscribeScheduler.IMMEDIATE).get().isEmpty()) delay(20) }
            Unit
        } finally { scope.cancel(); db.close(); audio.delete() }
    }
    @Test fun repositoryPublishesAndResetsRealRoundProgress() = runBlocking {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, app.resound.data.CaptureDatabase::class.java)
            .allowMainThreadQueries().build()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        try {
            val settings = Settings()
            val repository = RoomTranscriptRepository(context, db.transcribe(), settings, TranscribeScheduler(context, settings), scope)
            repository.updateProgress(3, 1)
            withTimeout(3000) { repository.queue.first { it.runTotal == 3 && it.runDone == 1 } }
            repository.updateState(1000, "测试错误")
            val state = withTimeout(3000) { repository.queue.first { it.lastRunAt == 1000L } }
            assertEquals(3, state.runTotal); assertEquals(1, state.runDone)
            repository.updateProgress(0, 0)
            withTimeout(3000) { repository.queue.first { it.runTotal == 0 && it.runDone == 0 } }
            Unit
        } finally { job.cancelAndJoin(); db.close() }
    }
    @Test fun localScheduleDoesNotRequireNetwork() {
        val settings=AndroidTranscribeSettings(context)
        settings.setSource(TranscribeSource.Local)
        TranscribeScheduler(context,settings).schedule()
        assertEquals(NetworkType.NOT_REQUIRED,manager.getWorkInfosForUniqueWork(TranscribeScheduler.PERIODIC).get().single().constraints.requiredNetworkType)
    }
    @Test fun periodicScheduleUsesIntervalAndUnmeteredAndUpdatesSameWork() {
        val settings = Settings()
        val scheduler = TranscribeScheduler(context, settings)
        val before = System.currentTimeMillis()
        scheduler.schedule()
        val first = manager.getWorkInfosForUniqueWork(TranscribeScheduler.PERIODIC).get().single()
        assertEquals(NetworkType.UNMETERED, first.constraints.requiredNetworkType)
        assertTrue(first.nextScheduleTimeMillis >= before + 30 * 60_000)
        settings.setIntervalMinutes(45); settings.setWifiOnly(false); scheduler.schedule()
        val next = manager.getWorkInfosForUniqueWork(TranscribeScheduler.PERIODIC).get().single()
        assertEquals(first.id, next.id)
        assertEquals(NetworkType.CONNECTED, next.constraints.requiredNetworkType)
        assertEquals(45 * 60_000L, next.periodicityInfo!!.repeatIntervalMillis)
    }
    @Test fun immediateWorkIsUniqueAndReconfiguresQueuedConstraints() = runBlocking {
        val settings = Settings()
        val scheduler = TranscribeScheduler(context, settings)
        scheduler.runNow()
        withTimeout(3000) { while(manager.getWorkInfosForUniqueWork(TranscribeScheduler.IMMEDIATE).get().isEmpty()) delay(10) }
        val old = manager.getWorkInfosForUniqueWork(TranscribeScheduler.IMMEDIATE).get().single()
        scheduler.runNow()
        withTimeout(3000) { while(manager.getWorkInfosForUniqueWork(TranscribeScheduler.IMMEDIATE).get().single().id == old.id) delay(10) }
        val first = manager.getWorkInfosForUniqueWork(TranscribeScheduler.IMMEDIATE).get().single()
        assertEquals(NetworkType.UNMETERED, first.constraints.requiredNetworkType)
        settings.setWifiOnly(false)
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        scheduler.settingsChanged(scope)
        withTimeout(3000) {
            while (manager.getWorkInfosForUniqueWork(TranscribeScheduler.IMMEDIATE).get().single().constraints.requiredNetworkType != NetworkType.CONNECTED) delay(10)
        }
        job.cancelAndJoin()
        assertEquals(first.id, manager.getWorkInfosForUniqueWork(TranscribeScheduler.IMMEDIATE).get().single().id)
    }
    @Test fun diagnosticsContainOnlyAttemptMetadataAndQueueCounts() = runBlocking {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, app.resound.data.CaptureDatabase::class.java)
            .allowMainThreadQueries().build()
        val settings = Settings().apply { saveKey("synthetic-key") }
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        try {
            val dao = db.transcribe()
            val scheduler = TranscribeScheduler(context, settings)
            scheduler.schedule()
            db.capture().segment(app.resound.data.SegmentEntity(startAt = 1, endAt = 3001, sourceId = "mic", path = "/unused", sizeBytes = 1))
            val row = dao.observeSegments().first().single()
            dao.complete(row.copy(status = "Done", completedAt = 100), listOf(app.resound.data.TranscriptLineEntity(
                segmentId = row.id, startAt = 1, endAt = 3001, text = "不应导出的原话", speakerTag = "0")))
            repeat(25) { dao.attempt(app.resound.data.AsrAttemptEntity(segmentId = row.id, at = it.toLong(),
                result = "Done", errorType = null, elapsedMillis = 300, sentMillis = 3000, logId = "safe-log")) }
            dao.reserveAudio(java.time.LocalDate.now().toString(), 3000, row.id)
            val repository = RoomTranscriptRepository(context, dao, settings, scheduler, scope)
            withTimeout(3000) { repository.queue.first { it.usedSecondsToday == 3L } }
            val output = repository.diagnostics()
            assertEquals(20, output.lines().count { it.startsWith("转写元数据") })
            assertTrue(output.contains("usedSecondsToday=3"))
            assertTrue(output.contains("elapsedMillis=300"))
            assertFalse(output.contains("不应导出的原话"))
            assertFalse(output.contains("synthetic-key"))
            assertEquals("不应导出的原话", repository.day(java.time.Instant.ofEpochMilli(1).atZone(java.time.ZoneId.systemDefault()).toLocalDate()).first().single().lines.single().text)
            assertFalse(repository.day(java.time.Instant.ofEpochMilli(1).atZone(java.time.ZoneId.systemDefault()).toLocalDate()).first().single().audioAvailable)
        } finally { job.cancelAndJoin(); db.close() }
    }

}
