package app.resound.topic

import app.resound.quick.QuickText
import app.resound.quick.TopicState

data class TopicButton(val action: String, val label: String)

object TopicQuickPolicy {
    const val FAILED = "专题操作失败，请重试"
    const val QUIET_STARTED = "专题已开始，这段时间原本设为不录音"
    const val RECORDING_OFF = "专题已开始，录音没开"
    const val TILE_LABEL = "专题录音"
    fun state(status: TopicStatus?): TopicState = when (status) {
        TopicStatus.Running -> TopicState.Running
        TopicStatus.Paused -> TopicState.Paused
        else -> TopicState.None
    }
    fun active(status: String?) = status == "Running" || status == "Paused"
    fun receiverAction(hasTopic: Boolean) = if (hasTopic) "end" else "start"
    fun tileSubtitle(ready: Boolean, line: String?, kind: TopicKind) = when {
        !ready -> "专题准备中"
        line != null -> line
        else -> QuickText.startTopic(kind)
    }
    fun line(name: String?, kind: TopicKind, paused: Boolean, startAt: Long, now: Long, endClock: String?): String {
        val minutes = ((now - startAt) / 60_000).coerceAtLeast(0)
        return "${name ?: kind.title}${if (paused) "已暂停" else "中"} · 已 $minutes 分钟" + (endClock?.let { " · $it 结束" } ?: "")
    }
    fun actions(paused: Boolean) = listOf(TopicButton("mark", "记一下"),
        if (paused) TopicButton("resume", "继续") else TopicButton("pause", "暂停"), TopicButton("end", "结束"))
    fun startFeedback(error: String?, recordingDisabled: Boolean, quietOverride: Boolean): String? = when {
        error != null -> error
        recordingDisabled -> RECORDING_OFF
        quietOverride -> QUIET_STARTED
        else -> null
    }
    fun reminderTitle(reminder: TopicReminder, kind: TopicKind, scheduled: Boolean) = when (reminder) {
        TopicReminder.Ending -> "还有 5 分钟结束"
        TopicReminder.Quiet -> "15 分钟没人说话，要结束专题吗？"
        TopicReminder.RecordingOff -> if (scheduled) "预约的专题开始了，录音没开" else RECORDING_OFF
        TopicReminder.Ready -> when (kind) {
            TopicKind.Meeting -> "会议纪要好了"; TopicKind.Training -> "培训笔记好了"; TopicKind.Call -> "通话记录好了"
            TopicKind.Script -> "口播稿好了"; TopicKind.Normal -> "专题整理好了"
        }
    }
    fun reminderActions(reminder: TopicReminder) = when (reminder) {
        TopicReminder.Ending -> listOf(TopicButton("extend", "延长 30 分钟"))
        TopicReminder.Quiet -> listOf(TopicButton("end", "结束"), TopicButton("continue", "继续录"))
        TopicReminder.RecordingOff -> listOf(TopicButton("start-listening", "打开录音"))
        TopicReminder.Ready -> emptyList()
    }
}
