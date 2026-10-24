package app.resound.capture

import kotlinx.coroutines.flow.StateFlow

/*
 * 手动速记与界面之间的约定。界面（Claude）只依赖这里；底层（Codex）实现。改签名需双方确认。
 *
 * 按一下开始速记，说一段话，停下（再按一下、安静 4 秒或满 2 分钟）后：这段话优先转写，
 * 然后用“手动速记”技能（assets/skills/quick_note）整理成一张技能卡，发一条通知可点开。
 * 入口：常驻通知的“速记”按钮、控制中心“回响速记”开关、App 今天页的速记按钮。
 */

enum class QuickNotePhase {
    /** 空闲 */
    Idle,

    /** 正在录这一段 */
    Recording,

    /** 录完了，正在转写和整理 */
    Processing,
}

data class QuickNoteState(
    val phase: QuickNotePhase,
    /** 这一段开始时间（录音中、处理中有值） */
    val startedAt: Long?,
    /** 最近一次成功出的卡片 id（用来跳转） */
    val lastCardId: Long?,
    /** 最近一次失败的原因（给人看），成功后清空 */
    val lastError: String?,
)

interface QuickNoteController {
    val state: StateFlow<QuickNoteState>

    /** 开始一段速记；正在录时再调用等同于 stop()。录音没开或麦克风被占用时返回失败原因 */
    fun toggle(): Result<Unit>

    /** 结束这一段，开始转写和整理 */
    fun stop()

    /** 放弃这一段（不转写、不出卡，录音照常进入普通流程） */
    fun cancel()
}
