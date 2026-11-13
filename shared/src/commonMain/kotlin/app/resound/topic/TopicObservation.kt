package app.resound.topic

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

/** 失败专题由用户重试；Done 的迟到原话由单独的小查询唤醒，不常驻展开历史录音。 */
object TopicObservationPolicy {
    fun needsProgress(status: String) = status == "Ended" || status == "Summarizing"
}

/** 空集合时不创建段订阅；有任务时立即监听，不加时间去抖。 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> Flow<List<Long>>.whileTopicsPending(observe: (List<Long>) -> Flow<T>): Flow<T> =
    distinctUntilChanged().flatMapLatest { ids ->
        if (ids.isEmpty()) emptyFlow() else observe(ids).distinctUntilChanged()
    }
