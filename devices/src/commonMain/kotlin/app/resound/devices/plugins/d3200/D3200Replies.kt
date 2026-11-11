package app.resound.devices.plugins.d3200

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/** The sole reader routes matching replies without stealing audio or unsolicited status pushes. */
internal class D3200Replies {
    internal data class Pending(val command: Int, val file: Long?, val beforeReply: () -> Unit,
        val reply: CompletableDeferred<ByteArray> = CompletableDeferred())
    private val pending = MutableStateFlow<Pending?>(null)
    fun begin(command: Int, file: Long?, beforeReply: () -> Unit = {}): Pending {
        return Pending(command, file, beforeReply).also { check(pending.compareAndSet(null, it)) }
    }
    fun dispatch(frame: ByteArray): Boolean {
        val wait = pending.value ?: return false
        if (D3200Protocol.command(frame) != wait.command) return false
        val payload = D3200Protocol.payload(frame)
        if (wait.file != null && !D3200Protocol.headerMatches(payload, wait.file)) return false
        if (!pending.compareAndSet(wait, null)) return false
        if (frame[4].toInt() and 15 != 1) wait.reply.completeExceptionally(IllegalStateException("录音豆拒绝命令 ${wait.command.toString(16)}"))
        else { wait.beforeReply(); wait.reply.complete(payload) }
        return true
    }
    fun clear(wait: Pending) { pending.compareAndSet(wait, null) }
    fun fail(error: Throwable) { pending.getAndUpdate { null }?.reply?.completeExceptionally(error) }
}
