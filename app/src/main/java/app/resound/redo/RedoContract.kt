package app.resound.redo

import app.resound.transcribe.TranscribeSource
import kotlinx.coroutines.flow.StateFlow

/*
 * 卡 071 重做与选转写模型：界面（Claude）与底层（Codex）的约定。改签名需双方确认。
 */

/** 一种转写方式；只有 ready 的能选。note 写不能用的原因（如“未填密钥”“模型未下载”） */
data class AsrOption(val source: TranscribeSource, val title: String, val ready: Boolean, val note: String? = null)

/** 一个整理模型；ready = 已填密钥（检测过且通过时 checked = true） */
data class LlmOption(val presetId: String, val title: String, val ready: Boolean, val checked: Boolean = false, val note: String? = null)

/** 按时长估算：用时按本机实测（本机识别）或经验值（云端），花费按各自单价；估不出为 null */
data class AsrEstimate(val etaMillis: Long?, val costYuan: Double?)

/** 转写方式的选项与估算：重做和导入共用 */
interface AsrChooser {
    val options: StateFlow<List<AsrOption>>
    /** 上次在重做/导入里选的；没选过为设置里当前用的 */
    val lastChoice: StateFlow<TranscribeSource>
    fun remember(source: TranscribeSource)
    fun estimate(durationMs: Long, source: TranscribeSource): AsrEstimate
}

enum class RedoScope {
    /** 原话不动，只重新整理 */
    DigestOnly,
    /** 重新转写后再重新整理 */
    TranscribeAndDigest,
    /** 只重新转写（原话页选录音段时） */
    TranscribeOnly,
}

/** 首页选事件时给 eventIds；原话页选录音段时给 segmentIds。asr 只在需要转写时用 */
data class RedoRequest(
    val eventIds: List<Long> = emptyList(),
    val segmentIds: List<Long> = emptyList(),
    val scope: RedoScope,
    val asr: TranscribeSource? = null,
    val llmPresetId: String? = null,
)

data class RedoEstimate(
    val events: Int,
    val segments: Int,
    val audioMs: Long,
    /** 原声已过期、只能重新整理的事件 / 段数 */
    val expiredEvents: Int,
    val expiredSegments: Int,
    /** 手动编辑过的事件数（重做会覆盖，界面要提醒） */
    val editedEvents: Int,
    val etaMillis: Long?,
    val costYuan: Double?,
)

sealed interface RedoJobState {
    /** stage 给人看，如“重新转写 3/12 段”“重新整理” */
    data class Running(val stage: String, val fraction: Float) : RedoJobState
    /** 完成；undoable = 还能撤销到重做前 */
    data class Done(val summary: String, val undoable: Boolean) : RedoJobState
    data class Failed(val message: String) : RedoJobState
    data object Cancelled : RedoJobState
    data object Undone : RedoJobState
}

data class RedoJob(val id: String, val title: String, val request: RedoRequest, val state: RedoJobState, val createdAt: Long)

interface RedoService {
    val asr: AsrChooser
    val llmOptions: StateFlow<List<LlmOption>>
    suspend fun estimate(request: RedoRequest): RedoEstimate
    /** 进行中和最近完成的（完成的保留到 dismiss 或 1 天） */
    val jobs: StateFlow<List<RedoJob>>
    /** 后台进行；同一时间只跑一个，其余排队 */
    fun start(request: RedoRequest): String
    fun cancel(id: String)
    /** 恢复到重做前（只保留最近一次）；失败给人看的原因 */
    suspend fun undo(id: String): Result<Unit>
    fun dismiss(id: String)
}

/** 点“重做完成”通知打开 App 时 Intent 里带的任务 id */
const val EXTRA_REDO_JOB = "app.resound.redoJob"
