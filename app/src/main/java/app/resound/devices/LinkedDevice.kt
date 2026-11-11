package app.resound.devices

internal interface LinkedDevice : DeviceLink {
    val disconnectedAt: Long? get() = null
    suspend fun close()
}
