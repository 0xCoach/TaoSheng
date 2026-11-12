package app.resound.quick

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.resound.MainActivity
import app.resound.R
import app.resound.capture.CaptureController
import app.resound.capture.CaptureService
import app.resound.capture.CaptureTilePolicy
import app.resound.capture.ClassModeTileService
import app.resound.topic.TopicQuickPolicy
import app.resound.topic.TopicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** 只观察状态与选择，不把每秒数十次的音量变化当成菜单更新。 */
@OptIn(FlowPreview::class)
internal fun attachQuickActionsAndroid(context: Context, scope: CoroutineScope, capture: CaptureController,
    topics: TopicRepository, prefs: QuickPrefs, policy: QuickActionsPolicy) {
    scope.launch(Dispatchers.Main.immediate) {
        combine(prefs.lastPauseMinutes, prefs.lastTopicKind) { minutes, kind -> minutes to kind }.collect {
            CaptureService.refreshOngoing()
            ClassModeTileService.refresh(context)
        }
    }
    scope.launch(Dispatchers.Main.immediate) {
        combine(
            capture.status.map { CaptureTilePolicy.listenState(it.state) }.distinctUntilChanged(),
            topics.current.map { (it?.id ?: 0L) to TopicQuickPolicy.state(it?.status) }.distinctUntilChanged(),
            prefs.lastPauseMinutes, prefs.lastTopicKind,
        ) { listen, topic, minutes, kind ->
            topic.first to policy.entries(listen, topic.second, minutes, kind)
        }.debounce(1000).collectLatest { (topicId, entries) ->
            val shortcuts = entries.take(ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)).mapIndexed { index, entry ->
                ShortcutInfoCompat.Builder(context, "quick-${entry.action.name}")
                    .setActivity(ComponentName(context, MainActivity::class.java))
                    .setShortLabel(entry.label).setLongLabel(entry.label).setRank(index)
                    .setIcon(IconCompat.createWithResource(context, R.drawable.ic_notify))
                    .setIntent(Intent(context, QuickActionActivity::class.java).setAction(Intent.ACTION_VIEW)
                        .setData(Uri.parse("resound://quick/${entry.action.name}/$topicId"))
                        .putExtra("quickAction", entry.action.name).putExtra("topicId", topicId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    .build()
            }
            // 后台发布受系统频率限制；保留最新状态，窗口恢复后重试，新状态会取消旧重试。
            while (true) {
                try {
                    if (ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)) break
                } catch (_: IllegalArgumentException) {
                    Log.w("QuickActions", "快捷菜单发布参数未被系统接受")
                    break
                } catch (_: IllegalStateException) {
                    Log.w("QuickActions", "快捷菜单暂不可更新")
                }
                delay(60_000)
            }
        }
    }
}
