package app.resound.ui.topic

import app.resound.AppGraph
import app.resound.topic.Topic
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 卡 169：记一下 / 撤销 / 删除重点。
 * 记一下之后要等数据库写完才拿得到这条重点的编号，用于“撤销”。
 */
object TopicMarks {
    /** 记一下，返回刚记下的（专题编号, 重点编号, 时刻）；拿不到时为 null（仍已记下） */
    suspend fun markAndGet(): Triple<Long, Long, Long>? {
        val before = AppGraph.topics.current.value?.marks?.map { it.id }?.toSet().orEmpty()
        AppGraph.topics.mark()
        val t: Topic = withTimeoutOrNull(2_000) {
            AppGraph.topics.current.first { cur -> cur?.marks?.any { it.id !in before } == true }
        } ?: return null
        val m = t.marks.filter { it.id !in before }.maxByOrNull { it.at } ?: return null
        return Triple(t.id, m.id, m.at)
    }

    suspend fun unmark(topicId: Long, markId: Long) = AppGraph.topics.unmark(topicId, markId)
}
