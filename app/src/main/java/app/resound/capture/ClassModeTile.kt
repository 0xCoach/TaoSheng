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
        app.resound.topic.TopicActionReceiver.intent(this, if (topic == null) "start" else "end", topic?.id ?: 0).send()
    }
    override fun onStopListening() { observer?.cancel(); observer = null; super.onStopListening() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        internal fun refresh(context: Context) {
            TileService.requestListeningState(context, ComponentName(context, ClassModeTileService::class.java))
        }
    }
}
