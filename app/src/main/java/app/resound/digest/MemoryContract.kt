package app.resound.digest

import app.resound.transcribe.TranscriptLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/*
 * “今天”页与大模型整理之间的约定。
 */

enum class ItemStatus {
    /** 刚提取，等用户确认 */
    Pending,

    /** 用户接受（待办进行中 / 约定已记下） */
    Accepted,

    /** 用户忽略 */
    Ignored,

    /** 已完成 */
    Done,
}

/** 提取项：category 是 待办 / 约定 / 备忘 / 灵感 / 关键信息 / 决定 / 知识 */
data class MemoryItem(
    val id: Long,
    val eventId: Long,
    val category: String,
    /** 仅待办：我要做 / 我答应别人 / 别人答应我 */
    val kind: String?,
    val content: String,
    /** 大模型给的时间文字，如 "2026-10-28 14:00" */
    val dueText: String?,
    val dueAt: Long?,
    val who: String?,
    val status: ItemStatus,
    /** 依据的那句原话 */
    val sourceLineId: Long?,
    /** 提取时间（毫秒），用于排序和显示 */
    val at: Long = 0,
    /** 完成时间；不是 Done 时为 null */
    val doneAt: Long? = null,
    /** 最近一次改状态的时间（接受、完成、撤回都算） */
    val statusChangedAt: Long? = null,
    /** “进行中”手动排序，越小越靠前；没排过为 null */
    val sortOrder: Long? = null,
    /** 相似灵感合并（卡 040）：这条代表的想法一共被说过几次（含被并进来的） */
    val repeats: Int = 1,
    /** 被并进了哪一条；为 null 表示自己是独立的一条（列表里只显示独立的） */
    val mergedInto: Long? = null,
)


/** 某一天的条目（不含 Ignored） */
data class DayItems(
    /** 当天提到的待办 / 约定 / 备忘 */
    val mentioned: List<MemoryItem>,
    /** 截止或约定时间落在当天的（可能是更早提到的） */
    val due: List<MemoryItem>,
    /** 当天完成的 */
    val done: List<MemoryItem>,
    /** 当天提到的灵感 / 关键信息 / 决定 / 知识 */
    val notes: List<MemoryItem>,
)

/** 一件事 */
data class MemoryEvent(
    val id: Long,
    val startAt: Long,
    val endAt: Long,
    /** 会议 / 上课 / 闲聊 / 电话 / 独白 / 媒体 / 旁人 / 其他 */
    val scene: String,
    val title: String,
    val summary: String,
    val points: List<String>,
    val people: List<String>,
    val place: String?,
    val emotion: String?,
    /** 1..5 */
    val intensity: Int?,
    /** 大模型判断的“要紧”（钱、健康、家人、承诺、重要决定），只作参考，不进重点列表 */
    val important: Boolean,
    val items: List<MemoryItem>,
    /** 用户亲手标的重点（卡 041）；只有用户能标、能取消 */
    val starred: Boolean = false,
    /** 标重点的时间 */
    val starredAt: Long? = null,
)

data class DigestState(
    /** 已转写、还没整理的句子数 */
    val pendingLines: Int,
    val running: Boolean,
    val lastRunAt: Long?,
    val lastError: String?,
    val tokensToday: Long,
)

interface MemoryRepository {
    /** 某一天的事件（按开始时间倒序） */
    fun events(date: LocalDate): Flow<List<MemoryEvent>>

    /** 一件事的原话 */
    fun eventLines(eventId: Long): Flow<List<TranscriptLine>>

    /** 等确认的待办、约定、备忘（新的在前） */
    fun pendingItems(): Flow<List<MemoryItem>>

    /** 跨天查条目：按类别、状态过滤（null 表示不限），新的在前 */
    fun items(categories: Set<String>, statuses: Set<ItemStatus>?): Flow<List<MemoryItem>>

    /** 改状态：转 Done 记 doneAt，离开 Done 清空；改为 Accepted 时放到“进行中”最前面 */
    fun setItemStatus(id: Long, status: ItemStatus)

    /** “进行中”：Accepted 的待办和备忘，按 sortOrder 升序，没排过的按提取时间新的在前 */
    fun inProgress(): Flow<List<MemoryItem>> = items(setOf("待办", "备忘"), setOf(ItemStatus.Accepted))

    /** 约定：Accepted 的约定，按时间先后（没有时间的排最后） */
    fun appointments(): Flow<List<MemoryItem>> = items(setOf("约定"), setOf(ItemStatus.Accepted))

    /** 拖动排序后，把“进行中”的完整顺序存下来 */
    fun reorder(ids: List<Long>) {}

    /** 某一天的条目 */
    fun dayItems(date: LocalDate): Flow<DayItems> = kotlinx.coroutines.flow.flowOf(DayItems(emptyList(), emptyList(), emptyList(), emptyList()))

    /** 某月里有内容（事件或条目）的日子，给月历打点 */
    fun activeDays(month: java.time.YearMonth): Flow<Set<LocalDate>> = kotlinx.coroutines.flow.flowOf(emptySet())

    fun setImportant(eventId: Long, important: Boolean)

    /** 用户标/取消重点（卡 041）。标上时，这件事里还在“待确认”的待办、备忘自动接受（进“进行中”） */
    fun setStarred(eventId: Long, starred: Boolean) {}

    /** 用户标了重点的事（跨天，按标重点时间新的在前，最多 500） */
    fun starredEvents(): Flow<List<MemoryEvent>> = kotlinx.coroutines.flow.flowOf(emptyList())

    /** 大模型标“要紧”的事（跨天，新的在前）；界面暂不用，保留给回顾等 */
    fun importantEvents(): Flow<List<MemoryEvent>> = kotlinx.coroutines.flow.flowOf(emptyList())

    /** 被并进这一条的其他灵感（每条带自己的原话出处），按时间先后 */
    fun mergedItems(itemId: Long): Flow<List<MemoryItem>> = kotlinx.coroutines.flow.flowOf(emptyList())

    /** 把一条从合并里拆出来，恢复成独立的一条；以后不再自动并回去 */
    fun unmerge(itemId: Long) {}

    fun deleteEvent(eventId: Long)

    val state: StateFlow<DigestState>

    /** 立即整理一轮（会先跑转写） */
    fun runNow()
}

/** 整理用的大模型服务 */
data class LlmPreset(val id: String, val name: String, val baseUrl: String, val model: String)

interface DigestSettings {
    val presets: List<LlmPreset>

    /** 当前选中的服务 id */
    val presetId: StateFlow<String>

    fun setPreset(id: String)

    /** 当前服务是否已填密钥 */
    val hasKey: StateFlow<Boolean>

    fun saveKey(key: String)

    fun clearKey()

    /** 发一句很短的话确认能用 */
    suspend fun test(): Result<String>
}
