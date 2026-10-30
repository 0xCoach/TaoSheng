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
    val quietSchedule by lazy { AndroidQuietSchedule(store.prefs, arm = { AndroidQuietSchedule.arm(this, it) }, changed = CaptureService::recheck) }
    val segmentFiles by lazy { SegmentFiles(this, database.capture(), scope) }
    private lateinit var transcribeScheduler: TranscribeScheduler
    fun rescheduleDigest(changed: Boolean = false) {
        if (changed) transcribeScheduler.timingChanged() else transcribeScheduler.scheduleDigest()
    }
    fun enqueueTimedDigest(target: Long, expires: Long) = transcribeScheduler.enqueueTimed(target, expires)
    fun timedDigestValid(target: Long): Boolean {
        val timing = AppGraph.digestTiming
        val local = java.time.Instant.ofEpochMilli(target).atZone(java.time.ZoneId.systemDefault())
        return timing.mode.value == DigestMode.TimePoints && local.hour * 60 + local.minute in timing.timePoints.value
    }
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
        val crashes = app.resound.diagnostics.CrashRecords(this)
        crashes.installHandler()
        appDisplayName = appName
        if (getProcessName().endsWith(":local_asr")) return
        val asrSpeed = AndroidAsrSpeedSettings(getSharedPreferences("asr-speed", MODE_PRIVATE), scope)
        AppGraph.asrSpeed = asrSpeed
        // 课堂模式开关、到点结束、总开关变化：刷新常驻通知和下拉磁贴
        scope.launch {
            kotlinx.coroutines.flow.combine(asrSpeed.classMode, asrSpeed.separateSpeakers) { a, b -> a to b }.collect {
                app.resound.capture.CaptureService.refreshOngoing()
                app.resound.capture.ClassModeTileService.refresh(this@ResoundApp)
            }
        }
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) { asrSpeed.refresh() }
        }, android.content.IntentFilter(android.content.Intent.ACTION_TIME_CHANGED))
        app.resound.audio.OggGuard.init(this)
        app.resound.backup.BackupRestore.applyPending(this)
        // 仅回收上个进程遗留的转码/密钥测试临时文件；原录音在 files/segments。
        cacheDir.listFiles()?.filter { it.name.startsWith("asr-") && it.extension in setOf("pcm", "ogg") }
            ?.forEach { it.delete() }
        cacheDir.listFiles()?.filter { it.name.startsWith("todo-voice-") && it.extension == "wav" }
            ?.forEach { it.delete() }
        AppGraph.usage = app.resound.usage.RoomUsageRepository(database.memory(), database.transcribe(),
            getSharedPreferences("usage", MODE_PRIVATE))
        AppGraph.prompts = app.resound.prompts.LocalPromptTemplates(this)
        AppGraph.inputs = inputs
        AppGraph.playbackSkip = AndroidPlaybackSkipSettings(this)
        CaptureNotifications.channels(this)
        controller = AndroidCaptureController(this, store)
        AppGraph.capture = controller
        AppGraph.quietSchedule = quietSchedule
        AppGraph.voiceInput = AndroidVoiceInputCapture(this, database, scope)
        val settings = AndroidTranscribeSettings(this)
        val scheduler = TranscribeScheduler(this, settings)
        transcribeScheduler = scheduler
        segmentFiles.onSaved = { scheduler.segmentSaved(database.transcribe().pendingMillis()) }
        val hotwords = AndroidHotwordSettings(this, database.voiceprint(), scope)
        AppGraph.hotwords = hotwords
        AppGraph.importer = app.resound.importer.AndroidAudioImporter(this, scope)
        // 声纹标注后只重跑声纹；lateinit 的仓库在 run() 被调用时早已赋值
        val voiceprintRerun = app.resound.voiceprint.VoiceprintRerun(scope, { voiceprintRepository.run() }, scheduler::runNow)
        val pcmReader = AsrAudioReader(cacheDir)
        val sharedPcm = SharedPcmFiles(cacheDir, pcmReader::decodeForExport)
        voiceprintRepository = LocalVoiceprintRepository(database, scope, { file -> sharedPcm.read(file, pcmReader::readPcm) },
            { CampPlusEmbedding(this) }, voiceprintRerun::request)
        AppGraph.voiceprint = voiceprintRepository
        val envSound = app.resound.envsound.LocalEnvSoundRepository.create(this, database, scope,
            voiceprintRepository::recognizesMeBeforeTranscribe, scheduler::runNow, sharedPcm)
        AppGraph.envSound = envSound
        val originalAudio = LocalOriginalAudio(database.transcribe(), getSharedPreferences("transcribe", MODE_PRIVATE), scope,
            guard = { block -> voiceprintRepository.withAudioCleanup(block) },
            playback = PlaybackCaptureHold(scope, CaptureService::playbackHold))
        AppGraph.originalAudio = originalAudio
        voiceprintRepository.cleanupOriginalAudio = originalAudio::cleanup
        val privacyActions = app.resound.privacy.RoomPrivacyActions(database,
            discardRecording = CaptureService::discardCurrent,
            recentFiles = { from, until, block -> segmentFiles.withRecentDeletion(from, until, block) },
            voiceprintGuard = { block -> voiceprintRepository.withPrivacyDeletion(block) },
            removeFeatures = voiceprintRepository::removeFeatures)
        AppGraph.privacy = privacyActions
        AppGraph.segmentDeleter = privacyActions
        AppGraph.audioExporter = LocalAudioExporter(cacheDir, database.transcribe(),
            ExportSpeeds(getSharedPreferences("export-speeds", MODE_PRIVATE))) { PipelineTimes.record(this, it) }
        AppGraph.audioExporter.cleanup()
        AppGraph.exportJobs = AndroidExportJobs(this, AppGraph.audioExporter, scope)
        val transcripts = RoomTranscriptRepository(this, database.transcribe(), settings, scheduler, scope)
        val asrChooser = app.resound.redo.AndroidAsrChooser(this, settings, scope) { AppGraph.usage.price.value.asrPerHour }
        val sourceNetwork: (TranscribeSource) -> Boolean = { source ->
            if (source == TranscribeSource.Local) true else {
                val connectivity = getSystemService(ConnectivityManager::class.java)
                val network = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                    (!settings.wifiOnly.value || network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            }
        }
        transcribeRunner = TranscribeRunner(database.transcribe(), ConfiguredAsrProvider(this, settings, hotwords::merged),
            settings::credential, transcripts::updateState, deferAudioCleanup = true,
            sourceReady = settings::ready, sourceKey = settings::credential, sourceNetwork = sourceNetwork, recordSpeed = asrChooser::record,
            recordTiming = { PipelineTimes.recordSegment(this, it) },
            defaultSource = { settings.source.value }, separateSpeakers = asrSpeed::effectiveSeparateSpeakers,
            beforeTranscribe = envSound::beforeTranscribe,
            beginBatch = { envSound.beginBatch() }, endBatch = { envSound.endBatch() }, sharedPcm = sharedPcm,
            afterSegment = { segment ->
                if (database.transcribe().segment(segment.id).let { it.separateSpeakers != false || (it.importChannel != null && it.speakerCount != 0) }) {
                    PipelineProgress.stage(PipelineProgress.Stage.Voiceprint)
                    try { voiceprintRepository.runSegment(segment.id) }
                    finally { PipelineProgress.stage(PipelineProgress.Stage.Transcribe) }
                }
            },
            afterImport = { voiceprintRepository.cleanupAudio() },
            updateProgress = { total, done -> transcripts.updateProgress(total, done); if (total > 0) PipelineProgress.progress(done, total) }, identity = settings::identity, ready = { settings.ready.value }, networkAllowed = {
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
        }, unseparated = database.transcribe()::unseparated, isMedia = database.envSound()::media, voiceprintReady = voiceprintRepository::isReady, hotwords = hotwords::merged, afterDigest = { manual -> app.resound.importer.ImportTodoMerge.all(database); skillScheduler.afterDigest(manual) })
        AppGraph.importKinds = app.resound.importer.AndroidImportKinds.create(this, database,
            AppGraph.importer as app.resound.importer.AndroidAudioImporter, scope, digestSettings, llm,
            app.resound.importer.ImportSpeakers(database, pcmReader::readPcm, { CampPlusEmbedding(this) }),
            idle = { block -> transcribeRunner.withIdleAudio { voiceprintRepository.withStableResults { block() } } },
            transcribe = scheduler::runNow, ready = { scheduler.runDigestNow(); voiceprintRepository.cleanupAudio() })
        val redoEngine = app.resound.redo.createRedoEngine(this, database, asrChooser, settings, digestSettings, llm,
            hotwords::merged, { AppGraph.prompts.text("digest") }, { ids -> voiceprintRepository.recognizeRedo(ids) }, sourceNetwork)
        AppGraph.redo = app.resound.redo.AndroidRedoService(this, database, asrChooser, redoEngine, scope,
            { app.resound.redo.llmOptions(digestSettings) }, { digestSettings.presetId.value },
            idle = { block -> transcribeRunner.withIdleAudio { digestRunner.withIdle { block() } } })
        AppGraph.digestProgress = digestRunner.progress
        val appointmentSettings = AndroidAppointmentSettings(this)
        AppGraph.memory = RoomMemoryRepository(database.memory(), digestRunner, {
            // 手动：等着重试的转写段一并马上重试，再整理
            scope.launch { database.transcribe().retryFailed(); scheduler.runDigestNow() }
        }, scope, appointmentSettings, { app.resound.search.EmbeddingScheduler.enqueue(this, continuation = true) })
        AppGraph.rhythm = RoomRhythmStats(database.memory())
        AppGraph.digestSettings = digestSettings
        AppGraph.llmProviders = digestSettings.providers
        AppGraph.llmBalance = app.resound.usage.LlmBalanceChecker(getSharedPreferences("llm-balance", MODE_PRIVATE), digestSettings.providers, { b, line ->
            app.resound.push.LocalNotifier(this, app.resound.push.LocalNotifier.BALANCE_CHANNEL).send(
                if (b.available) "整理模型余额不足 ¥%.2f".format(b.total) else "整理模型余额已用完",
                "低于提醒线 ¥%.0f，余额用完后整理会失败，请及时充值。".format(line), null)
        })
        AppGraph.digestTiming = digestSettings.timing
        scheduler.digestTiming = digestSettings.timing
        digestSettings.timing.onChanged = scheduler::timingChanged
        // 独立 20 秒 HTTP 上限也覆盖响应体读取；不改变日常整理的长请求上限。
        val todoLlm = LlmClient(okhttp3.OkHttpClient.Builder()
            .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build())
        AppGraph.manualTodos = RoomManualTodos(database.memory(), scope,
            { digestSettings.skillCredentials(null) }, todoLlm::chatJson, { reply ->
                database.memory().addUsage(java.time.LocalDate.now().toString(), reply.promptTokens,
                    reply.completionTokens, app.resound.usage.UsagePurpose.Digest)
            })
        AppGraph.todoVoice = AndroidTodoVoiceInput(controller, scope, cacheDir,
            ConfiguredAsrProvider(this, settings, hotwords::merged), settings::credential)
        scheduler.digestMinutes = { digestSettings.intervalMinutes.value }
        digestSettings.onIntervalChanged = { scheduler.scheduleDigest() }
        val reviewScheduler = app.resound.review.ReviewScheduler(this)
        lateinit var reviews: app.resound.review.RoomReviewRepository
        val pushChannels = app.resound.push.AndroidPushChannels(this) { reviews.recent().first().firstOrNull() }
        AppGraph.push = pushChannels
        reviews = app.resound.review.RoomReviewRepository(database.review(), database.memory(),
            getSharedPreferences("review", MODE_PRIVATE), scope,
            { digestSettings.skillCredentials(null) }, llm::chatJson, { AppGraph.prompts.text("review") }, appName,
            app.resound.push.ChannelNotifier(app.resound.push.LocalNotifier(this), pushChannels), { reviewScheduler.schedule(it, changed = true) },
            dailyMemory = pushChannels::dispatchDailyMemory,
            beforeReview = { app.resound.review.ReviewPreparation.run(this) },
            work = androidx.work.WorkManager.getInstance(this))
        val skillFiles = app.resound.skills.SkillFiles(this)
        digestSettings.providers.beforeDelete = { id -> skillFiles.resetProviders(setOf(id)) }
        skillFiles.resetProviders(skillFiles.skills.value.mapNotNull { it.presetId }.toSet() - digestSettings.presets.map { it.id }.toSet())
        val skillRepository = app.resound.skills.LocalSkillRepository(
            skillFiles, database.skills(), database.memory(), scope,
            digestSettings::skillCredentials, llm::chatJson, hotwords::merged,
            app.resound.push.ChannelNotifier(app.resound.push.LocalNotifier(this), pushChannels), skillScheduler::schedule,
            generation = app.resound.skills.SkillGeneration(androidx.work.WorkManager.getInstance(this), database.skills(), scope),
            beforeWeeklyReview = { app.resound.review.ReviewPreparation.run(this) })
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
        val updater = app.resound.update.createUpdater(this, backupRepository)
        AppGraph.updater = updater
        AppGraph.problemReports = app.resound.diagnostics.LocalProblemReports(this, crashes, AppGraph.stats, updater)
        app.resound.update.UpdateWork.schedule(this, updater.autoCheck.value, updater.previewChannel.value)
        registerActivityLifecycleCallbacks(app.resound.update.UpdateForegroundChecks(updater, scope))
        pushChannels.attachSubscriptions(this, database, backupRepository, scope)
        app.resound.recordings.attachRecordingArchive(this, database, pushChannels, scope)
        scope.launch { transcribeRunner.recoverOnStartup(); transcribeRunner.withIdleAudio { voiceprintRepository.cleanupAudio() } }
        ImportAudioCleanupWork.schedule(this)
        if (store.prefs.getBoolean("enabled", false)) CaptureWatchdog.schedule(this)
    }
}
