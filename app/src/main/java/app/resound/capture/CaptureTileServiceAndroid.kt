package app.resound.capture

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.resound.AppGraph
import app.resound.R
import app.resound.appName
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId

class CaptureTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    override fun onStartListening() {
        super.onStartListening()
        observer?.cancel()
        if (!AppGraph.isReady) return
        observer = scope.launch {
            AppGraph.capture.status.map { it.state to it.pausedUntil }.distinctUntilChanged().collect {
                render(AppGraph.capture.status.value)
            }
        }
    }
    private fun render(status: CaptureStatus) {
        qsTile?.apply {
            label = appName
            icon = Icon.createWithResource(this@CaptureTileService, R.drawable.ic_notify)
            state = if (CaptureTilePolicy.action(status.state) == CaptureTilePolicy.Action.Pause) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = CaptureTilePolicy.subtitle(status, ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(status.pausedUntil ?: 0)).totalSeconds)
            contentDescription = "$label，$subtitle"
            updateTile()
        }
    }
    override fun onClick() {
        super.onClick()
        unlockAndRun {
            if (AppGraph.isReady) CaptureTilePolicy.click(AppGraph.capture, AppGraph.quickPrefs.lastPauseMinutes.value)
        }
    }
    override fun onStopListening() { observer?.cancel(); observer = null; super.onStopListening() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        internal fun refresh(context: Context) {
            TileService.requestListeningState(context, ComponentName(context, CaptureTileService::class.java))
        }
    }
}
