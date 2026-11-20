package app.resound.topic

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

/** 会中与会后等待都观察进度；失败专题由用户重试，完成专题由原话变化唤醒。 */
object TopicObservationPolicy {
    fun needsProgress(status: String) = status in setOf("Running", "Paused", "Ended", "Summarizing")
}

/** 空集合时不创建段订阅；有任务时立即监听，不加时间去抖。 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> Flow<List<Long>>.whileTopicsPending(observe: (List<Long>) -> Flow<T>): Flow<T> =
    distinctUntilChanged().flatMapLatest { ids ->
        if (ids.isEmpty()) emptyFlow() else observe(ids).distinctUntilChanged()
    }
