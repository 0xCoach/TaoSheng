package app.resound.capture

import app.resound.quick.QuickPrefsRules

internal fun CaptureTilePolicy.click(controller: CaptureController, lastPauseMinutes: Int = QuickPrefsRules.DEFAULT_PAUSE) = when (action(controller.status.value.state)) {
    CaptureTilePolicy.Action.Pause -> controller.pause(QuickPrefsRules.pauseMinutes(lastPauseMinutes))
    CaptureTilePolicy.Action.Resume -> controller.resume()
    CaptureTilePolicy.Action.Start -> controller.start()
}

internal fun CaptureTilePolicy.subtitle(status: CaptureStatus, offsetSeconds: Int = 0): String =
    subtitle(status.state, status.pausedUntil, offsetSeconds)
