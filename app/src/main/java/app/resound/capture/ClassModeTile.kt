package app.resound.capture

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.resound.AppGraph
import app.resound.R
import kotlinx.coroutines.*

/** 保留组件名以兼容已添加的磁贴；新动作统一进入专题，旧课堂设置与历史仍可读取。 */
class ClassModeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val topic = if (AppGraph.isTopicsReady) AppGraph.topics.current.value else null
        app.resound.topic.TopicActionReceiver.intent(context, if (topic == null) "start" else "end", topic?.id ?: 0).send()
    }
}

class ClassModeTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    override fun onStartListening() {
        super.onStartListening()
        observer?.cancel()
        render()
        if (!AppGraph.isTopicsReady) return
        observer = scope.launch { AppGraph.topics.current.collect { render() } }
    }
    private fun render() {
        qsTile?.apply {
            label = "专题录音"
            icon = Icon.createWithResource(this@ClassModeTileService, R.drawable.ic_notify)
            val usable = AppGraph.isTopicsReady
            state = when {
                !usable -> Tile.STATE_UNAVAILABLE
                AppGraph.topics.current.value != null -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
            subtitle = if (!usable) "专题准备中" else AppGraph.topics.current.value?.let { app.resound.topic.TopicNotifications.line(it) } ?: "点一下开始专题"
            contentDescription = "$label，$subtitle"
            updateTile()
        }
    }
    override fun onClick() {
        super.onClick()
        if (!AppGraph.isTopicsReady) return
        val topic = AppGraph.topics.current.value
        if (topic == null) { app.resound.topic.TopicActionReceiver.intent(this, "start", 0).send(); return }
        // 进行中点一下打开专题面板（记一下/暂停/结束），不直接结束，免得误触
        val open = android.content.Intent(this, app.resound.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra("open", "topic").putExtra("topicId", topic.id)
        if (android.os.Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 27, open,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE))
        else @Suppress("DEPRECATION") startActivityAndCollapse(open)
    }
    override fun onStopListening() { observer?.cancel(); observer = null; super.onStopListening() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        internal fun refresh(context: Context) {
            TileService.requestListeningState(context, ComponentName(context, ClassModeTileService::class.java))
        }
    }
}
