package app.resound.capture

/*
 * R4 场景感知录音：把正交信号（手动暂停 / 隐私暂停 / 停录时段 / 会议专题 /
 * 麦克风占用 / 资源受限）合成一条录音指令与当前场景标签。
 * 纯函数、平台无关；安卓侧只负责采集信号与执行指令。
 */

enum class RecordingScene(val label: String) {
    Normal("日常"),
    Meeting("会议/专题"),
    QuietHours("停录时段"),
    /** 用户主动隐私暂停；只能用户亲手恢复 */
    PrivacyPaused("隐私暂停"),
    /** 手动定时暂停 */
    Paused("已暂停"),
    MicInterrupted("麦克风被占"),
    ResourceLimited("资源受限"),
}

enum class SceneSource { Manual, Rule, System }

data class SceneSignals(
    val now: Long,
    /** 手动暂停截止；大于 now 生效 */
    val manualPausedUntil: Long = 0,
    /** 隐私暂停：任何自动规则（闹钟、蓝牙重连、停录时段结束）都不得恢复 */
    val privacyPaused: Boolean = false,
    /** 专题已暂停：录音与转写随专题一起冻结，直到专题继续/结束/到期 */
    val topicPaused: Boolean = false,
    /** 当前停录时段窗口截止；不在时段内为 null */
    val quietEndsAt: Long? = null,
    val quietName: String? = null,
    /** 运行中的会议/专题要求覆盖停录时段（沿用既有 TopicPolicy.overridesQuiet 结果） */
    val meetingActive: Boolean = false,
    /** 麦克风被其他应用/通话占用原因；null 表示正常 */
    val micBusyReason: String? = null,
    /** 资源受限（低电量未充电 / 高温 / 存储不足）：只延后重计算，不停录音、不删音频 */
    val resourceLimited: Boolean = false,
    /** 场景感知总开关：关闭时自动派生项（资源延后、场景标签、会话记录）全部不生效，用户规则照旧 */
    val sceneAwareEnabled: Boolean = true,
)

sealed interface CaptureDirective {
    data object Record : CaptureDirective

    /** 停录到 [until]；[until] 为 Long.MAX_VALUE 表示不自动恢复（隐私暂停） */
    data class Pause(val until: Long, val label: String?, val source: SceneSource) : CaptureDirective
}

data class SceneDecision(
    val directive: CaptureDirective,
    /** 当前主场景标签；仅供展示与会话记录，不改变指令 */
    val scene: RecordingScene,
    /** 是否延后重负载计算（转写/整理/模型推理）；录音落盘不受影响 */
    val deferHeavyWork: Boolean,
    /** 给用户看的原因说明 */
    val reason: String?,
) {
    val pausing: Boolean get() = directive is CaptureDirective.Pause
}

object SceneEngine {
    fun decide(s: SceneSignals): SceneDecision {
        val directive: CaptureDirective = when {
            s.privacyPaused -> CaptureDirective.Pause(Long.MAX_VALUE, RecordingScene.PrivacyPaused.label, SceneSource.Manual)
            s.topicPaused -> CaptureDirective.Pause(Long.MAX_VALUE, "专题暂停", SceneSource.Manual)
            s.manualPausedUntil > s.now -> CaptureDirective.Pause(s.manualPausedUntil, null, SceneSource.Manual)
            s.meetingActive -> CaptureDirective.Record
            s.quietEndsAt != null && s.quietEndsAt > s.now ->
                CaptureDirective.Pause(s.quietEndsAt, s.quietName, SceneSource.Rule)
            else -> CaptureDirective.Record
        }
        val scene = when {
            s.privacyPaused -> RecordingScene.PrivacyPaused
            directive is CaptureDirective.Pause && directive.source == SceneSource.Manual -> RecordingScene.Paused
            directive is CaptureDirective.Pause -> RecordingScene.QuietHours
            s.micBusyReason != null -> RecordingScene.MicInterrupted
            s.meetingActive && s.sceneAwareEnabled -> RecordingScene.Meeting
            s.resourceLimited && s.sceneAwareEnabled -> RecordingScene.ResourceLimited
            else -> RecordingScene.Normal
        }
        val reason = when (scene) {
            RecordingScene.PrivacyPaused -> "已停止采集；需要你亲手恢复"
            RecordingScene.Paused -> (directive as? CaptureDirective.Pause)?.label?.let { "${it}中" } ?: "定时暂停中"
            RecordingScene.QuietHours -> s.quietName?.let { "停录时段：$it" }
            RecordingScene.MicInterrupted -> s.micBusyReason?.let { "麦克风被占用：$it" } ?: "麦克风被占用"
            RecordingScene.ResourceLimited -> "资源受限，重计算任务延后；录音照常"
            RecordingScene.Meeting -> "会议/专题进行中"
            RecordingScene.Normal -> null
        }
        return SceneDecision(directive, scene, deferHeavyWork = s.resourceLimited && s.sceneAwareEnabled, reason)
    }
}
