package app.resound.devices.plugins.d3200

/** Only device-info readback confirms control; a manual pause survives reconnect/auto-start. */
internal object D3200ControlPolicy {
    fun autoStart(configured: Boolean, held: Boolean) = configured && !held
    fun confirmed(recording: Int?, start: Boolean) = if (start) recording == 1 else recording == 0 || recording == 2
}
