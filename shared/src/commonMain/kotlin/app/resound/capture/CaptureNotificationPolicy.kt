package app.resound.capture

import app.resound.quick.QuickText
import app.resound.topic.TopicKind

enum class CaptureNoticeTarget { Resume, Topic, App }
data class CaptureNotice(val title: String, val text: String, val target: CaptureNoticeTarget, val toggle: String)

object CaptureNotificationPolicy {
    fun ongoing(appName: String, paused: Boolean, quietName: String?, clock: String, topicLine: String?, minutes: Int): CaptureNotice = CaptureNotice(
        title = when { quietName != null -> "$appName · ${quietName}中"; paused -> "$appName · 已暂停"; else -> appName },
        text = topicLine ?: when { quietName != null -> "$clock 自动恢复，点此立即恢复"; paused -> "到点自动恢复，也可点此立即恢复"; else -> "后台运行中" },
        target = when { topicLine != null -> CaptureNoticeTarget.Topic; paused -> CaptureNoticeTarget.Resume; else -> CaptureNoticeTarget.App },
        // 通知按钮位置窄，统一用两三个字的短词，避免被截断
        toggle = if (quietName != null || paused) "恢复" else "暂停",
    )
    fun clock(daysAway: Long, hourMinute: String, full: String) = when (daysAway) { 0L -> hourMinute; 1L -> "明天 $hourMinute"; else -> full }
    fun startTopic(kind: TopicKind) = QuickText.startTopic(kind)
    const val ADD_TODO = "加待办"
    const val START_TOPIC = "开专题"
    fun interrupted(appName: String) = "${appName}已停止，点此恢复"
    const val INTERRUPTED_DETAIL = "后台被系统关闭，点击重新开始"
    const val SILENT = "麦克风收不到声音"
    const val SILENT_DETAIL = "请检查系统的麦克风开关，或在涛声里暂停后再恢复"
}
