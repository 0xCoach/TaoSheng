package app.resound

import app.resound.capture.CaptureController
import app.resound.capture.CaptureStatsRepository
import app.resound.capture.PlaybackMonitor
import app.resound.capture.SetupGuide
import app.resound.digest.DigestSettings
import app.resound.digest.MemoryRepository
import app.resound.location.LocationRepository
import app.resound.screentime.PlaybackQueries
import app.resound.screentime.PlaybackSink
import app.resound.screentime.PlaybackTitleSettings
import app.resound.screentime.ScreenTimePuller
import app.resound.screentime.ScreenTimeReader
import app.resound.screentime.ScreenTimeSettings
import app.resound.transcribe.TranscribeSettings
import app.resound.transcribe.TranscriptRepository
import kotlin.reflect.KProperty

/** 同一个实例可安全发布给后台回调，各调用方可按需用可空或必需的访问方式。 */
private class ServiceSlot<T : Any> {
    @Volatile
    var instance: T? = null

    operator fun getValue(owner: AppGraph, property: KProperty<*>): T =
        instance ?: throw UninitializedPropertyAccessException("lateinit property ${property.name} has not been initialized")

    operator fun setValue(owner: AppGraph, property: KProperty<*>, value: T) {
        instance = value
    }
}

/**
 * 简单的手工依赖注入：Application 启动时装配，界面从这里取。
 * 底层实现完成前，界面用 ui/preview 里的假数据开发。
 */
object AppGraph {
    lateinit var searchHistory: app.resound.search.SearchHistory
    val isSearchHistoryReady: Boolean get() = ::searchHistory.isInitialized
    lateinit var marks: app.resound.marks.MarkRepository
    lateinit var markSettings: app.resound.marks.AndroidMarkSettings
    lateinit var platform: app.resound.platform.PlatformServices
    val isPlatformReady: Boolean get() = ::platform.isInitialized

    lateinit var quickPrefs: app.resound.quick.QuickPrefs
    val isQuickPrefsReady: Boolean get() = ::quickPrefs.isInitialized
    lateinit var health: app.resound.health.HealthCheck
    val isHealthReady: Boolean get() = ::health.isInitialized
    val settingsSearch: app.resound.settingsearch.SettingsSearch = app.resound.settingsearch.DefaultSettingsSearch()
    /** 设置搜索是纯 Kotlin 对象，始终可用 */
    val isSettingsSearchReady: Boolean get() = true

    internal lateinit var devicePlatform: app.resound.devices.android.AndroidDevicePlatform
    lateinit var devices: app.resound.devices.Devices
    lateinit var deviceScanError: kotlinx.coroutines.flow.StateFlow<String?>
    lateinit var deviceCards: app.resound.devices.DeviceCards
        internal set
    /** 外设三件都装配好了，界面才显示设备区块 */
    val isDevicesReady: Boolean get() = ::devices.isInitialized && ::deviceCards.isInitialized && ::deviceScanError.isInitialized
    lateinit var llmFailover: app.resound.digest.LlmFailover
    val isLlmFailoverReady: Boolean get() = ::llmFailover.isInitialized
    lateinit var llmProviders: app.resound.digest.LlmProviders
    val isLlmProvidersReady: Boolean get() = ::llmProviders.isInitialized
    lateinit var topics: app.resound.topic.TopicRepository
    val isTopicsReady: Boolean get() = ::topics.isInitialized
    val isMarksReady: Boolean get() = ::marks.isInitialized && ::markSettings.isInitialized
    lateinit var asrSpeed: app.resound.transcribe.AsrSpeedSettings
    lateinit var llmBalance: app.resound.usage.LlmBalanceChecker
    val isLlmBalanceReady: Boolean get() = ::llmBalance.isInitialized
    val isAsrSpeedReady: Boolean get() = ::asrSpeed.isInitialized

    // 卡 052–054：界面先按约定接好，底层合并后由 ResoundApp 装配
    // 卡 064：今日节奏的“平时”
    lateinit var rhythm: app.resound.digest.RhythmStats
    val isRhythmReady: Boolean get() = ::rhythm.isInitialized

    // 卡 068：录音导出与批量删除
    lateinit var audioExporter: app.resound.transcribe.AudioExporter
    val isAudioExporterReady: Boolean get() = ::audioExporter.isInitialized
    lateinit var exportJobs: app.resound.transcribe.ExportJobs
    val isExportJobsReady: Boolean get() = ::exportJobs.isInitialized
    lateinit var segmentDeleter: app.resound.transcribe.SegmentDeleter
    val isSegmentDeleterReady: Boolean get() = ::segmentDeleter.isInitialized

