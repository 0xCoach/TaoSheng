package app.resound.home

/*
 * 卡 167：桌面小组件显示什么、按钮做什么（纯规则，iOS 小组件可复用）。不引用 android.* / java.*。
 */

/** 中间按钮：没有专题时开专题，专题进行中改成“记一下” */
enum class WidgetTopicButton { StartTopic, MarkTopic }

/** 右边按钮 */
enum class WidgetToggle { PauseListening, ResumeListening, StartListening, PauseTopic, ResumeTopic, OpenApp }

data class WidgetView(
    val status: String,
    val sub: String,
    /** 圆点是否亮（在录 / 专题进行中） */
    val active: Boolean,
    val topicButton: WidgetTopicButton,
    val toggle: WidgetToggle,
)

object WidgetRules {
    /**
     * [topicTitle] 有值表示有进行中的专题（如“会议专题 · 23 分”）；[resumeAt] 为“15:30”这样的恢复时刻；[quietName] 为停录时段名。
     */
    fun view(capture: HomeCapture, topicTitle: String?, topicPaused: Boolean, resumeAt: String?, quietName: String?): WidgetView = when {
        topicTitle != null -> WidgetView(topicTitle, if (topicPaused) "专题已暂停" else "点 ★ 记下重点", !topicPaused,
            WidgetTopicButton.MarkTopic, if (topicPaused) WidgetToggle.ResumeTopic else WidgetToggle.PauseTopic)
        else -> when (capture) {
            HomeCapture.Active -> WidgetView("正在聆听", "只在有人说话时录音", true, WidgetTopicButton.StartTopic, WidgetToggle.PauseListening)
            HomeCapture.Paused -> WidgetView(quietName?.let { "${it}中" } ?: "已暂停", resumeAt?.let { "$it 自动恢复" } ?: "点右边按钮恢复", false,
                WidgetTopicButton.StartTopic, WidgetToggle.ResumeListening)
            HomeCapture.InCall -> WidgetView("通话中，暂停收音", "通话结束后自动继续", false, WidgetTopicButton.StartTopic, WidgetToggle.PauseListening)
            HomeCapture.MicTaken -> WidgetView("麦克风被占用", "别的应用用完后自动继续", false, WidgetTopicButton.StartTopic, WidgetToggle.PauseListening)
            HomeCapture.Stopped -> WidgetView("没在聆听", "点右边按钮开始", false, WidgetTopicButton.StartTopic, WidgetToggle.StartListening)
            HomeCapture.NoPermission -> WidgetView("缺少权限", "点这里打开涛声去开启", false, WidgetTopicButton.StartTopic, WidgetToggle.OpenApp)
        }
    }
}
