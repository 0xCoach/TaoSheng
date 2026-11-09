/*
 * [INPUT]: 串行控制队列、Android 热点连接及 PNote Wi-Fi 状态。
 * [OUTPUT]: 快传连接状态与受控的 TCP 建连、取消、超时清理。
 * [POS]: 只管理传输网络；文件续传与导入仍由 RecorderTransfer 负责。
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package app.resound.sonicloud

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RecorderWifiPhase { Off, Opening, Joining, Connecting, Ready }
data class RecorderWifiStatus(
    val phase: RecorderWifiPhase = RecorderWifiPhase.Off,
    val ssid: String? = null,
    val error: String? = null,
)

internal enum class RecorderWifiCommand { Open, Connect, Connected, Close }

internal interface RecorderWifiNetwork {
    fun request(ssid: String, available: () -> Unit, lost: () -> Unit)
    fun bind()
    fun restore()
    fun release()
}

internal class RecorderWifi(
    private val scope: CoroutineScope,
    private val post: (suspend () -> Unit) -> Unit,
    private val network: RecorderWifiNetwork,
    private val command: suspend (RecorderWifiCommand) -> Unit,
    private val failed: suspend (String) -> Unit,
) {
    private val mutable = MutableStateFlow(RecorderWifiStatus())
    val status = mutable.asStateFlow()
    val active get() = mutable.value.phase != RecorderWifiPhase.Off
    val ready get() = mutable.value.phase == RecorderWifiPhase.Ready
    private var generation = 0
    private var timer: Job? = null

    suspend fun open() {
        check(!active) { "Wi-Fi 已连接或正在连接" }
        val token = ++generation
        mutable.value = RecorderWifiStatus(RecorderWifiPhase.Opening)
        timer = scope.launch {
            delay(60_000)
            post { if (token == generation && !ready) stop("Wi-Fi 连接超时，请检查热点并重试") }
        }
        attempt { command(RecorderWifiCommand.Open) }
    }

    suspend fun event(state: Int, ssid: String?) {
        when (state) {
            0 -> if (mutable.value.phase == RecorderWifiPhase.Opening) attempt {
                require(!ssid.isNullOrBlank() && ssid != "null" && '\u0000' !in ssid &&
                    ssid.toByteArray(Charsets.UTF_8).size <= 32) { "录音卡未提供有效热点名称" }
                val token = generation
                mutable.value = RecorderWifiStatus(RecorderWifiPhase.Joining, ssid)
                network.request(ssid,
                    available = { post {
                        if (token == generation && mutable.value.phase == RecorderWifiPhase.Joining) attempt {
                            mutable.value = mutable.value.copy(phase = RecorderWifiPhase.Connecting)
                            network.bind()
                            command(RecorderWifiCommand.Connect)
                        }
                    } },
                    lost = { post { if (token == generation && active) stop("录音卡 Wi-Fi 已断开或连接被取消，重新选择文件可续传") } },
                )
            }
            2 -> when (mutable.value.phase) {
                RecorderWifiPhase.Connecting -> attempt {
                    command(RecorderWifiCommand.Connected)
                    network.restore()
                    timer?.cancel()
                    mutable.value = mutable.value.copy(phase = RecorderWifiPhase.Ready)
                }
                RecorderWifiPhase.Ready -> Unit
                RecorderWifiPhase.Off -> command(RecorderWifiCommand.Close)
                else -> stop("收到过期的 TCP 连接，请重新开启 Wi-Fi 快传")
            }
            1, 3 -> if (active) stop("录音卡热点或 TCP 已断开，重新选择文件可续传")
            // 4/5 只说明有无手机加入热点，不能当作本机 TCP 就绪。
        }
    }

    suspend fun stop(reason: String? = null) {
        if (!active) return
        generation++
        timer?.cancel(); timer = null
        mutable.value = RecorderWifiStatus(error = reason)
        var cleanupError: Exception? = null
        try { command(RecorderWifiCommand.Close) }
        catch (e: Exception) { if (e is CancellationException) throw e; cleanupError = e }
        finally {
            try { network.release() }
            catch (e: Exception) { if (cleanupError == null) cleanupError = e }
        }
        val message = reason ?: cleanupError?.let { "Wi-Fi 已释放，关闭录音卡热点失败：${it.message}" }
        if (message != null) {
            mutable.value = mutable.value.copy(error = message)
            failed(message)
        }
    }

    private suspend fun attempt(block: suspend () -> Unit) {
        try { block() }
        catch (e: Exception) {
            if (e is CancellationException) throw e
            stop(e.message ?: "Wi-Fi 连接失败")
        }
    }
}
