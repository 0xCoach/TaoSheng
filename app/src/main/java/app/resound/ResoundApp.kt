package app.resound

import app.resound.push.attachSubscriptions

import android.app.Application
import app.resound.capture.*
import app.resound.data.CaptureDatabase
import app.resound.transcribe.*
import app.resound.digest.*
import app.resound.voiceprint.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class ResoundApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val inputs by lazy { app.resound.audio.AndroidInputDevices(this) }
    val database by lazy { CaptureDatabase.open(this) }
    val store by lazy { CaptureStore(this, database.capture()) }
    val segmentFiles by lazy { SegmentFiles(this, database.capture(), scope) }
    private lateinit var transcribeScheduler: TranscribeScheduler
    fun runTranscriptionNow() = transcribeScheduler.runNow()
    lateinit var controller: AndroidCaptureController
        private set
    lateinit var transcribeRunner: TranscribeRunner
        private set
    lateinit var digestRunner: DigestRunner
        private set
    lateinit var voiceprintRepository: LocalVoiceprintRepository
        private set
    override fun onCreate() {
        super.onCreate()
        if (getProcessName().endsWith(":local_asr")) return
        app.resound.backup.BackupRestore.applyPending(this)
        // 仅回收上个进程遗留的转码/密钥测试临时文件；原录音在 files/segments。
        cacheDir.listFiles()?.filter { it.name.startsWith("asr-") && it.extension in setOf("pcm", "ogg") }
            ?.forEach { it.delete() }
        AppGraph.usage = app.resound.usage.RoomUsageRepository(database.memory(), database.transcribe(),
            getSharedPreferences("usage", MODE_PRIVATE))
        AppGraph.prompts = app.resound.prompts.LocalPromptTemplates(this)
        AppGraph.inputs = inputs
        AppGraph.playbackSkip = AndroidPlaybackSkipSettings(this)
        CaptureNotifications.channels(this)
        controller = AndroidCaptureController(this, store)
        AppGraph.capture = controller
        val settings = AndroidTranscribeSettings(this)
        val scheduler = TranscribeScheduler(this, settings)
        transcribeScheduler = scheduler
        val hotwords = AndroidHotwordSettings(this, database.voiceprint(), scope)
        AppGraph.hotwords = hotwords
        AppGraph.importer = app.resound.importer.AndroidAudioImporter(this, scope)
        voiceprintRepository = LocalVoiceprintRepository(database, scope, AsrAudioReader(cacheDir)::readPcm,
            { CampPlusEmbedding(this) }, scheduler::runNow)
        AppGraph.voiceprint = voiceprintRepository
        AppGraph.privacy = app.resound.privacy.RoomPrivacyActions(database,
            discardRecording = CaptureService::discardCurrent,
            recentFiles = { from, until, block -> segmentFiles.withRecentDeletion(from, until, block) },
            voiceprintGuard = { block -> voiceprintRepository.withPrivacyDeletion(block) },
            removeFeatures = voiceprintRepository::removeFeatures)
        val transcripts = RoomTranscriptRepository(this, database.transcribe(), settings, scheduler, scope)
        transcribeRunner = TranscribeRunner(database.transcribe(), ConfiguredAsrProvider(this, settings, hotwords::merged),
            settings::credential, transcripts::updateState, deferAudioCleanup = true,
            afterImport = { voiceprintRepository.run(); voiceprintRepository.cleanupAudio() },
            updateProgress = transcripts::updateProgress, identity = settings::identity, ready = { settings.ready.value }, networkAllowed = {
                if (settings.source.value == TranscribeSource.Local) true else {
                val connectivity = getSystemService(ConnectivityManager::class.java)
                val network = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                    (!settings.wifiOnly.value || network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                }
            })
        settings.testKey = { key ->
            val file = File.createTempFile("asr-test-", ".ogg", cacheDir)
            try {
                assets.open("asr-test.ogg").use { input -> file.outputStream().use { input.copyTo(it) } }
                transcribeRunner.test(AsrAudio(file, "ogg", 3007), key)
            } finally { file.delete() }
        }
        settings.onChanged = { scheduler.settingsChanged(scope) }
        val llm = LlmClient()
        val digestSettings = AndroidDigestSettings(this, llm) { reply ->
            database.memory().addUsage(java.time.LocalDate.now().toString(), reply.promptTokens,
                reply.completionTokens, app.resound.usage.UsagePurpose.Other)
        }
        val skillScheduler = app.resound.skills.SkillScheduler(this)
        digestRunner = DigestRunner(this, database.memory(), llm, digestSettings, { AppGraph.prompts.text("digest") }, networkAllowed = {
            val connectivity = getSystemService(ConnectivityManager::class.java)
            connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }, voiceprintReady = voiceprintRepository::isReady, hotwords = hotwords::merged, afterDigest = { skillScheduler.afterDigest() })
        AppGraph.digestProgress = digestRunner.progress
        val appointmentSettings = AndroidAppointmentSettings(this)
        AppGraph.memory = RoomMemoryRepository(database.memory(), digestRunner, {
            // 手动：等着重试的转写段一并马上重试，再整理
            digestRunner.forceOnce = true
            transcripts.retryFailed()
        }, scope, appointmentSettings)
        AppGraph.digestSettings = digestSettings
        val reviewScheduler = app.resound.review.ReviewScheduler(this)
        lateinit var reviews: app.resound.review.RoomReviewRepository
        val pushChannels = app.resound.push.AndroidPushChannels(this) { reviews.recent().first().firstOrNull() }
        AppGraph.push = pushChannels
        reviews = app.resound.review.RoomReviewRepository(database.review(), database.memory(),
            getSharedPreferences("review", MODE_PRIVATE), scope,
            { digestSettings.preset to digestSettings.readKey() }, llm::chatJson, { AppGraph.prompts.text("review") }, appName,
            app.resound.push.ChannelNotifier(app.resound.push.LocalNotifier(this), pushChannels), { reviewScheduler.schedule(it, changed = true) },
            dailyMemory = pushChannels::dispatchDailyMemory)
        val skillRepository = app.resound.skills.LocalSkillRepository(
            app.resound.skills.SkillFiles(this), database.skills(), database.memory(), scope,
            digestSettings::skillCredentials, llm::chatJson, hotwords::merged,
            app.resound.push.ChannelNotifier(app.resound.push.LocalNotifier(this), pushChannels), skillScheduler::schedule)
        AppGraph.skills = skillRepository
        scope.launch { skillRepository.reschedule() }
        AppGraph.review = reviews
        AppGraph.diary = app.resound.diary.LocalDiaryRepository(this, AppGraph.memory, reviews, database.memory(),
            cards = { date -> skillRepository.cards(date).first() })
        val dailyMemory = app.resound.push.DailyMemorySource(this, AppGraph.memory, reviews, database.memory(), database.review(), database.skills())
        pushChannels.memoryDocument = dailyMemory::load
        reviewScheduler.schedule(reviews.state.value.dailyAtMinutes)
        AppGraph.ask = app.resound.ask.createAskRepository(this, database, llm, digestSettings, scope)
        AppGraph.transcripts = transcripts
        AppGraph.transcribeSettings = settings
        AppGraph.stats = RoomCaptureStatsRepository(this, database.capture(), transcripts::diagnostics)
        AppGraph.setup = AndroidSetupGuide(this)
        segmentFiles // 启动串行编码队列，重试完整暂存片段。
        scheduler.schedule()
        app.resound.search.EmbeddingScheduler.start(this)
        val backupRepository = app.resound.backup.AndroidBackupRepository(this, database, diary = AppGraph.diary)
        AppGraph.backup = backupRepository
        pushChannels.attachSubscriptions(this, database, backupRepository, scope)
        scope.launch { transcribeRunner.recoverOnStartup(); transcribeRunner.withIdleAudio { voiceprintRepository.cleanupAudio() } }
        ImportAudioCleanupWork.schedule(this)
        if (store.prefs.getBoolean("enabled", false)) CaptureWatchdog.schedule(this)
    }
}