    // 卡 062：原声保留与播放
    lateinit var originalAudio: app.resound.transcribe.OriginalAudio
    val isOriginalAudioReady: Boolean get() = ::originalAudio.isInitialized

    // 卡 079：录音批量导出与推送到坚果云
    lateinit var recordingPacker: app.resound.recordings.RecordingPacker
    val isRecordingPackerReady: Boolean get() = ::recordingPacker.isInitialized

    // 卡 071：重做（重新转写 / 重新整理）
    lateinit var redo: app.resound.redo.RedoService
    val isRedoReady: Boolean get() = ::redo.isInitialized

    // 卡 070：环境音识别
    lateinit var nightInsights: app.resound.night.NightInsights
    lateinit var night: app.resound.night.NightRepository
    val isNightReady: Boolean get() = ::night.isInitialized
    lateinit var envSound: app.resound.envsound.EnvSoundRepository
    val isEnvSoundReady: Boolean get() = ::envSound.isInitialized
    /** 卡 132：新手上手的底层信号 */
    lateinit var onboarding: app.resound.onboarding.Onboarding
    val isOnboardingReady: Boolean get() = ::onboarding.isInitialized
    /** 卡 130：声音、位置、环境音按时间对齐查询 */
    lateinit var situation: app.resound.context.SituationLookup
    val isSituationReady: Boolean get() = ::situation.isInitialized
    private val footprint = ServiceSlot<LocationRepository>()
    var location: LocationRepository by footprint
    internal val locationIfReady: LocationRepository? get() = footprint.instance
    val isLocationReady: Boolean get() = locationIfReady != null

    /** 设置页和媒体监听共用的播放标题采集偏好。 */
    private val playbackTitles = ServiceSlot<PlaybackTitleSettings>()
    var playbackTitleSettings: PlaybackTitleSettings by playbackTitles
    internal val playbackTitleSettingsIfReady: PlaybackTitleSettings? get() = playbackTitles.instance
    val isPlaybackTitleReady: Boolean get() = playbackTitleSettingsIfReady != null
    /** 播放记录尚未装配时，整理按空记录继续。 */
    private val playbackHistory = ServiceSlot<PlaybackQueries>()
    var playbackQueries: PlaybackQueries?
        get() = playbackHistory.instance
        set(value) { playbackHistory.instance = value }
    /** 接收系统媒体事件，装配完成前跳过事件写入。 */
    private val playbackRecorder = ServiceSlot<PlaybackSink>()
    var playbackSink: PlaybackSink?
        get() = playbackRecorder.instance
        set(value) { playbackRecorder.instance = value }

    /** 屏幕事件采集的设置和拉取服务，独立于播放记录。 */
    private val screenControls = ServiceSlot<ScreenTimeSettings>()
    private val screenCollector = ServiceSlot<ScreenTimePuller>()
    var screenTimeSettings: ScreenTimeSettings by screenControls
    var screenTimePuller: ScreenTimePuller by screenCollector
    internal val screenTimeSettingsIfReady: ScreenTimeSettings?
        get() = screenControls.instance?.takeIf { screenCollector.instance != null }
    val isScreenTimeReady: Boolean get() = screenTimeSettingsIfReady != null
    /** 读取屏幕活动；查询服务未装配时不提供屏幕时间数据。 */
    private val screenHistory = ServiceSlot<ScreenTimeReader>()
    var screenTimeReader: ScreenTimeReader?
        get() = screenHistory.instance
        set(value) { screenHistory.instance = value }

    // 卡 079：寻物
    lateinit var things: app.resound.things.ThingRepository
    val isThingsReady: Boolean get() = ::things.isInitialized

    // 卡 056：停录时段
    lateinit var quietSchedule: app.resound.capture.QuietSchedule
    val isQuietScheduleReady: Boolean get() = ::quietSchedule.isInitialized

    lateinit var problemReports: app.resound.diagnostics.ProblemReports
    val isProblemReportsReady: Boolean get() = ::problemReports.isInitialized

    lateinit var digestTiming: app.resound.digest.DigestTiming
    val isDigestTimingReady: Boolean get() = ::digestTiming.isInitialized

    lateinit var manualTodos: app.resound.digest.ManualTodos
    val isManualTodosReady: Boolean get() = ::manualTodos.isInitialized

