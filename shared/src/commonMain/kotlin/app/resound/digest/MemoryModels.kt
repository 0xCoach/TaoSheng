package app.resound.digest

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

/** 定到了具体几点（dueText 为 "YYYY-MM-DD HH:mm"）；只有日期或没时间为 false。强提醒只对这种开放 */
val MemoryItem.hasTime: Boolean get() = dueAt != null && (dueText?.length ?: 0) > 10
