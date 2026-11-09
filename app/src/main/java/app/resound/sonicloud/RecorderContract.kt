/**
 * [INPUT]: 依赖 StateFlow 发布 SDK 状态。
 * [OUTPUT]: 提供录音卡设备、文件、传输模型与 RecorderController。
 * [POS]: sonicloud 的界面边界；隐藏厂商 JSON 和蓝牙实现。
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package app.resound.sonicloud

import kotlinx.coroutines.flow.StateFlow

data class RecorderDevice(val address: String, val name: String) {
    val key: String get() = "Sonicloud:${address.uppercase()}"
}
data class RecorderFile(val name: String, val size: Long, val seconds: Long, val imported: Boolean = false, val capturedLive: Boolean = false)
enum class RecorderRecording { Unknown, Stopped, Recording, Paused }

// 两值查询无法区分停止和暂停，不能用它抹掉已确认的暂停状态。
internal fun recorderQueryState(value: Int, previous: RecorderRecording, paused: Boolean): RecorderRecording =
    if (value == 1) RecorderRecording.Recording
    else if (paused || previous == RecorderRecording.Paused) RecorderRecording.Paused
    else RecorderRecording.Stopped

internal enum class RecorderCommand { Start, Continue }
internal fun recorderConnected(value: Any?): Boolean = value?.toString() in setOf("1", "true")
internal fun recorderMayConnect(currentKey: String?, requestedKey: String, subscribed: Boolean): Boolean = !subscribed || currentKey == requestedKey
internal fun recorderResumeCommand(state: RecorderRecording): RecorderCommand? = when (state) {
    RecorderRecording.Paused -> RecorderCommand.Continue
    RecorderRecording.Stopped -> RecorderCommand.Start
    else -> null
}
data class RecorderStatus(
    val scanning: Boolean = false,
    val devices: List<RecorderDevice> = emptyList(),
    val device: RecorderDevice? = null,
    val connected: Boolean = false,
    val battery: Int? = null,
    val recording: RecorderRecording = RecorderRecording.Unknown,
    val files: List<RecorderFile> = emptyList(),
    val listing: Boolean = false,
    val downloading: String? = null,
    val percent: Int = 0,
    val queued: Int = 0,
    val error: String? = null,
)
interface RecorderController {
    val status: StateFlow<RecorderStatus>
    fun scan()
    fun stopScan()
    fun connect(device: RecorderDevice)
    fun disconnect()
    fun stopRecording()
    fun refreshFiles()
    fun download(files: List<RecorderFile>)
    fun cancelDownload()
}
