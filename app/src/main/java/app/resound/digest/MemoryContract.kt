package app.resound.digest

import app.resound.transcribe.TranscriptLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/*
 * “今天”页与大模型整理之间的约定。
 */

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

interface DigestSettings {
    /** 卡 130：整理时把每段原话当时的地点和环境音一起交给大模型（位置会发给大模型服务商，默认关） */
    val includeSituation: kotlinx.coroutines.flow.StateFlow<Boolean> get() = kotlinx.coroutines.flow.MutableStateFlow(false)
    fun setIncludeSituation(on: Boolean) {}
    fun headers(presetId: String): List<HeaderEntry> = emptyList()
    fun saveHeaders(presetId: String, headers: List<HeaderEntry>) {}
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
