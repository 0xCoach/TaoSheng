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
import java.time.format.DateTimeFormatter

internal object CaptureTilePolicy {
    enum class Action { Pause, Resume, Start }
    fun action(state: CaptureState): Action = when (state) {
        CaptureState.Listening, CaptureState.Recording, CaptureState.MicBusy -> Action.Pause
        CaptureState.Paused -> Action.Resume
        CaptureState.Stopped, CaptureState.NoPermission -> Action.Start
    }
    fun click(controller: CaptureController) = when (action(controller.status.value.state)) {
        Action.Pause -> controller.pause(30)
        Action.Resume -> controller.resume()
        Action.Start -> controller.start()
    }
    fun subtitle(status: CaptureStatus): String = when (action(status.state)) {
        Action.Pause -> "聆听中"
        Action.Resume -> status.pausedUntil?.let {
            "已暂停到 ${Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))}"
        } ?: "已暂停"
        Action.Start -> "已停止"
    }
}

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
            subtitle = CaptureTilePolicy.subtitle(status)
            contentDescription = "$label，$subtitle"
            updateTile()
        }
    }
    override fun onClick() {
        super.onClick()
        unlockAndRun {
            if (AppGraph.isReady) CaptureTilePolicy.click(AppGraph.capture)
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
