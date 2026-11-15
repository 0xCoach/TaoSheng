package app.resound.devices

enum class DeviceLinkPhase { Disconnected, Connecting, Connected, Backfilling, WaitingReconnect, Disabled }
enum class DeviceRecorderPhase { Recording, Paused, Stopped }
enum class DeviceActivity { Disconnected, Connecting, ConnectedIdle, WaitingForRecording, StartingAudio, ReceivingAudio, Backfilling, WaitingReconnect, Disabled }
data class DeviceActivityEvent(val atMillis: Long, val activity: DeviceActivity)
data class DeviceExperience(
    val activity: DeviceActivity = DeviceActivity.Disconnected,
    val lastConnectedAt: Long? = null,
    val lastDisconnectedAt: Long? = null,
    val events: List<DeviceActivityEvent> = emptyList(),
)

fun deviceActivity(link: DeviceLinkPhase, recording: DeviceRecorderPhase?, wantsAudio: Boolean,
                   receivingAudio: Boolean, transferring: Boolean): DeviceActivity = when {
    link == DeviceLinkPhase.Disabled -> DeviceActivity.Disabled
    link == DeviceLinkPhase.WaitingReconnect -> DeviceActivity.WaitingReconnect
    link == DeviceLinkPhase.Connecting -> DeviceActivity.Connecting
    transferring || link == DeviceLinkPhase.Backfilling -> DeviceActivity.Backfilling
    link == DeviceLinkPhase.Disconnected -> DeviceActivity.Disconnected
    !wantsAudio -> DeviceActivity.ConnectedIdle
    recording == DeviceRecorderPhase.Paused || recording == DeviceRecorderPhase.Stopped -> DeviceActivity.WaitingForRecording
    receivingAudio -> DeviceActivity.ReceivingAudio
    else -> DeviceActivity.StartingAudio
}

/** Repeated battery/progress updates do not create connection events. No device IDs or audio are stored. */
fun DeviceExperience.advance(activity: DeviceActivity, atMillis: Long): DeviceExperience {
    if (this.activity == activity) return this
    val linked = setOf(DeviceActivity.ConnectedIdle, DeviceActivity.WaitingForRecording,
        DeviceActivity.StartingAudio, DeviceActivity.ReceivingAudio, DeviceActivity.Backfilling)
    return copy(activity = activity,
        lastConnectedAt = if (activity in linked && this.activity !in linked) atMillis else lastConnectedAt,
        lastDisconnectedAt = if (this.activity in linked && activity !in linked) atMillis else lastDisconnectedAt,
        events = (events + DeviceActivityEvent(atMillis, activity)).takeLast(40))
}

enum class DeviceReadingFreshness { Unknown, Recent, Stale }
fun deviceReadingFreshness(readAt: Long?, now: Long): DeviceReadingFreshness = when {
    readAt == null || readAt <= 0 || readAt > now + 60_000 -> DeviceReadingFreshness.Unknown
    now - readAt > 120_000 -> DeviceReadingFreshness.Stale
    else -> DeviceReadingFreshness.Recent
}

/** Resume only bytes present on disk AND committed in persistent transfer rows. */
fun deviceDurablePrefix(cachedBytes: Long, committedBytes: Long, expectedBytes: Long?, resumable: Boolean): Long =
    if (!resumable) 0 else minOf(cachedBytes.coerceAtLeast(0), committedBytes.coerceAtLeast(0), expectedBytes ?: Long.MAX_VALUE).coerceAtLeast(0)
