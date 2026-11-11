package app.resound.devices.plugins.d3200

import kotlinx.coroutines.CompletableDeferred

/** The sole reader routes matching replies without stealing audio or unsolicited status pushes. */
internal class D3200Replies {
    internal data class Pending(val command: Int, val file: Long?, val beforeReply: () -> Unit,
        val reply: CompletableDeferred<ByteArray> = CompletableDeferred())
    private var pending: Pending? = null
    @Synchronized fun begin(command: Int, file: Long?, beforeReply: () -> Unit = {}): Pending {
        check(pending == null)
        return Pending(command, file, beforeReply).also { pending = it }
    }
    @Synchronized fun dispatch(frame: ByteArray): Boolean {
        val wait = pending ?: return false
        if (D3200Protocol.command(frame) != wait.command) return false
        val payload = D3200Protocol.payload(frame)
        if (wait.file != null && !D3200Protocol.headerMatches(payload, wait.file)) return false
        pending = null
        if (frame[4].toInt() and 15 != 1) wait.reply.completeExceptionally(IllegalStateException("录音豆拒绝命令 ${wait.command.toString(16)}"))
        else { wait.beforeReply(); wait.reply.complete(payload) }
        return true
    }
    @Synchronized fun clear(wait: Pending) { if (pending === wait) pending = null }
    @Synchronized fun fail(error: Throwable) { pending?.reply?.completeExceptionally(error); pending = null }
}
