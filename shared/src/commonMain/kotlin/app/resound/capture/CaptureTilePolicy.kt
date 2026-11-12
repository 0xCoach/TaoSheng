package app.resound.capture

import app.resound.quick.ListenState
import app.resound.quick.QuickText

object CaptureTilePolicy {
    enum class Action { Pause, Resume, Start }
    fun action(state: CaptureState): Action = when (state) {
        CaptureState.Listening, CaptureState.Recording, CaptureState.MicBusy -> Action.Pause
        CaptureState.Paused -> Action.Resume
        CaptureState.Stopped, CaptureState.NoPermission -> Action.Start
    }
    fun listenState(state: CaptureState): ListenState = when (state) {
        CaptureState.Listening, CaptureState.Recording, CaptureState.MicBusy -> ListenState.Listening
        CaptureState.Paused -> ListenState.Paused
        CaptureState.Stopped -> ListenState.Stopped
        CaptureState.NoPermission -> ListenState.Unavailable
    }
    fun subtitle(state: CaptureState, pausedUntil: Long?, offsetSeconds: Int = 0): String = when (action(state)) {
        Action.Pause -> "聆听中"
        Action.Resume -> pausedUntil?.let { "已暂停到 ${QuickText.clock(it, offsetSeconds)}" } ?: "已暂停"
        Action.Start -> "已停止"
    }
}
