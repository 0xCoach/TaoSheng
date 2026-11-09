/*
 * [INPUT]: PNote 异步 bindService 与 Android 服务连接生命周期。
 * [OUTPUT]: SDK 命令发送前的可等待就绪信号；绑定失败或断开时释放旧连接。
 * [POS]: SDK 启动边界；PNote 通过非粘性 EventBus 发命令，必须等服务和监听器就绪。
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package app.resound.sonicloud

import android.content.*
import android.os.IBinder
import com.wind.pnote.ui.DeviceDataListener
import com.wind.pnote.ui.PNote
import kotlinx.coroutines.*

internal class RecorderSdkBinding(
    private val context: Context,
    private val listener: DeviceDataListener,
    private val lost: (String) -> Unit,
) {
    private var readiness: CompletableDeferred<Unit>? = null
    private var connection: ServiceConnection? = null
    val isReady get() = readiness?.let { it.isCompleted && !it.isCancelled } == true

    // 所有入口与 Android 回调均在主线程，等待不会阻塞服务绑定回调。
    suspend fun awaitReady() {
        val pending = readiness ?: CompletableDeferred<Unit>().also { future ->
            readiness = future
            try {
                PNote.setShowLog(false)
                PNote.init(object : ContextWrapper(context) {
                    override fun bindService(intent: Intent, delegate: ServiceConnection, flags: Int): Boolean {
                        val observed = object : ServiceConnection {
                            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                                if (connection !== this) return
                                delegate.onServiceConnected(name, service)
                                future.complete(Unit)
                            }
                            override fun onServiceDisconnected(name: ComponentName) {
                                if (connection !== this) return
                                delegate.onServiceDisconnected(name)
                                invalidate("录音卡 SDK 服务已断开")
                            }
                            override fun onBindingDied(name: ComponentName) {
                                if (connection !== this) return
                                delegate.onBindingDied(name)
                                invalidate("录音卡 SDK 绑定已失效")
                            }
                            override fun onNullBinding(name: ComponentName) {
                                if (connection !== this) return
                                delegate.onNullBinding(name)
                                invalidate("录音卡 SDK 未返回有效服务")
                            }
                        }
                        connection = observed
                        check(context.bindService(intent, observed, flags)) { "无法启动录音卡 SDK 服务，请重试" }
                        return true
                    }
                }, listener)
            } catch (e: Exception) {
                release()
                throw e
            }
        }
        try {
            withTimeout(5000) { pending.await() }
            check(readiness === pending && isReady) { "录音卡 SDK 连接已中断，请重试" }
        } catch (e: Exception) {
            if (readiness === pending) release()
            if (e is TimeoutCancellationException) throw IllegalStateException("录音卡 SDK 初始化超时，请重试", e)
            throw e
        }
    }

    private fun invalidate(message: String) {
        val wasReady = isReady
        readiness?.completeExceptionally(IllegalStateException(message))
        release()
        if (wasReady) lost(message)
    }

    private fun release() {
        val previous = connection
        connection = null
        readiness = null
        if (previous != null) runCatching { context.unbindService(previous) }
    }
}
