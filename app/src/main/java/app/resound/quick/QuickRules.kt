package app.resound.quick

import app.resound.topic.TopicKind

object QuickPrefsRules {
    const val DEFAULT_PAUSE = 30
    val defaultTopic = TopicKind.Meeting
    private val pauses = listOf(15, 30, 60, 120)

    /** 同距离选较短一档；转 Long 后计算，避免异常整数溢出。 */
    fun pauseMinutes(value: Int): Int = pauses.minBy { kotlin.math.abs(it.toLong() - value.toLong()) }
    fun topicKind(value: String?): TopicKind = TopicKind.entries.firstOrNull { it.name == value } ?: defaultTopic
}

object QuickText {
    fun duration(minutes: Int): String = QuickPrefsRules.pauseMinutes(minutes).let {
        if (it >= 60) "${it / 60} 小时" else "$it 分钟"
    }
    fun pause(minutes: Int) = "暂停 ${duration(minutes)}"
    // 通话类型的原始标题含空格；紧凑后所有入口均不超过 10 字符。
    fun topic(kind: TopicKind) = kind.title.filterNot(Char::isWhitespace)
    fun startTopic(kind: TopicKind) = "开始${topic(kind)}专题"
    fun clock(at: Long, offsetSeconds: Int): String {
        val minute = ((at / 60_000 + offsetSeconds / 60) % 1440 + 1440) % 1440
        return "${(minute / 60).toString().padStart(2, '0')}:${(minute % 60).toString().padStart(2, '0')}"
    }
    const val FAILED = "快捷操作失败，请重试"
    const val STALE = "状态已变化，请重新打开快捷菜单"
    const val PERMISSION = "缺少录音或通知权限，请在设置中开启"
    const val CAPTURE_PENDING = "录音状态尚未确认，请打开涛声查看"
    fun success(action: QuickAction, topic: TopicState, minutes: Int, kind: TopicKind): String = when (action) {
        QuickAction.Pause -> if (topic == TopicState.Running) "专题已暂停" else "已暂停 ${duration(minutes)}"
        QuickAction.Resume -> if (topic == TopicState.Paused) "专题已继续" else "已恢复聆听"
        QuickAction.StartListening -> "已开始聆听"
        QuickAction.StartTopic -> "${topic(kind)}专题已开始"
        QuickAction.MarkTopic -> "已记下这一刻"
        QuickAction.EndTopic -> "专题已结束"
    }
}

class DefaultQuickActionsPolicy : QuickActionsPolicy {
    override fun entries(listen: ListenState, topic: TopicState, lastPauseMinutes: Int, lastTopicKind: TopicKind): List<QuickEntry> =
        when (topic) {
            TopicState.Running -> listOf(QuickEntry(QuickAction.MarkTopic, "记一下"), QuickEntry(QuickAction.Pause, "暂停专题"), QuickEntry(QuickAction.EndTopic, "结束专题"))
            TopicState.Paused -> listOf(QuickEntry(QuickAction.Resume, "继续专题"), QuickEntry(QuickAction.EndTopic, "结束专题"))
            TopicState.None -> listOf(when (listen) {
                ListenState.Listening -> QuickEntry(QuickAction.Pause, QuickText.pause(lastPauseMinutes))
                ListenState.Paused -> QuickEntry(QuickAction.Resume, "现在恢复")
                ListenState.Stopped, ListenState.Unavailable -> QuickEntry(QuickAction.StartListening, "开始聆听")
            }, QuickEntry(QuickAction.StartTopic, QuickText.startTopic(lastTopicKind)))
        }
}

/** 快捷菜单携带专题编号，防止旧入口误操作后来开始的另一场专题。0 表示无专题。 */
object QuickExecutionRules {
    fun allowed(action: QuickAction, expectedTopicId: Long, currentTopicId: Long, listen: ListenState,
        topic: TopicState, minutes: Int, kind: TopicKind): Boolean =
        expectedTopicId == currentTopicId && DefaultQuickActionsPolicy().entries(listen, topic, minutes, kind).any { it.action == action }

    fun command(action: QuickAction, topic: TopicState): QuickCommand = when (action) {
        QuickAction.Pause -> if (topic == TopicState.Running) QuickCommand.PauseTopic else QuickCommand.PauseListening
        QuickAction.Resume -> if (topic == TopicState.Paused) QuickCommand.ResumeTopic else QuickCommand.ResumeListening
        QuickAction.StartListening -> QuickCommand.StartListening
        QuickAction.StartTopic -> QuickCommand.StartTopic
        QuickAction.MarkTopic -> QuickCommand.MarkTopic
        QuickAction.EndTopic -> QuickCommand.EndTopic
    }

    fun captureConfirmed(command: QuickCommand, listen: ListenState): Boolean = when (command) {
        QuickCommand.PauseListening -> listen == ListenState.Paused
        QuickCommand.ResumeListening, QuickCommand.StartListening -> listen == ListenState.Listening
        else -> true
    }
}

enum class QuickCommand(val needsMicrophone: Boolean = false) {
    PauseListening(true), ResumeListening(true), StartListening(true), StartTopic, MarkTopic, PauseTopic, ResumeTopic, EndTopic,
}
