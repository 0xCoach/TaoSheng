@file:OptIn(kotlin.time.ExperimentalTime::class)
package app.resound.digest.core

data class TranscriptLineData(val id: Long = 0, val segmentId: Long,
    val startAt: Long, val endAt: Long, val text: String, val speakerTag: String?, val speakerName: String? = null,
    /** 整理进了哪件事；没整理或没归进事件为 null */
    val eventId: Long? = null,
    /** 整理过的时间；null 表示还没整理 */
    val digestedAt: Long? = null, val speakerScore: Float? = null,
    val speakerPersonId: Long? = null,
    val speakerManual: Boolean = false,
    val voiceInputJson: String? = null, val voiceInputPendingUntil: Long? = null,
    /** 本地同音纠正前的整句；未纠正为 null。 */
    val originalText: String? = null)

data class MemoryEventData(
    val id: Long = 0,
    val startAt: Long,
    val endAt: Long,
    val scene: String,
    val title: String,
    val summary: String,
    val pointsJson: String,
    val peopleJson: String,
    val place: String?,
    val emotion: String?,
    val intensity: Int?,
    val important: Boolean,
    val deleted: Boolean = false,
    val createdAt: Long,
    val model: String,
    val starred: Boolean = false,
    val starredAt: Long? = null,
    val editedAt: Long? = null,
    val originalTitle: String? = null,
    val originalSummary: String? = null,
    val originalPointsJson: String? = null,
)

data class MemoryItemData(
    val id: Long = 0,
    val eventId: Long?,
    val category: String,
    val kind: String?,
    val content: String,
    val dueText: String?,
    val dueAt: Long?,
    val who: String?,
    val lineId: Long?,
    val status: String,
    val createdAt: Long,
    val doneAt: Long? = null,
    val statusChangedAt: Long? = null,
    val sortOrder: Long? = null,
    val mergedInto: Long? = null,
    val noMerge: Boolean = false,
    val deleted: Boolean = false,
    val editedAt: Long? = null,
    val doneHintLineId: Long? = null,
    val doneHintAt: Long? = null,
    val dismissedDoneLinesJson: String = "[]",
    val reminderVersion: Long = 0,
    val origin: String = "digest",
    val sourceText: String? = null,
    /** 到点强提醒：弹出、响铃、震动（用户在待办卡或编辑里打开） */
    val strongReminder: Boolean = false,
)

data class ReviewInput(
    val events: List<MemoryEventData>,
    val items: List<MemoryItemData>,
    val starredStale: List<MemoryEventData> = emptyList(),
)
