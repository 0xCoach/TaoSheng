package app.resound

import app.resound.capture.CaptureController
import app.resound.capture.CaptureStatsRepository
import app.resound.capture.SetupGuide
import app.resound.digest.DigestSettings
import app.resound.digest.MemoryRepository
import app.resound.transcribe.TranscribeSettings
import app.resound.transcribe.TranscriptRepository

/**
 * 简单的手工依赖注入：Application 启动时装配，界面从这里取。
 * 底层实现完成前，界面用 ui/preview 里的假数据开发。
 */
object AppGraph {
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

    // 卡 072：录音批量导出与推送到坚果云
    lateinit var recordingPacker: app.resound.recordings.RecordingPacker
    val isRecordingPackerReady: Boolean get() = ::recordingPacker.isInitialized
    lateinit var recordingSync: app.resound.recordings.RecordingSync
    val isRecordingSyncReady: Boolean get() = ::recordingSync.isInitialized

    // 卡 071：重做（重新转写 / 重新整理）
    lateinit var redo: app.resound.redo.RedoService
    val isRedoReady: Boolean get() = ::redo.isInitialized

    // 卡 070：环境音识别
    lateinit var envSound: app.resound.envsound.EnvSoundRepository
    val isEnvSoundReady: Boolean get() = ::envSound.isInitialized

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

    lateinit var playbackSkip: app.resound.capture.PlaybackSkipSettings
    val isPlaybackSkipReady: Boolean get() = ::playbackSkip.isInitialized


    lateinit var review: app.resound.review.ReviewRepository
    val isReviewReady: Boolean get() = ::review.isInitialized

    lateinit var privacy: app.resound.privacy.PrivacyActions
    val isPrivacyReady: Boolean get() = ::privacy.isInitialized

    lateinit var push: app.resound.push.PushChannels
    val isPushReady: Boolean get() = ::push.isInitialized

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
    lateinit var digestSettings: DigestSettings

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
        get() = ::memory.isInitialized && ::digestSettings.isInitialized
}
