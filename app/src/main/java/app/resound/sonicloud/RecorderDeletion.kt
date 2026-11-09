/*
 * [INPUT]: 串行 SDK 删除回执、完整文件目录与连接中断。
 * [OUTPUT]: 逐个删除及目录复核进度；失败立即停止剩余操作。
 * [POS]: SonicloudRecorder 的删除状态机；无文件名回执必须由目录确认，不能据此乐观移除文件。
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package app.resound.sonicloud

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class RecorderDeleteStatus(
    val current: String? = null,
    val total: Int = 0,
    val completed: Int = 0,
    val verifying: Boolean = false,
    val stopping: Boolean = false,
    val error: String? = null,
    val needsReconnect: Boolean = false,
) {
    val busy: Boolean get() = current != null
}

internal class RecorderDeletion(
    private val scope: CoroutineScope,
    private val post: (suspend () -> Unit) -> Unit,
    private val send: suspend (String) -> Unit,
    private val refresh: suspend () -> Unit,
    private val failed: (String) -> Unit,
) {
    private val mutable = MutableStateFlow(RecorderDeleteStatus())
    val status = mutable.asStateFlow()
    val active get() = mutable.value.busy
    private val queue = ArrayDeque<String>()
    private var timer: Job? = null
    private var generation = 0L

    fun reset() {
        check(!active)
        timer?.cancel(); generation++
        queue.clear()
        mutable.value = RecorderDeleteStatus()
    }

    suspend fun start(files: List<RecorderFile>) {
        check(!active && !mutable.value.needsReconnect) { "请断开重连后刷新目录，再清理文件" }
        require(files.isNotEmpty())
        require(files.all { it.name.isNotBlank() && it.name.length <= 255 && '\u0000' !in it.name })
        queue.addAll(files.map { it.name }.distinct())
        mutable.value = RecorderDeleteStatus(total = queue.size)
        next()
    }

    private suspend fun next() {
        timer?.cancel(); generation++
        val name = queue.removeFirstOrNull()
        mutable.update { it.copy(current = name, verifying = false) }
        if (name == null) return
        armTimeout()
        try { send(name) }
        catch (e: Exception) {
            if (e is CancellationException) throw e
            abort(e.message ?: "删除命令发送失败")
        }
    }

    suspend fun acknowledged(success: Boolean) {
        if (!active || mutable.value.verifying) return
        if (!success) { abort("录音卡拒绝删除 ${mutable.value.current}"); return }
        mutable.update { it.copy(verifying = true) }
        armTimeout()
        try { refresh() }
        catch (e: Exception) {
            if (e is CancellationException) throw e
            abort(e.message ?: "无法复核删除结果")
        }
    }

    suspend fun listed(files: List<RecorderFile>) {
        val state = mutable.value
        if (!state.busy || !state.verifying) return
        if (files.any { it.name == state.current }) {
            abort("目录中仍有 ${state.current}，已停止清理")
            return
        }
        mutable.update { it.copy(completed = it.completed + 1) }
        next()
    }

    fun stopRemaining() {
        if (!active) return
        queue.clear()
        mutable.update { it.copy(stopping = true) }
    }

    fun abort(reason: String) {
        if (!active) return
        timer?.cancel(); generation++
        queue.clear()
        val message = "$reason；已停止后续删除，请断开重连并刷新目录核对结果"
        mutable.update { it.copy(current = null, verifying = false, error = message, needsReconnect = true) }
        failed(message)
    }

    fun directoryProgress() {
        if (active && mutable.value.verifying) armTimeout()
    }

    internal fun timeout() = abort("删除或目录复核超时，当前文件结果未知")

    private fun armTimeout() {
        timer?.cancel()
        val token = ++generation
        timer = scope.launch {
            delay(30_000)
            post { if (token == generation) timeout() }
        }
    }
}
