package app.resound.digest

import kotlinx.coroutines.flow.StateFlow

/**
 * 卡 054 · 手动 / 语音添加待办的约定（Claude 定，Codex 实现，签名不要自行修改）。
 *
 * 待办页右下角按钮：点一下打字，按住说话。两种都把文字交给整理模型拆成待办卡，用户改好、确认后直接进“进行中”。
 */
enum class ItemOrigin { Digest, Typed, Voice }

/** 一张待确认的待办卡（还没入库）；字段含义同 [MemoryItem] */
data class TodoDraft(
    /** 待办 / 约定 / 备忘 */
    val category: String,
    /** 仅待办：我要做 / 我答应别人 / 别人答应我 */
    val kind: String?,
    val content: String,
    val dueText: String?,
    val dueAt: Long?,
    val who: String?,
    /** 到点强提醒（弹出、响铃、震动），默认关 */
    val strongReminder: Boolean = false,
)

data class TodoDrafts(
    val items: List<TodoDraft>,
    /** false = 没网、没填密钥或模型没给出可用结果，按原话生成了一张卡（时间留空） */
    val parsed: Boolean,
)

interface ManualTodos {
    /** 把一段话拆成待办卡（一句话里几件事就几张）；不入库。最多等 20 秒，超时按原话生成一张 */
    suspend fun draft(text: String): TodoDrafts

    /** 用户确认后入库：状态 Accepted、放“进行中”最前面；记下来源和原话，有时间的照常参与提醒 */
    fun add(drafts: List<TodoDraft>, origin: ItemOrigin, sourceText: String) = add(drafts, origin, sourceText, null)

    /** 从时间线上的一件事存的待办：记下这件事的第一句原话，详情里能看原话、听录音（重新转写时随原话一起迁移） */
    fun add(drafts: List<TodoDraft>, origin: ItemOrigin, sourceText: String, fromEventId: Long?)
}

/** 按住说话：只在用户按着时录，录音不保存，只留识别出的文字 */
interface TodoVoiceInput {
    /** 当前音量 0..1，给波形动画用 */
    val level: StateFlow<Float>

    /** 按下：暂停日常录音、开始录，最长 60 秒到时自动停。麦克风被占用或没权限时返回 false */
    suspend fun start(): Boolean

    /** 松开：停止录音、恢复日常录音，用当前转写方式识别 */
    suspend fun finish(): VoiceNoteResult

    /** 手指滑走取消：丢掉录音、恢复日常录音 */
    fun cancel()
}

sealed interface VoiceNoteResult {
    data class Text(val text: String) : VoiceNoteResult
    /** 不足 1 秒或没识别出字 */
    data object Empty : VoiceNoteResult
    /** message 只说结果，如“网络不通，没能识别”“还没下载本机转写模型” */
    data class Failed(val message: String) : VoiceNoteResult
}
