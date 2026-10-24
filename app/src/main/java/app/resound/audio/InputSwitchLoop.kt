package app.resound.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow

/** 串行取消并收尾后才打开新来源。断开不延迟；同设备重新进入至少等 3 秒，等待时继续录手机。 */
internal class InputSwitchLoop(
    private val snapshots: Flow<InputSnapshot>,
    private val now: () -> Long,
    private val cooldownMillis: Long = 3_000,
) {
    private sealed interface Change {
        data class Devices(val snapshot: InputSnapshot) : Change
        data class Failed(val generation: Long, val error: AudioSourceException) : Change
        data object Retry : Change
    }
    suspend fun run(
        onUnchanged: suspend (InputRoute, String?) -> Unit = { _, _ -> },
        record: suspend (InputRoute, String?) -> Unit,
    ): Unit = coroutineScope {
        val changes = Channel<Change>(Channel.UNLIMITED)
        val observer = launch { snapshots.collect { changes.send(Change.Devices(it)) } }
        val until = mutableMapOf<String, Long>()
        var selected = InputRoute(InputDevices.PHONE)
        var choice: InputChoice? = null
        var active: InputRoute? = null
        var failed: InputRoute? = null
        var recording: Job? = null
        var timer: Job? = null
        var generation = 0L
        var note: String? = null
        try {
            for (change in changes) {
                when (change) {
                    is Change.Devices -> {
                        val next = change.snapshot.selected()
                        if (next != selected || choice != change.snapshot.choice) { failed = null; note = null }
                        selected = next
                        choice = change.snapshot.choice
                        val explicit = choice as? InputChoice.Device
                        if (selected.device.kind == InputKind.Phone && explicit != null)
                            note = "${explicit.name} 没有连接，先用手机麦克风"
                    }
                    is Change.Failed -> {
                        if (change.generation != generation) continue
                        val lost = active ?: continue
                        if (lost.device.kind == InputKind.Phone) throw change.error
                        failed = lost
                        note = if (change.error.reason == AudioSourceException.Reason.Disconnected)
                            "${lost.device.name} 断开或路由失效，已换回手机麦克风"
                        else "${lost.device.name} 现在不能录音，先用手机麦克风"
                    }
                    Change.Retry -> Unit
                }
                val delayMs = ((until[selected.device.key] ?: 0) - now()).coerceAtLeast(0)
                val replacedPort = active?.let { it.device.key == selected.device.key && it.portId != selected.portId } == true
                val next = if (selected == failed || delayMs > 0 || replacedPort) InputRoute(InputDevices.PHONE) else selected
                timer?.cancel(); timer = null
                if (delayMs > 0 && selected != failed) timer = launch { delay(delayMs); changes.send(Change.Retry) }
                if (next.device.kind != InputKind.Phone) note = null
                if (active == next) { onUnchanged(next, note); continue }
                val previous = active
                recording?.cancelAndJoin() // record 的 finally 正常 finish，不会丢弃片段。
                if (previous != null && previous.device.kind != InputKind.Phone) {
                    until[previous.device.key] = now() + cooldownMillis
                    if (replacedPort) timer = launch { delay(cooldownMillis); changes.send(Change.Retry) }
                    if (next.device.kind == InputKind.Phone && note == null)
                        note = "${previous.device.name} 已离开当前输入，已换回手机麦克风"
                }
                active = next
                val token = ++generation
                val message = note ?: if (next.device.kind != InputKind.Phone) "已选择 ${next.device.name}" else null
                recording = launch {
                    try {
                        record(next, message)
                        throw AudioSourceException(AudioSourceException.Reason.Disconnected, "输入结束")
                    } catch (e: AudioSourceException) { changes.send(Change.Failed(token, e)) }
                }
            }
        } finally {
            observer.cancel(); timer?.cancel(); recording?.cancel()
            withContext(NonCancellable) { recording?.join() }
            changes.close()
        }
    }
}
