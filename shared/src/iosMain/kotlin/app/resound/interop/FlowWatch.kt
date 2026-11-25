package app.resound.interop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Swift 侧订阅共用层数据流：每次有新值回调到主线程，返回值调用 close() 取消订阅。 */
class FlowWatch internal constructor(private val scope: CoroutineScope) {
    fun close() = scope.cancel()
}

fun <T : Any> watchFlow(flow: Flow<T>, onEach: (T) -> Unit, onError: (Throwable) -> Unit): FlowWatch {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    scope.launch {
        try { flow.collect { onEach(it) } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { onError(e) }
    }
    return FlowWatch(scope)
}

/** Swift 侧取数据流的当前值（一次性读取）。 */
suspend fun <T : Any> firstOf(flow: Flow<T>): T = flow.first()
