package app.resound.topic.core
import app.resound.topic.*

data class TopicRow(
    val id: Long = 0,
    val kind: String, val name: String?, val status: String, val createdAt: Long,
    val plannedStartAt: Long? = null, val plannedEndAt: Long? = null, val repeatRule: Int? = null,
    val eventId: Long? = null, val cardId: Long? = null, val error: String? = null, val revision: Long = 0,
    // 下列少量恢复元数据也进备份，避免进程重启丢失用户修改或重复提醒。
    val quietOverride: Boolean = false, val retainedItemIds: String = "[]", val starred: Boolean = false,
    val quietNotifiedAfter: Long? = null, val endingNotifiedAt: Long? = null,
)
data class TopicSpanRow(val id: Long = 0,
    val topicId: Long, val startAt: Long, val endAt: Long? = null)
data class TopicMarkRow(val id: Long = 0, val topicId: Long, val at: Long)
data class TopicGroup(val row: TopicRow,
    val spans: List<TopicSpanRow>,
    val marks: List<TopicMarkRow>) {
    fun topic() = Topic(row.id, TopicKind.valueOf(row.kind), row.name, TopicStatus.valueOf(row.status), row.createdAt,
        row.plannedStartAt, row.plannedEndAt, TopicPolicy.days(row.repeatRule),
        spans.sortedBy { it.startAt }.map { TopicSpan(it.id, it.startAt, it.endAt) },
        marks.sortedBy { it.at }.map { TopicMark(it.id, it.at) }, row.eventId, row.cardId, row.error, row.revision, row.quietOverride)
    fun deadline(): Long = row.plannedEndAt ?: ((spans.minOfOrNull { it.startAt } ?: row.createdAt) + TopicPolicy.MAX_DURATION)
}
