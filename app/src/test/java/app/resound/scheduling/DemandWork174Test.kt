package app.resound.scheduling

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.work.*
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.resound.capture.CaptureWatchdog
import app.resound.data.*
import app.resound.privacy.PrivacyCleanupWorker
import app.resound.push.*
import app.resound.recordings.RecordingAudioSweepWorker
import app.resound.search.*
import app.resound.transcribe.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class DemandWork174Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: CaptureDatabase
    private lateinit var work: WorkManager
    private lateinit var scope: CoroutineScope
    private lateinit var original: LocalOriginalAudio
    private lateinit var files: File

    @Before fun setup() {
        // 只验证真实 WorkManager 调度；不启动网络、ONNX、录音或真实应用装配。
        val factory = object : WorkerFactory() {
            override fun createWorker(context: Context, name: String, parameters: WorkerParameters): ListenableWorker =
                object : CoroutineWorker(context, parameters) {
                    override suspend fun doWork(): Result = awaitCancellation()
                }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build())
        work = WorkManager.getInstance(context)
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries()
            .addCallback(SearchIndex.callback).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        files = File(context.cacheDir, "demand174").apply { mkdirs() }
        original = LocalOriginalAudio(db.transcribe(), context.getSharedPreferences("demand174", 0), scope,
            playback = PlaybackCaptureHold(scope, {}))
    }
    @After fun close() = runBlocking {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        work.cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
        db.close()
        files.deleteRecursively()
        File(context.filesDir, "import-segments").deleteRecursively()
        Unit
    }
    private fun active(name: String) = work.getWorkInfosForUniqueWork(name).get().filter { !it.state.isFinished }
    private suspend fun until(predicate: () -> Boolean) = withTimeout(5000) { while (!predicate()) delay(10) }
    private suspend fun event() = db.memory().insertEvent(MemoryEventEntity(startAt = 1, endAt = 2,
        scene = "其他", title = "测试", summary = "摘要", pointsJson = "[]", peopleJson = "[]",
        place = null, emotion = null, intensity = null, important = false, createdAt = 1, model = "test"))
    private suspend fun segment(source: String, status: String = "Done"): File {
        val file = File(files, "audio.ogg").apply { writeText("synthetic") }
        db.capture().segment(SegmentEntity(id = 1, startAt = 1, endAt = 2, sourceId = source,
            path = file.path, sizeBytes = file.length(), status = status, completedAt = 2, voiceprintProcessedAt = 2))
        return file
    }

    @Test fun embeddingStartupCancelsLegacyAndOnlyDownloadsWhenContentAppears() = runBlocking {
        work.enqueueUniquePeriodicWork("search-embedding-catchup", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<EmbeddingWork>(15, TimeUnit.MINUTES).build()).result.get()
        EmbeddingScheduler.observe(context, db, scope)
        until { active("search-embedding-catchup").isEmpty() }
        assertTrue(active("search-model").isEmpty())
        event()
        until { active("search-model").isNotEmpty() }
        assertEquals(NetworkType.CONNECTED, active("search-model").single().constraints.requiredNetworkType)
        assertTrue(active("search-embeddings").isEmpty())
        // 模拟重启：取消旧任务与观察器，数据库仍有待算内容。
        scope.coroutineContext[Job]!!.cancelAndJoin()
        work.cancelUniqueWork("search-model").result.get()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        EmbeddingScheduler.observe(context, db, scope)
        until { active("search-model").isNotEmpty() }
    }
    @Test fun embeddingPowerRecoveryAndEditsAppendBehindRunningWork() = runBlocking {
        val id = event()
        EmbeddingScheduler.reconcile(context, db, modelReady = true, powerAllowed = false)
        assertTrue(active("search-embeddings-power").single().constraints.requiresCharging())
        assertTrue(active("search-embeddings").isEmpty())
        EmbeddingScheduler.reconcile(context, db, modelReady = true, powerAllowed = true)
        until { active("search-embeddings-power").isEmpty() && active("search-embeddings").size == 1 }
        assertTrue(active("search-embeddings-power").isEmpty())
        assertEquals(1, active("search-embeddings").size)
        val store = EmbeddingStore(db)
        store.save(store.pending(SearchModels.modelId).single(), SearchModels.modelId, floatArrayOf(1f))
        assertTrue(store.pending(SearchModels.modelId).isEmpty())
        db.memory().editEvent(id, "改后的标题", "摘要", "[]", 3)
        EmbeddingScheduler.reconcile(context, db, modelReady = true, powerAllowed = true)
        until { active("search-embeddings").size == 2 }
        assertEquals(2, active("search-embeddings").size)
    }
    @Test fun privacyStartupRecoversQueueAndNewRowsAreNotLostBehindRunningWork() = runBlocking {
        db.privacy().queueCleanup(PrivacyFileCleanup("synthetic-a"))
        work.enqueueUniquePeriodicWork("privacy-file-cleanup-catchup", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PrivacyCleanupWorker>(15, TimeUnit.MINUTES).build()).result.get()
        PrivacyCleanupWorker.observe(context, db, scope)
        until { active("privacy-file-cleanup").size == 1 }
        assertTrue(active("privacy-file-cleanup-catchup").isEmpty())
        db.privacy().queueCleanup(PrivacyFileCleanup("synthetic-b"))
        until { active("privacy-file-cleanup").size == 2 }
    }
    @Test fun emptyPrivacyQueueDoesNotScheduleAndDeletionFailureKeepsDurableRetry() = runBlocking {
        PrivacyCleanupWorker.reconcile(context, db)
        assertTrue(active("privacy-file-cleanup").isEmpty())
        val directory = File(files, "blocked").apply { mkdirs() }
        val child = File(directory, "child").apply { writeText("synthetic") }
        db.privacy().queueCleanup(PrivacyFileCleanup(directory.path))
        val actions = app.resound.privacy.RoomPrivacyActions(db, diagnostic = {})
        assertFalse(actions.retryFileCleanup())
        PrivacyCleanupWorker.reconcile(context, db)
        assertEquals(1, active("privacy-file-cleanup").size)
        child.delete()
        assertTrue(actions.retryFileCleanup())
        assertTrue(db.privacy().pendingCleanup().isEmpty())
    }
    @Test fun audioSweepFollowsEnabledFolderAudioAndCancelsLegacyWork() {
        val channel = PushChannel(1, PushKind.MarkdownFolder, "测试", true, emptySet(), PushFormat.Markdown, "",
            subscription = PushSubscription(audio = AudioPush.All))
        RecordingAudioSweepWorker.schedule(context, listOf(channel))
        assertEquals(15 * 60_000L, active("recording-audio-sweep").single().periodicityInfo!!.repeatIntervalMillis)
        RecordingAudioSweepWorker.schedule(context, listOf(channel.copy(enabled = false)))
        assertTrue(active("recording-audio-sweep").isEmpty())
        RecordingAudioSweepWorker.schedule(context, listOf(channel.copy(subscription = PushSubscription())))
        assertTrue(active("recording-audio-sweep").isEmpty())
        RecordingAudioSweepWorker.schedule(context, listOf(channel))
        assertEquals(1, active("recording-audio-sweep").size)
    }
    @Test fun watchdogCancelsEvenWhenStoppedWithoutRunningServiceAndReschedules() {
        val prefs = context.getSharedPreferences("watchdog174", 0)
        CaptureWatchdog.schedule(context)
        val listener = CaptureWatchdog.observe(context, prefs)
        assertTrue(active("capture-watchdog").isEmpty())
        prefs.edit().putBoolean("enabled", true).commit()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, active("capture-watchdog").size)
        prefs.edit().putBoolean("enabled", false).commit()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(active("capture-watchdog").isEmpty())
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
    @Test fun importCleanupStartupRecoversFilesAndPreservesOrdinaryAudioRetention() = runBlocking {
        ImportAudioCleanupWork.reconcile(context, db, original)
        assertTrue(active("import-audio-cleanup").isEmpty())
        val file = segment("mic")
        ImportAudioCleanupWork.observe(context, db, original, scope)
        until { active("import-audio-cleanup").isNotEmpty() }
        assertEquals(60 * 60_000L, active("import-audio-cleanup").single().periodicityInfo!!.repeatIntervalMillis)
        file.delete()
        ImportAudioCleanupWork.reconcile(context, db, original)
        assertTrue(active("import-audio-cleanup").isEmpty())
        File(context.filesDir, "import-segments").mkdirs()
        File(context.filesDir, "import-segments/orphan.ogg").writeText("synthetic")
        ImportAudioCleanupWork.reconcile(context, db, original)
        assertEquals(1, active("import-audio-cleanup").size)
    }
    @Test fun importWritesTriggerCleanupWithoutRestartAndReadingImportIsProtected() = runBlocking {
        segment("import", "Running")
        ImportAudioCleanupWork.reconcile(context, db, original)
        assertTrue(active("import-audio-cleanup").isEmpty())
        ImportAudioCleanupWork.observe(context, db, original, scope)
        db.transcribe().update(db.transcribe().segment(1).copy(status = "Failed", nextRetryAt = 100, attempts = 1))
        until { active("import-audio-cleanup").isNotEmpty() }
    }
}
