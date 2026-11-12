package app.resound.quick

import app.resound.topic.TopicKind
import kotlinx.coroutines.flow.StateFlow

/*
 * 卡 147：快捷操作（主卡暂停/专题、桌面长按菜单、快捷开关、通知）的平台无关约定。
 * 本文件不引用 android.* / java.*，以后 iOS 直接复用。
 */

/** 记住上次的选择：下次一点就用它 */
interface QuickPrefs {
    /** 上次选的暂停时长（分钟），默认 30 */
    val lastPauseMinutes: StateFlow<Int>
    fun setLastPauseMinutes(minutes: Int)

    /** 上次开始的专题类型，默认会议 */
    val lastTopicKind: StateFlow<TopicKind>
    fun setLastTopicKind(kind: TopicKind)
}

/** 聆听状态的简化版：只区分快捷操作关心的几种 */
enum class ListenState { Listening, Paused, Stopped, Unavailable }

/** 专题状态的简化版；没有进行中的专题时为 None */
enum class TopicState { None, Running, Paused }

/** 一个快捷操作 */
enum class QuickAction { Pause, Resume, StartListening, StartTopic, MarkTopic, EndTopic }

/** 一个快捷入口要显示的内容：动作 + 显示文字（如“开始培训专题”“暂停 30 分钟”） */
data class QuickEntry(val action: QuickAction, val label: String)

/** 按当前状态排出快捷入口（桌面长按菜单用；最多 4 个，最常用的在前） */
fun interface QuickActionsPolicy {
    fun entries(listen: ListenState, topic: TopicState, lastPauseMinutes: Int, lastTopicKind: TopicKind): List<QuickEntry>
}