    lateinit var todoVoice: app.resound.digest.TodoVoiceInput
    val isTodoVoiceReady: Boolean get() = ::todoVoice.isInitialized

    lateinit var updater: app.resound.update.AppUpdater
    val isUpdaterReady: Boolean get() = ::updater.isInitialized

    lateinit var voiceInput: app.resound.capture.VoiceInputCapture
    val isVoiceInputReady: Boolean get() = ::voiceInput.isInitialized

    lateinit var inputs: app.resound.audio.InputDevices
    val isInputsReady: Boolean get() = ::inputs.isInitialized

    lateinit var usage: app.resound.usage.UsageRepository
    val isUsageReady: Boolean get() = ::usage.isInitialized

    lateinit var skills: app.resound.skills.SkillRepository
    val isSkillsReady: Boolean get() = ::skills.isInitialized

    lateinit var digestProgress: kotlinx.coroutines.flow.StateFlow<app.resound.digest.DigestProgress>
    val isDigestProgressReady: Boolean get() = ::digestProgress.isInitialized

    lateinit var playbackSentences: app.resound.transcribe.PlaybackSentences
    lateinit var playbackSettings: app.resound.transcribe.PlaybackSettings
    lateinit var playbackSkip: app.resound.capture.LegacyPlaybackControls
    val playbackDigestSettings get() = playbackSkip
    val isPlaybackSkipReady get() = ::playbackSkip.isInitialized
    val isPlaybackDigestReady get() = isPlaybackSkipReady

    /** 统一提供设备播放状态，订阅期间监听系统变化。 */
    private val playbackMonitor = ServiceSlot<PlaybackMonitor>()
    var playback: PlaybackMonitor by playbackMonitor


    lateinit var review: app.resound.review.ReviewRepository
    val isReviewReady: Boolean get() = ::review.isInitialized

    lateinit var privacy: app.resound.privacy.PrivacyActions
    val isPrivacyReady: Boolean get() = ::privacy.isInitialized

    lateinit var push: app.resound.push.PushChannels
    val isPushReady: Boolean get() = ::push.isInitialized

    lateinit var importKinds: app.resound.importer.ImportKinds
    val isImportKindsReady: Boolean get() = ::importKinds.isInitialized
    lateinit var folderImports: app.resound.importer.FolderImports
    lateinit var folderRecordings: app.resound.importer.FolderRecordings
    val isCallRecordingReady: Boolean get() = ::folderRecordings.isInitialized
    val isFolderImportReady: Boolean get() = ::folderImports.isInitialized
    lateinit var importer: app.resound.importer.AudioImporter
    val isImporterReady: Boolean get() = ::importer.isInitialized

    lateinit var hotwords: app.resound.transcribe.HotwordSettings
    val isHotwordsReady: Boolean get() = ::hotwords.isInitialized

    lateinit var prompts: app.resound.prompts.PromptTemplates
    val isPromptsReady: Boolean get() = ::prompts.isInitialized

    lateinit var diary: app.resound.diary.DiaryRepository
    val isDiaryReady: Boolean get() = ::diary.isInitialized

    lateinit var backup: app.resound.backup.BackupRepository
    val isBackupReady: Boolean get() = ::backup.isInitialized

    lateinit var capture: CaptureController
    lateinit var stats: CaptureStatsRepository
    lateinit var setup: SetupGuide

    lateinit var transcripts: TranscriptRepository
    lateinit var transcribeSettings: TranscribeSettings

    lateinit var memory: MemoryRepository
    private val digestControls = ServiceSlot<DigestSettings>()
    var digestSettings: DigestSettings by digestControls
    internal val digestSettingsIfReady: DigestSettings? get() = digestControls.instance
    val isDigestSettingsReady: Boolean get() = digestSettingsIfReady != null

    lateinit var ask: app.resound.ask.AskRepository
    val isAskReady: Boolean get() = ::ask.isInitialized

    lateinit var voiceprint: app.resound.voiceprint.VoiceprintRepository
    val isVoiceprintReady: Boolean get() = ::voiceprint.isInitialized

    /** 录音底层是否已装配 */
    val isReady: Boolean
        get() = ::capture.isInitialized && ::stats.isInitialized && ::setup.isInitialized

    /** 转写底层是否已装配 */
    val isTranscribeReady: Boolean
        get() = ::transcripts.isInitialized && ::transcribeSettings.isInitialized

    /** 大模型整理是否已装配 */
    val isMemoryReady: Boolean
        get() = ::memory.isInitialized && isDigestSettingsReady
}
