package app.resound.digest

import app.resound.transcribe.TranscriptLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
    val eventId: Long?,
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
    /** 卡 046：用户手动改过（文字/时间/和谁）的时间；改过的条目重新整理时不被覆盖 */
    val editedAt: Long? = null,
    /** 卡 046：“可能做完了”——整理时发现后来有句话说明这件事已经做了；用户确认或不理会前一直挂着 */
    val doneHint: DoneHint? = null,
    val origin: ItemOrigin = ItemOrigin.Digest,
    val sourceText: String? = null,
    /** 到点强提醒：弹出、响铃、震动；关着时到点只发普通通知 */
    val strongReminder: Boolean = false,
)

/** 卡 046：后来听到的、说明已经做完的那句原话 */
data class DoneHint(val lineId: Long, val text: String, val at: Long)

/** 卡 046：约定的提前提醒（随备份）。和“到期提醒”走同一条路：手机通知 + 订阅了提醒的推送通道 */
data class AppointmentReminders(
    /** 头一天晚上提醒一次，时刻为 [eveningAt]（一天中的分钟数，默认 21:00） */
    val eveningBefore: Boolean = true,
    val eveningAt: Int = 21 * 60,
    /** 开始前多少分钟提醒；null 不提醒。界面可选 15 / 30 / 60 / 120 */
    val leadMinutes: Int? = 60,
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
    /** 会议 / 上课 / 闲聊 / 电话 / 独白 / 媒体 / 其他 */
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
    /** 卡 062：用户手动编辑过（标题/摘要/要点）的时间；没编辑为 null */
    val editedAt: Long? = null,
    /** 卡 062：编辑前整理出的原样；没编辑为 null。用于“已编辑”查看和恢复原样 */
    val original: EventOriginal? = null,
)

/** 卡 062：事件整理时的原样 */
data class EventOriginal(val title: String, val summary: String, val points: List<String>)

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
    /** 按原话 id 取那句和它所在录音段的全部原话（待办的原话所在事件还没整理出来、或待办没挂事件时用） */
    suspend fun sourceLines(lineId: Long): List<TranscriptLine> = emptyList()

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

    /** 卡 062：手动编辑事件的标题、摘要、要点。第一次编辑时保存原样；搜索、问问、记忆文件、推送随之更新。整理不会覆盖编辑过的事件 */
    fun editEvent(eventId: Long, title: String, summary: String, points: List<String>) {}

    /** 卡 062：恢复成整理时的原样，清掉 editedAt */
    fun restoreEvent(eventId: Long) {}

    /** 按 id 取一件事的当前内容（含编辑后的）；已删除或不存在为 null。问问的旧引用用它显示最新标题、打开详情 */
    suspend fun eventById(eventId: Long): MemoryEvent? = null

    // ---- 卡 046：待办改造 ----

    /** “进行中”：Accepted 的待办、约定、备忘（替代 inProgress + appointments）。界面把有 dueAt 的按时间排在上面，其余按 sortOrder */
    fun ongoing(): Flow<List<MemoryItem>> = items(setOf("待办", "约定", "备忘"), setOf(ItemStatus.Accepted))

    /** 已完成：doneAt 在 [from] 当天 0 点之后的待办/约定/备忘，按完成时间新的在前，不设 500 条上限 */
    fun doneItems(from: LocalDate): Flow<List<MemoryItem>> = items(setOf("待办", "约定", "备忘"), setOf(ItemStatus.Done))

    /** 删除一条（只删这条，事件和原话不动）。之后任何列表、回顾、问问、推送、提醒都不再出现；重新整理也不会再生成回来 */
    fun deleteItem(id: Long) {}

    /**
     * 手动修改；dueAt 为 null 表示去掉时间。hasTime=false 表示只定了哪天（dueAt 取当天 0 点，dueText 写 "YYYY-MM-DD"），
     * true 时 dueText 写 "YYYY-MM-DD HH:mm"。who 为空串或 null 表示不写。记 editedAt，提醒按新时间重排
     */
    fun updateItem(id: Long, content: String, dueAt: Long?, hasTime: Boolean, who: String?) {}

    /** 一条的最新内容（编辑、改状态后跟着变）；找不到（已删）给 null */
    fun item(id: Long): Flow<MemoryItem?> = items(setOf("待办", "约定", "备忘", "灵感", "关键信息", "决定", "知识"), null)
        .map { list -> list.firstOrNull { it.id == id } }

    /** 打开 / 关掉一条的到点强提醒 */
    fun setStrongReminder(id: Long, on: Boolean) {}

    /** 批量改状态（待确认的“全部接受 / 全部忽略”），一个事务 */
    fun setItemsStatus(ids: List<Long>, status: ItemStatus) { ids.forEach { setItemStatus(it, status) } }

    /** “可能做完了”不对：清掉提示，这条以后不再因同一句话提示 */
    fun dismissDoneHint(id: Long) {}

    val appointmentReminders: StateFlow<AppointmentReminders> get() = kotlinx.coroutines.flow.MutableStateFlow(AppointmentReminders())

    fun setAppointmentReminders(value: AppointmentReminders) {}

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

    /** 按功能逐项检测当前模型（连接、按格式回答、问问的流式回答/工具调用/标注出处）；没法开始检测（没填密钥等）给 failure */
    suspend fun check(): Result<List<ModelCheckItem>> = test().map { listOf(ModelCheckItem(ModelCheck.CONNECT, true, it)) }
}

/** 定到了具体几点（dueText 为 "YYYY-MM-DD HH:mm"）；只有日期或没时间为 false。强提醒只对这种开放 */
val MemoryItem.hasTime: Boolean get() = dueAt != null && (dueText?.length ?: 0) > 10
