package app.resound.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.resound.AppGraph
import app.resound.MainActivity
import app.resound.R
import app.resound.capture.CaptureController
import app.resound.capture.CaptureService
import app.resound.capture.CaptureState
import app.resound.home.HomeCapture
import app.resound.home.WidgetRules
import app.resound.home.WidgetToggle
import app.resound.home.WidgetTopicButton
import app.resound.topic.TopicActionReceiver
import app.resound.topic.TopicRepository
import app.resound.topic.TopicStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * 桌面小组件（卡 167）：左边聆听 / 专题状态，右边加待办、开专题（进行中为记一下）、暂停 / 恢复。
 * 省电：不定时刷新，只在状态真正变化时更新；桌面上没有小组件时什么都不做。
 */
class CaptureWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = render(context, manager, ids)

    companion object {
        /** 状态变化时调用；没有小组件时直接返回 */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, CaptureWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        /** 监听聆听状态与专题：只看会改变显示的字段，去抖 1 秒，不因音量变化频繁刷新 */
        @OptIn(FlowPreview::class)
        fun attach(context: Context, scope: CoroutineScope, capture: CaptureController, topics: TopicRepository) {
            scope.launch(Dispatchers.Main.immediate) {
                combine(
                    capture.status.map { Triple(it.state, it.pausedUntil, it.quietName) to (it.busyDetail == app.resound.audio.MicYield.IN_CALL) }.distinctUntilChanged(),
                    topics.current.map { t -> t?.let { Triple(it.id, it.status, it.marks.size) } }.distinctUntilChanged(),
                ) { a, b -> a to b }.debounce(1000).collect { refresh(context) }
            }
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            if (!AppGraph.isReady) return
            val status = AppGraph.capture.status.value
            val topic = if (AppGraph.isTopicsReady) AppGraph.topics.current.value else null
            val inCall = status.busyDetail == app.resound.audio.MicYield.IN_CALL
            val capture = when (status.state) {
                CaptureState.Listening, CaptureState.Recording -> HomeCapture.Active
                CaptureState.Paused -> HomeCapture.Paused
                CaptureState.MicBusy -> if (inCall) HomeCapture.InCall else HomeCapture.MicTaken
                CaptureState.Stopped -> HomeCapture.Stopped
                CaptureState.NoPermission -> HomeCapture.NoPermission
            }
            val resumeAt = app.resound.ui.verify.resumeAt(status.pausedUntil)?.let {
                Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime().let { t -> "%d:%02d".format(t.hour, t.minute) }
            }
            val view = WidgetRules.view(capture, topic?.let { app.resound.ui.topic.topicHomeTitle(it) },
                topic?.status == TopicStatus.Paused, resumeAt, status.quietName)

            val rv = RemoteViews(context.packageName, R.layout.widget_capture)
            rv.setTextViewText(R.id.widget_status, view.status)
            rv.setTextViewText(R.id.widget_sub, view.sub)
            rv.setInt(R.id.widget_dot, "setImageAlpha", if (view.active) 255 else 90)
            rv.setOnClickPendingIntent(R.id.widget_root, openApp(context, topic?.id))
            rv.setOnClickPendingIntent(R.id.widget_todo, PendingIntent.getActivity(context, 1670,
                Intent(context, app.resound.ui.items.QuickTodoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            when (view.topicButton) {
                WidgetTopicButton.StartTopic -> {
                    rv.setImageViewResource(R.id.widget_topic, R.drawable.ic_qa_topic)
                    rv.setContentDescription(R.id.widget_topic, "开专题")
                    rv.setOnClickPendingIntent(R.id.widget_topic, TopicActionReceiver.intent(context, "start"))
                }
                WidgetTopicButton.MarkTopic -> {
                    rv.setImageViewResource(R.id.widget_topic, R.drawable.ic_qa_mark)
                    rv.setContentDescription(R.id.widget_topic, "记一下")
                    rv.setOnClickPendingIntent(R.id.widget_topic, TopicActionReceiver.intent(context, "mark", topic?.id ?: 0))
                }
            }
            val (icon, label, click) = when (view.toggle) {
                WidgetToggle.PauseListening -> Triple(R.drawable.ic_qa_pause, "暂停", service(context, CaptureService.PAUSE))
                WidgetToggle.ResumeListening -> Triple(R.drawable.ic_qa_play, "恢复", service(context, CaptureService.RESUME))
                WidgetToggle.StartListening -> Triple(R.drawable.ic_qa_mic, "开始聆听", service(context, CaptureService.START))
                WidgetToggle.PauseTopic -> Triple(R.drawable.ic_qa_pause, "暂停专题", TopicActionReceiver.intent(context, "pause", topic?.id ?: 0))
                WidgetToggle.ResumeTopic -> Triple(R.drawable.ic_qa_play, "继续专题", TopicActionReceiver.intent(context, "resume", topic?.id ?: 0))
                WidgetToggle.OpenApp -> Triple(R.drawable.ic_qa_mic, "打开涛声", openApp(context, null))
            }
            rv.setImageViewResource(R.id.widget_toggle, icon)
            rv.setContentDescription(R.id.widget_toggle, label)
            rv.setOnClickPendingIntent(R.id.widget_toggle, click)
            manager.updateAppWidget(ids, rv)
        }

        private fun service(context: Context, action: String): PendingIntent {
            val minutes = if (AppGraph.isQuickPrefsReady) AppGraph.quickPrefs.lastPauseMinutes.value else 30
            return PendingIntent.getForegroundService(context, 1671 + action.hashCode(),
                Intent(context, CaptureService::class.java).setAction(action).putExtra("route", "widget").putExtra("minutes", minutes),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun openApp(context: Context, topicId: Long?): PendingIntent = PendingIntent.getActivity(context, 1672,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .apply { if (topicId != null) putExtra("open", "topic").putExtra("topicId", topicId) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
