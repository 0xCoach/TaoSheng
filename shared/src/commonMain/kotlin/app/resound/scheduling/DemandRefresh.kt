package app.resound.scheduling

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive

/** 状态不变不会重置周期；需求出现立即刷新，消失即取消等待及正在执行的刷新。 */
suspend fun Flow<Boolean>.refreshWhileNeeded(intervalMillis: Long, refresh: suspend () -> Unit) {
    require(intervalMillis > 0)
    distinctUntilChanged().collectLatest { needed ->
        if (needed) while (currentCoroutineContext().isActive) {
            refresh()
            delay(intervalMillis)
        }
    }
}

/** 数据变化先校对一次，再只等待下一个截止点；null 不安排计时器。时间由平台传入。 */
suspend fun Flow<Long?>.refreshAtDeadline(now: () -> Long, refresh: suspend () -> Unit) {
    distinctUntilChanged().collectLatest { deadline ->
        refresh()
        if (deadline != null && deadline > now()) {
            delay((deadline - now()).coerceAtLeast(1))
            refresh()
        }
    }
}
