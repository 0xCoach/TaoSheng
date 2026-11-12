package app.resound.topic

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class TopicSpan(val id: Long, val startAt: Long, val endAt: Long?)
data class TopicMark(val id: Long, val at: Long)
data class Topic(
    val id: Long, val kind: TopicKind, val name: String?, val status: TopicStatus,
    val createdAt: Long, val plannedStartAt: Long?, val plannedEndAt: Long?,
    val repeat: Set<DayOfWeek>, val spans: List<TopicSpan>, val marks: List<TopicMark>,
    val eventId: Long?, val cardId: Long?, val error: String?, val revision: Long,
    val quietOverride: Boolean,
)
data class TopicReplacedEvent(val id: Long, val title: String, val startAt: Long, val endAt: Long, val starred: Boolean)
data class TopicReplacePreview(
    val startAt: Long, val endAt: Long, val events: List<TopicReplacedEvent>,
    val changedTodoCount: Int, val starredEventCount: Int, val conflict: Boolean, val error: String? = null,
)
/** 失败不抛出冲突异常，界面直接显示 error；成功后可用 topicId 打开详情。 */
data class StartResult(val topicId: Long? = null, val quietOverride: Boolean = false,
    val recordingDisabled: Boolean = false, val error: String? = null)

interface TopicRepository {
    val current: StateFlow<Topic?>
    /** 全部专题记录，按录制时间倒序；不依赖事件已生成。 */
    fun observeHistory(): Flow<List<Topic>>
    fun observeProgress(topicId: Long): Flow<TopicProgress>
    fun observeDay(date: LocalDate): Flow<List<Topic>>
    fun observe(topicId: Long): Flow<Topic?>
    fun observeScheduled(): Flow<List<Topic>>
    suspend fun start(kind: TopicKind, name: String?, durationMinutes: Int? = null): StartResult
    suspend fun pause()
    suspend fun resume()
    suspend fun end()
    suspend fun extend(minutes: Int)
    suspend fun mark()
    /** 删除标错的重点（卡 169）；专题已取消时报错。删除后纪要不自动重写，界面提示可“重新生成” */
    suspend fun unmark(topicId: Long, markId: Long)
    suspend fun update(topicId: Long, kind: TopicKind, name: String?)
    suspend fun schedule(kind: TopicKind, name: String?, startAt: Long, endAt: Long, repeatDays: Set<DayOfWeek> = emptySet()): Result<Long>
    suspend fun cancelSchedule(topicId: Long)
    suspend fun previewFromEvents(ids: List<Long>): TopicReplacePreview
    suspend fun previewFromRange(start: Long, end: Long): TopicReplacePreview
    suspend fun createFromEvents(ids: List<Long>, kind: TopicKind, name: String?): Result<Long>
    suspend fun createFromRange(start: Long, end: Long, kind: TopicKind, name: String?): Result<Long>
    suspend fun cut(topicId: Long, start: Long, end: Long)
    suspend fun cancel(topicId: Long)
    suspend fun regenerate(topicId: Long)
}

/** 无 Result 返回值的方法发生冲突/状态不允许时抛此异常；message 是固定的可展示文案。 */
class TopicException(message: String) : IllegalStateException(message)
