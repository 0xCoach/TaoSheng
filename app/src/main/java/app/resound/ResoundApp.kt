package app.resound

import app.resound.data.open
import app.resound.data.readSql

import app.resound.data.retryFailed

import app.resound.push.attachSubscriptions
import app.resound.push.attachFootprint

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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class ResoundApp : Application() {
    private lateinit var watchdogPreferences: android.content.SharedPreferences.OnSharedPreferenceChangeListener
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val pipelineDiagnostics by lazy { app.resound.transcribe.PipelineDiagnostics(this) }
    internal val pipelineScheduler by lazy { app.resound.transcribe.PipelineScheduler(this) }
    val inputs by lazy { app.resound.audio.AndroidInputDevices(this) }
    internal lateinit var cardDevices: app.resound.devices.android.DeviceInputs
    val database by lazy { CaptureDatabase.open(this) }
    val store by lazy { CaptureStore(this, database.capture()) }
    val quietSchedule by lazy { AndroidQuietSchedule(store.prefs, arm = { AndroidQuietSchedule.arm(this, it) }, changed = CaptureService::recheck) }
    /** R4 场景感知：决策/隐私暂停/场景会话；决策输出仍走既有暂停通道 */
    val scene by lazy {
        app.resound.capture.AndroidScene(this, store.prefs,
            sessionDao = { database.sceneSessions() }, captureDao = { database.capture() },
            ideas = { if (AppGraph.isIdeasReady) AppGraph.ideas else null }, battery = { store.battery() })
    }
    val segmentFiles by lazy { SegmentFiles(this, database.capture(), scope) }
    internal lateinit var topics: app.resound.topic.TopicFeature
    private lateinit var transcribeScheduler: TranscribeScheduler
    fun rescheduleDigest(changed: Boolean = false) {
        if (changed) transcribeScheduler.timingChanged() else transcribeScheduler.scheduleDigest()
    }
    fun recoverTimedDigest() = transcribeScheduler.recoverTimed()
    suspend fun resumeWaitingTranscriptionBatch(action: String?) {
        when (action) {
            android.content.Intent.ACTION_SCREEN_ON -> pipelineScheduler.trigger("亮屏")
            android.content.Intent.ACTION_POWER_CONNECTED -> pipelineScheduler.trigger("接电")
        }
        transcribeScheduler.resumeWaitingBatch(action)
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
        AppGraph.platform = app.resound.platform.PlatformServices(
            now = app.resound.platform.SystemNow,
            zone = app.resound.platform.SystemZone,
            logger = app.resound.platform.AndroidLogger,
            keyValueStore = app.resound.platform.PrefsKeyValueStore(getSharedPreferences("platform", MODE_PRIVATE)),
            files = app.resound.platform.LocalFileStore(app.resound.platform.AndroidDispatchers),
            dispatchers = app.resound.platform.AndroidDispatchers,
        )
        val crashes = app.resound.diagnostics.CrashRecords(this)
        crashes.installHandler()
        appDisplayName = appName
        if (getProcessName().endsWith(":local_asr")) return
        registerActivityLifecycleCallbacks(BackgroundTranscribeDiagnostics)
        app.resound.diagnostics.RenderHeartbeat.install(this) // R4.4 黑屏冻结取证
        app.resound.backup.BackupRestore.applyPending(this)
        AppGraph.quickPrefs = app.resound.quick.AndroidQuickPrefs(this)
        // AUTH：门禁先于一切受保护组件装配——服务/Worker/MCP 都可能先于界面启动
        val authStore = app.resound.auth.AuthStore(this)
        runCatching { assets.open("auth-license-public-key.hex").use { it.readBytes() } }
            .onSuccess { app.resound.auth.LicenseVerifier.install(it) }
        authStore.refreshStatus()
        val authApi = app.resound.auth.AuthApi({ authStore.serverUrl })
        AppGraph.auth = app.resound.auth.AuthGate(authStore, authApi)
        AppGraph.officialAi = app.resound.auth.OfficialAi(this, AppGraph.auth, authStore)
        AppGraph.membership = app.resound.auth.MembershipState(this, authStore, AppGraph.auth)
        // 官方 AI 桥：服务端点数制网关；错误全部 terminal——点数不足/需同意/服务异常不自动切 BYOK
        val officialBridge: app.resound.digest.OfficialBridge = { kind, system, user ->
            when (val out = AppGraph.officialAi.generate(kind, system, user)) {
                is app.resound.auth.OfficialAi.GenOutcome.Ok ->
                    app.resound.digest.LlmReply(out.content, 0, 0, actualModel = "official")
                is app.resound.auth.OfficialAi.GenOutcome.Denied -> throw when (out.code) {
                    "CONSENT_REQUIRED" -> app.resound.digest.LlmException(app.resound.digest.LlmError.Auth, detail = "请先在设置里同意使用声声不息官方 AI", terminal = true)
                    "QUOTA_EXHAUSTED" -> app.resound.digest.LlmException(app.resound.digest.LlmError.Quota, detail = "官方 AI 点数已用完，请等下周期或升级 Pro", terminal = true)
                    "RATE_LIMITED" -> app.resound.digest.LlmException(app.resound.digest.LlmError.RateLimit, detail = "官方 AI 请求过于频繁，请稍后再试", terminal = true)
                    "REQUEST_TOO_LARGE" -> app.resound.digest.LlmException(app.resound.digest.LlmError.Format, detail = "内容超出官方 AI 单次上限", terminal = true)
                    "TASK_FAILED" -> app.resound.digest.LlmException(app.resound.digest.LlmError.Format, detail = "官方 AI 处理失败（未扣点）", terminal = true)
                    "UNKNOWN_PROVIDER_OUTCOME" -> app.resound.digest.LlmException(app.resound.digest.LlmError.Network, detail = "官方服务响应超时，结果待确认（未扣点）", terminal = true)
                    "PROVIDER_UNAVAILABLE" -> app.resound.digest.LlmException(app.resound.digest.LlmError.Server, detail = "官方 AI 服务暂不可用", terminal = true)
                    else -> app.resound.digest.LlmException(app.resound.digest.LlmError.Server, detail = "官方 AI 出错（${out.code}）", terminal = true)
                }
                is app.resound.auth.OfficialAi.GenOutcome.Network ->
                    throw app.resound.digest.LlmException(app.resound.digest.LlmError.Network, detail = "网络连接失败", terminal = true)
                app.resound.auth.OfficialAi.GenOutcome.NeedLogin ->
                    throw app.resound.digest.LlmException(app.resound.digest.LlmError.Auth, detail = "请登录后使用声声不息官方 AI", terminal = true)
            }
        }
        // 老装升级不锁死：本机是否已有历史数据——同步用文件存在性先判（不依赖异步 DB 查询，
        // 否则首个 require() 抢在探测前会把老用户判成全新装而拒绝），异步再复核补正。
        if (authStore.boundAccountId == null && !authStore.hasLocalData) {
            authStore.hasLocalData = runCatching {
                java.io.File(filesDir, "segments").list()?.isNotEmpty() == true ||
                        getDatabasePath("capture.db").exists()
            }.getOrDefault(true)
            scope.launch { if (database.capture().latestSpeechEndAt() != null) authStore.hasLocalData = true }
        }
        val asrSpeed = AndroidAsrSpeedSettings(getSharedPreferences("asr-speed", MODE_PRIVATE), scope).apply { topicTraining = database.topics()::trainingAt }
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
        // AUTH：登出/会话失效 → 立即停止录音（controller.stop 只在服务活着时发 STOP，安全收尾）
        AppGraph.auth.onSessionCleared = { controller.stop() }
        // AUTH：服务端判失效（refresh 401-403）→ 系统通知栏主动告知；点开 App 登录面板已在等（loginRequested 已置位）。
        AppGraph.auth.onSessionExpired = { reason ->
            scope.launch {
                runCatching {
                    app.resound.push.LocalNotifier(this@ResoundApp, app.resound.push.LocalNotifier.AUTH_CHANNEL)
                        .send("登录授权已失效", "$reason。录音与个人数据已暂停，重新登录后恢复。", null)
                }
            }
        }
        // 敲敲手势：开设置后监听加速度计；敲 N 下=录中暂停/停中恢复/未录启动
        app.resound.capture.KnockGestureController(this, store).start()
        AppGraph.quietSchedule = quietSchedule
        AppGraph.scene = scene
        AppGraph.voiceInput = AndroidVoiceInputCapture(this, database, scope)
        val settings = AndroidTranscribeSettings(this)
        val scheduler = TranscribeScheduler(this, settings)
        transcribeScheduler = scheduler
        segmentFiles.onSaved = {
            // 专题结束时可能还没自然分片/编码完；最后一段落盘后也立即转写，不等定时批次。
            if (database.topics().pendingAudio() > 0) scheduler.runNow()
            else scheduler.segmentSaved(database.transcribe().pendingMillis())
        }
        val hotwords = AndroidHotwordSettings(this, database.voiceprint(), scope)
        AppGraph.hotwords = hotwords
        hotwords.topicWords = { if (AppGraph.isTopicsReady) app.resound.topic.TopicPolicy.words(AppGraph.topics.current.value?.takeIf { app.resound.topic.TopicPolicy.activeAt(it, System.currentTimeMillis()) }?.name) else emptyList() }
        AppGraph.importer = app.resound.importer.AndroidAudioImporter(this, scope, fingerprints = database.folderImports())
        // 声纹标注后只重跑声纹；lateinit 的仓库在 run() 被调用时早已赋值
        val voiceprintRerun = app.resound.voiceprint.VoiceprintRerun(scope, { voiceprintRepository.run() }, scheduler::runNow, AppGraph.platform.dispatchers)
        val pcmReader = AsrAudioReader(cacheDir)
        val sharedPcm = SharedPcmFiles(cacheDir, pcmReader::decodeForExport)
        voiceprintRepository = LocalVoiceprintRepository(database, scope, { file -> sharedPcm.read(file, pcmReader::readPcm) },
            { CampPlusEmbedding(this) }, voiceprintRerun::request,
            sampleAudioStore = SampleAudioStore(SampleAudioStore.directory(this)))
        AppGraph.voiceprint = voiceprintRepository
        val envSound = app.resound.envsound.LocalEnvSoundRepository.create(this, database, scope,
            voiceprintRepository::recognizesMeBeforeTranscribe, scheduler::runNow, sharedPcm, AppGraph.platform.zone, AppGraph.platform.now::now, AppGraph.platform.dispatchers.io)
        AppGraph.envSound = envSound
        AppGraph.location = app.resound.location.LocationRepository.create(this, database, scope)
        AppGraph.situation = app.resound.context.LocalSituationLookup.create(database, AppGraph.location, scope, AppGraph.platform.zone, AppGraph.platform.dispatchers.io)
        val originalAudio = LocalOriginalAudio(database.transcribe(), getSharedPreferences("transcribe", MODE_PRIVATE), scope,
            guard = { block -> voiceprintRepository.withAudioCleanup(block) },
            playback = PlaybackCaptureHold(scope, CaptureService::playbackHold))
        AppGraph.originalAudio = originalAudio
        voiceprintRepository.cleanupOriginalAudio = originalAudio::cleanup
        val privacyActions = app.resound.privacy.RoomPrivacyActions(database,
            discardRecording = CaptureService::discardCurrent,
            recentFiles = { from, until, block -> segmentFiles.withRecentDeletion(from, until, block) },
            voiceprintGuard = { block -> voiceprintRepository.withPrivacyDeletion(block) },
            removeFeatures = voiceprintRepository::removeFeatures,
            scheduleCleanup = { app.resound.privacy.PrivacyCleanupWorker.enqueue(this) })
        app.resound.privacy.PrivacyCleanupWorker.start(this)
        AppGraph.privacy = privacyActions
        AppGraph.segmentDeleter = privacyActions
        AppGraph.audioExporter = LocalAudioExporter(cacheDir, database.transcribe(),
            ExportSpeeds(getSharedPreferences("export-speeds", MODE_PRIVATE))) { PipelineTimes.record(this, it) }
        AppGraph.audioExporter.cleanup()
        AppGraph.exportJobs = AndroidExportJobs(this, AppGraph.audioExporter, scope)
        val transcripts = RoomTranscriptRepository(this, database.transcribe(), settings, scheduler, scope, AppGraph.platform.zone, AppGraph.platform.now, AppGraph.platform.dispatchers.io)
        val asrChooser = app.resound.redo.AndroidAsrChooser(this, settings, scope) { AppGraph.usage.price.value.asrPerHour }
        val sourceNetwork: (TranscribeSource) -> Boolean = { source ->
            if (source == TranscribeSource.Local) true else {
                val connectivity = getSystemService(ConnectivityManager::class.java)
                val network = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                    (!settings.wifiOnly.value || network.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            }
        }
        // 专题已暂停：录音停，转写也一起冻结（专题继续/结束自动恢复）
        val topicPaused = { AppGraph.isTopicsReady && AppGraph.topics.current.value?.status == app.resound.topic.TopicStatus.Paused }
        transcribeRunner = TranscribeRunner(database.transcribe(), ConfiguredAsrProvider(this, settings, hotwords::merged),
            settings::credential, transcripts::updateState, deferAudioCleanup = true,
            sourceReady = { src -> settings.ready(src) && AppGraph.membership.allowTranscribe() && !topicPaused() }, sourceKey = settings::credential, sourceNetwork = sourceNetwork, recordSpeed = asrChooser::record,
            recordTiming = { PipelineTimes.recordSegment(this, it) },
            defaultSource = { settings.source.value }, separateSpeakers = asrSpeed::effectiveSeparateSpeakers, topicTraining = database.topics()::trainingAt,
            beforeTranscribe = envSound::beforeTranscribe,
            beginBatch = { envSound.beginBatch() }, endBatch = { envSound.endBatch() }, sharedPcm = sharedPcm,
            afterSegment = { segment ->
                // 成功转写记账（片段 id 幂等；断网持久化，联网去重上报）
                AppGraph.membership.recordSuccess(segment.id, (segment.endAt - segment.startAt).coerceAtLeast(0))
                scope.launch { runCatching { AppGraph.membership.flush() } }
                if (database.transcribe().segment(segment.id).let { it.separateSpeakers != false || (it.importChannel != null && it.speakerCount != 0) }) {
                    PipelineProgress.stage(PipelineProgress.Stage.Voiceprint)
                    try { voiceprintRepository.runSegment(segment.id) }
                    finally { PipelineProgress.stage(PipelineProgress.Stage.Transcribe) }
                }
            },
            afterImport = { voiceprintRepository.cleanupAudio() },
            afterTest = { millis -> AppGraph.membership.recordUsage("test:${java.util.UUID.randomUUID()}", millis) },
            updateProgress = { total, done -> transcripts.updateProgress(total, done); if (total > 0) PipelineProgress.progress(done, total) }, identity = settings::identity, ready = { settings.ready.value && AppGraph.membership.allowTranscribe() && !topicPaused() }, networkAllowed = {
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
        val serviceHeaders = app.resound.digest.ServiceHeaders(this)
        val llm = LlmClient(requestHeaders = serviceHeaders::forPreset, diagnostics = app.resound.diagnostics.LlmDiagnostics(this), official = officialBridge)
        val digestSettings = AndroidDigestSettings(this, llm) { reply ->
            database.memory().addUsage(java.time.LocalDate.now().toString(), reply.promptTokens,
                reply.completionTokens, app.resound.usage.UsagePurpose.Other)
        }
        AppGraph.llmFailover = digestSettings.failover
        digestSettings.providers.officialReady = { AppGraph.officialAi.ready() }
        AppGraph.officialAi.onStatusChanged = { digestSettings.providers.refreshOfficial() }
        // 登录成功即刷新官方 AI 状态：已同意用户本轮就能自动选中官方（否则要等下次启动）。
        AppGraph.auth.onAuthSuccess = { scope.launch { AppGraph.officialAi.refreshStatus() } }
        // 登出/失效：重发布服务商列表，官方条目回到「登录授权后可用」。
        AppGraph.auth.onSessionCleared = { controller.stop(); digestSettings.providers.refreshOfficial() }
        scope.launch { runCatching { AppGraph.officialAi.refreshStatus() } }
        val (things, thingExtraction) = app.resound.things.createThings(this, database, scope, digestSettings) { AppGraph.prompts.text("things") }
        AppGraph.things = things
        val skillScheduler = app.resound.skills.SkillScheduler(this)
        digestRunner = DigestRunner(this, database.memory(), llm, digestSettings, { AppGraph.prompts.text("digest") }, situation = AppGraph.situation, thingExtraction = thingExtraction, networkAllowed = {
            val connectivity = getSystemService(ConnectivityManager::class.java)
            connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }, unseparated = database.transcribe()::unseparated, isMedia = database.envSound()::media, voiceprintReady = voiceprintRepository::isReady, hotwords = hotwords::merged, afterDigest = { manual ->
            app.resound.importer.ImportTodoMerge.all(database); skillScheduler.afterDigest(manual)
            // 整理出新事件后立刻同步下游记忆，不等下次启动或手动“同步提取”。
            AppGraph.ideas.syncFromDigest { if (AppGraph.isAssistantReady) AppGraph.workflowEngine.emit(it) }
            AppGraph.decisionRepo.syncFromDigest()
            AppGraph.followupEngine.scan()
            AppGraph.workflowEngine.emit(app.resound.rules.RuleEvent.DigestDone.key) })
        AppGraph.quietDigest = app.resound.digest.QuietDigestWatcher(this, scope, database, scheduler, digestRunner).also { it.start() }
        AppGraph.importKinds = app.resound.importer.AndroidImportKinds.create(this, database,
            AppGraph.importer as app.resound.importer.AndroidAudioImporter, scope, digestSettings, llm,
            app.resound.importer.ImportSpeakers(database, pcmReader::readPcm, { CampPlusEmbedding(this) }),
            idle = { block -> transcribeRunner.withIdleAudio { voiceprintRepository.withStableResults { block() } } },
            transcribe = scheduler::runNow, ready = { scheduler.runDigestNow(); voiceprintRepository.cleanupAudio() })
        topics = app.resound.topic.TopicFeature.create(this, database, digestSettings, llm, hotwords::merged)
        AppGraph.topics = topics.repository
        // 专题暂停 = 录音与转写一起冻结：状态一变就让录音服务重新核对指令（恢复/结束/到期自动解冻）
        scope.launch {
            AppGraph.topics.current.map { it?.status }.distinctUntilChanged()
                .collect { CaptureService.recheck() }
        }
        val redoEngine = app.resound.redo.createRedoEngine(this, database, asrChooser, settings, digestSettings, llm,
            hotwords::merged, { AppGraph.prompts.text("digest") }, { AppGraph.prompts.text("merge") },
            { ids -> voiceprintRepository.recognizeRedo(ids) }, sourceNetwork, situation = AppGraph.situation)
        AppGraph.redo = app.resound.redo.AndroidRedoService(this, database, asrChooser, redoEngine, scope,
            { app.resound.redo.llmOptions(digestSettings) }, { digestSettings.presetId.value },
            idle = { block -> transcribeRunner.withIdleAudio { digestRunner.withIdle { block() } } })
        AppGraph.digestProgress = digestRunner.progress
        val appointmentSettings = AndroidAppointmentSettings(this)
        AppGraph.memory = RoomMemoryRepository(database.memory(), digestRunner, {
            // 手动：等着重试的转写段一并马上重试，再整理
            scope.launch { database.transcribe().retryFailed(); scheduler.runDigestNow() }
        }, scope, appointmentSettings, { app.resound.search.EmbeddingScheduler.enqueue(this, continuation = true) }, AppGraph.platform.zone, AppGraph.platform.now, AppGraph.platform.dispatchers.io, database.revisions())
        AppGraph.rhythm = RoomRhythmStats(database, java.time.ZoneId.of(AppGraph.platform.zone.zoneId()))
        AppGraph.digestSettings = digestSettings
        AppGraph.llmProviders = digestSettings.providers
        // M2：灵感收件箱 / 项目记忆 / AI 分析 / Agent 桥接（本地能力，未配模型也可用手动功能）
        AppGraph.ideas = app.resound.inbox.IdeaRepository(database, scope, AppGraph.platform.now, AppGraph.platform.dispatchers.io)
        AppGraph.projects = app.resound.projects.ProjectRepository(database, scope, AppGraph.platform.now, AppGraph.platform.dispatchers.io)
        val analysisRepository = app.resound.analysis.AnalysisRepository(database, scope, AppGraph.platform.now,
            AppGraph.platform.zone, AppGraph.platform.dispatchers.io,
            { digestSettings.skillCredentials(null) }, digestSettings::canUseWithoutKey,
            { digestSettings.failover.hasBackup(it) },
            digestSettings.failover.request("分析", database.memory(), app.resound.usage.UsagePurpose.Analysis, llm::chatJson))
        AppGraph.analysis = analysisRepository
        AppGraph.agentBridge = app.resound.agent.AgentBridge(this, database, scope, AppGraph.platform.now, AppGraph.platform.dispatchers.io)
        AppGraph.agentBridge.startDriver()
        app.resound.agent.AgentDispatchWork.schedule(this)
        // M2+：第二大脑扩展——决策/跟进/收集箱/规则/纠错/问答/MCP
        val assistantNotifier = app.resound.push.LocalNotifier(this)
        AppGraph.memoryCorrector = app.resound.revise.MemoryCorrector(database.memory(), database.revisions(), AppGraph.platform.dispatchers.io, AppGraph.platform.now)
        AppGraph.decisionRepo = app.resound.decision.DecisionRepository(database.decisions(), database.memory(), database.revisions(), AppGraph.platform.dispatchers.io, AppGraph.platform.now, database.projects())
        AppGraph.followupEngine = app.resound.followup.FollowupEngine(database.followups(), database.ideas(), database.memory(),
            AppGraph.platform.dispatchers.io, scope, AppGraph.platform.now,
            { t, b, l -> assistantNotifier.send(t, b, l) })
        AppGraph.captureInbox = app.resound.collect.CaptureInbox(this, database.captureItems(), AppGraph.platform.dispatchers.io, scope, AppGraph.platform.now,
            { AppGraph.workflowEngine.emit(it) }, dbPath = { database.filePath })
        AppGraph.transcriptReview = app.resound.transcribe.TranscriptReviewRepository(database.transcribe(), AppGraph.platform.now::now)
        AppGraph.workflowEngine = app.resound.rules.WorkflowEngine(database.workflowRules(), database.memory(), AppGraph.platform.dispatchers.io, AppGraph.platform.now,
            { t, b, l -> assistantNotifier.send(t, b, l) }, { AppGraph.analysis }, { AppGraph.agentBridge })
        AppGraph.groundedQA = app.resound.qa.GroundedQA(app.resound.search.RecordSearch(database), database.decisions(), database.ideas(),
            database.projects(), database.memory(), database.followups(),
            AppGraph.platform.dispatchers.io, AppGraph.platform.now,
            { digestSettings.skillCredentials(null) }, digestSettings::canUseWithoutKey, { digestSettings.failover.hasBackup(it) },
            digestSettings.failover.request("记忆问答", database.memory(), app.resound.usage.UsagePurpose.Ask, llm::chatJson))
        val mcpSecrets = app.resound.digest.SecretStore(this, "mcp-secrets", "resound-mcp-aes-v1")
        AppGraph.mcpServer = app.resound.mcp.McpServer(database, app.resound.search.RecordSearch(database),
            { id -> runCatching { mcpSecrets.read("mcp_$id") }.getOrNull() },
            { AppGraph.projects.active().first() })
        if (getSharedPreferences("assistant", MODE_PRIVATE).getBoolean("mcp_enabled", false)) AppGraph.mcpServer.start()
        app.resound.followup.FollowupWork.schedule(this)
        app.resound.rules.WorkflowWork.schedule(this)
        // 打开库与启动写任务串行在同一个不可取消协程：首连接/打开事务若在可取消调用方手里被掐断，
        // 会泄漏成永不提交的外层事务、毒化整个连接池（真机实锤：提交返回成功但行不落盘）。
        scope.launch {
            runCatching { database.readSql("SELECT 1") { it.step() } }
            analysisRepository.ensureSeeded()
            analysisRepository.recoverStaleRunning()
            AppGraph.ideas.syncFromDigest { if (AppGraph.isAssistantReady) AppGraph.workflowEngine.emit(it) }
            AppGraph.decisionRepo.syncFromDigest()
            AppGraph.followupEngine.scan()
            AppGraph.captureInbox.reconcile()
        }
        scope.launch {
            AppGraph.followupEngine.events.collect { AppGraph.workflowEngine.emit(it) }
        }
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
            .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build(), requestHeaders = serviceHeaders::forPreset, official = officialBridge)
        AppGraph.manualTodos = RoomManualTodos(database.memory(), scope,
            { digestSettings.skillCredentials(null) }, digestSettings.failover.request("手动待办", database.memory(), app.resound.usage.UsagePurpose.Digest, todoLlm::chatJson), { reply ->
                database.memory().addUsage(java.time.LocalDate.now().toString(), reply.promptTokens,
                    reply.completionTokens, app.resound.usage.UsagePurpose.Digest)
            }, hasBackup = { digestSettings.canUseWithoutKey(it) || digestSettings.failover.hasBackup(it) },
            afterAdd = { if (AppGraph.isIdeasReady) AppGraph.ideas.syncFromDigest { if (AppGraph.isAssistantReady) AppGraph.workflowEngine.emit(it) } })
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
            { digestSettings.skillCredentials(null) }, digestSettings.failover.request("回顾", database.memory(), app.resound.usage.UsagePurpose.Review, llm::chatJson), { AppGraph.prompts.text("review") }, appName,
            app.resound.push.ChannelNotifier(app.resound.push.LocalNotifier(this), pushChannels), { reviewScheduler.schedule(it, changed = true) },
            dailyMemory = pushChannels::dispatchDailyMemory,
            beforeReview = { app.resound.review.ReviewPreparation.run(this) },
            work = androidx.work.WorkManager.getInstance(this), hasBackup = { digestSettings.canUseWithoutKey(it) || digestSettings.failover.hasBackup(it) }, now = AppGraph.platform.now::now, zone = { java.time.ZoneId.of(AppGraph.platform.zone.zoneId()) }, io = AppGraph.platform.dispatchers.io)
        val skillFiles = app.resound.skills.SkillFiles(this)
        digestSettings.providers.beforeDelete = { id -> skillFiles.resetProviders(setOf(id)) }
        skillFiles.resetProviders(skillFiles.skills.value.mapNotNull { it.presetId }.toSet() - digestSettings.presets.map { it.id }.toSet())
        val skillRepository = app.resound.skills.LocalSkillRepository(
            skillFiles, database.skills(), database.memory(), scope,
            digestSettings::skillCredentials, digestSettings.failover.request("技能", database.memory(), app.resound.usage.UsagePurpose.Skill, llm::chatJson), hotwords::merged,
            app.resound.push.ChannelNotifier(app.resound.push.LocalNotifier(this), pushChannels), skillScheduler::schedule,
            generation = app.resound.skills.SkillGeneration(androidx.work.WorkManager.getInstance(this), database.skills(), scope),
            beforeWeeklyReview = { app.resound.review.ReviewPreparation.run(this) },
            hasBackup = { digestSettings.canUseWithoutKey(it) || digestSettings.failover.hasBackup(it) },
            draftRequest = digestSettings.failover.request("技能生成", database.memory(), app.resound.usage.UsagePurpose.Draft, llm::chatJson), now = app.resound.AppGraph.platform.now::now, io = app.resound.AppGraph.platform.dispatchers.io)
        AppGraph.skills = skillRepository
        scope.launch { skillRepository.reschedule() }
        AppGraph.review = reviews
        AppGraph.diary = app.resound.diary.LocalDiaryRepository(this, AppGraph.memory, reviews, database.memory(), AppGraph.platform.zone, AppGraph.platform.dispatchers.io,
            cards = { date -> skillRepository.cards(date).first() })
        val dailyMemory = app.resound.push.DailyMemorySource(this, AppGraph.memory, reviews, database.memory(), database.review(), database.skills())
        pushChannels.memoryDocument = dailyMemory::load
        reviewScheduler.schedule(reviews.state.value.dailyAtMinutes)
        AppGraph.ask = app.resound.ask.createAskRepository(this, database, llm, digestSettings, scope, AppGraph.platform.now)
        AppGraph.transcripts = transcripts
        AppGraph.transcribeSettings = settings
        AppGraph.stats = RoomCaptureStatsRepository(this, database.capture(), AppGraph.platform.zone, transcripts::diagnostics)
        AppGraph.setup = AndroidSetupGuide(this)
        segmentFiles // 启动串行编码队列，重试完整暂存片段。
        scheduler.schedule()
        registerActivityLifecycleCallbacks(app.resound.digest.DigestForegroundRecovery(this))
        app.resound.search.EmbeddingScheduler.start(this)
        val backupRepository = app.resound.backup.AndroidBackupRepository(this, database, diary = AppGraph.diary)
        AppGraph.backup = backupRepository
        AppGraph.health = app.resound.health.AndroidHealthCheck(this, controller, quietSchedule, settings, transcripts,
            digestSettings, digestSettings.providers, AppGraph.llmBalance, backupRepository, database.capture(), scope)
        app.resound.quick.attachQuickActionsAndroid(this, scope, controller, AppGraph.topics, AppGraph.quickPrefs,
            app.resound.quick.DefaultQuickActionsPolicy())
        // 桌面小组件只在状态变化时更新（没有小组件时不做事）
        app.resound.widget.CaptureWidget.attach(this, scope, controller, AppGraph.topics)
        AppGraph.onboarding = app.resound.onboarding.LocalOnboarding(
            getSharedPreferences("onboarding", MODE_PRIVATE), getSharedPreferences("backup", MODE_PRIVATE),
            database.transcribe().onboardingFirstTranscript(),
            database.transcribe().onboardingMultipleSpeakers(System.currentTimeMillis() - 2 * app.resound.onboarding.LocalOnboarding.DAY),
            kotlinx.coroutines.flow.combine(settings.source, settings.modelRevision) { source, _ ->
                source == TranscribeSource.Local && !app.resound.transcribe.local.LocalModels.speakersEnabled(this)
            }, transcripts.clock, scope)
        val updater = app.resound.update.createUpdater(this, backupRepository)
        AppGraph.updater = updater
        AppGraph.problemReports = app.resound.diagnostics.LocalProblemReports(this, crashes, AppGraph.stats, updater, database.memory(), digestSettings.failover)
        AppGraph.deviceCards = app.resound.devices.createDeviceCards(this)
        cardDevices = app.resound.devices.createDevices(this, AppGraph.deviceCards)
        AppGraph.devices = cardDevices
        AppGraph.deviceScanError = cardDevices.lastError
        inputs.attachCards(cardDevices, AppGraph.deviceCards, scope)
        app.resound.devices.DeviceCardsWork.schedule(this)
        app.resound.update.UpdateWork.schedule(this, updater.autoCheck.value, updater.previewChannel.value)
        pipelineScheduler
        registerActivityLifecycleCallbacks(app.resound.update.UpdateForegroundChecks(updater, scope))
        pushChannels.attachSubscriptions(this, database, backupRepository, scope)
        pushChannels.attachFootprint(this, scope)
        app.resound.recordings.attachRecordingArchive(this, database, pushChannels, scope)
        scope.launch { transcribeRunner.recoverOnStartup(); transcribeRunner.withIdleAudio { voiceprintRepository.cleanupAudio() } }
        val folderImports = app.resound.importer.AndroidFolderImports(this, database, scope,
            AppGraph.importer as app.resound.importer.AndroidAudioImporter)
        AppGraph.folderImports = folderImports
        registerActivityLifecycleCallbacks(app.resound.importer.FolderImportForeground(folderImports))
        ImportAudioCleanupWork.schedule(this)
        watchdogPreferences = CaptureWatchdog.observe(this, store.prefs)
    }
}
