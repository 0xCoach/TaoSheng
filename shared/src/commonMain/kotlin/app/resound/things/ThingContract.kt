package app.resound.things

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/*
 * 卡 079 寻物：界面与底层之间的约定。界面（Claude）只依赖这里的类型；底层（Codex）负责实现。
 * 改动这里的签名需要双方确认。
 *
 * 一样东西（Thing）有多条位置记录（ThingRecord）。每转写完一轮，就从新的原话里找“东西放哪了 / 借给谁 / 拿走 / 还了”，
 * 生成待确认的记录；用户确认后才算数（和待办的“待确认”一个逻辑）。
 */

/** 这条记录说的是哪种变化 */
enum class ThingAction(val title: String) {
    /** 放在某处（place 必填） */
    Placed("放在"),

    /** 借给某人（who 必填，place 可空） */
    Lent("借给"),

    /** 借出去的还回来了（place 可空：还回来放哪了） */
    Returned("还回来了"),

    /** 拿走 / 带出门，位置不明 */
    TakenAway("带走了"),
}

enum class ThingOwner(val title: String) { Mine("我的"), Family("家人的"), Others("别人的") }

enum class RecordStatus { Pending, Accepted, Ignored }

/** Live = 从日常原话里自动找到；Typed / Voice = 在寻物页手动打字 / 按住说话添加 */
enum class ThingOrigin { Live, Typed, Voice }

/** 拿不准是不是之前那样东西时，确认卡上问“是之前那把「钥匙」吗？” */
data class SameAsQuestion(val thingId: Long, val thingName: String)

data class ThingRecord(
    val id: Long,
    /** 已归到哪样东西；待确认且是新东西、或还在问 [sameAs] 时为 null */
    val thingId: Long?,
    /** 东西的名字（统一叫法，如“车钥匙”） */
    val name: String,
    val action: ThingAction,
    /** 放在哪，如“鞋柜第二个抽屉” */
    val place: String?,
    /** 借给谁 */
    val who: String?,
    val owner: ThingOwner,
    /** 说这句话的时间（毫秒） */
    val at: Long,
    /** 依据的那句原话；手动添加或原话已被删时为 null */
    val sourceLineId: Long?,
    /** 原话文字（入库时存一份，原话删了也能显示） */
    val sourceText: String?,
    val status: RecordStatus,
    val origin: ThingOrigin,
    /** 用户主动说“记一下 / 帮我记住”之类 */
    val explicit: Boolean = false,
    val sameAs: SameAsQuestion? = null,
)

data class Thing(
    val id: Long,
    val name: String,
    /** 别名，如 钥匙 / 车key */
    val aliases: List<String>,
    val owner: ThingOwner,
    /** 最新一条已确认的记录 */
    val latest: ThingRecord,
    /** 最近一次已确认的“放在”的位置；带走了、借出时给“最后在哪”用 */
    val lastPlace: String?,
    val lastPlaceAt: Long?,
    /** 已确认的记录条数（含 latest） */
    val records: Int,
    /** 还有等确认的新记录 */
    val hasPending: Boolean,
)

/** 手动添加时，模型拆出来的一条（还没入库）；字段含义同 [ThingRecord] */
data class ThingDraft(
    val name: String,
    val action: ThingAction,
    val place: String?,
    val who: String?,
    val owner: ThingOwner = ThingOwner.Mine,
    /** 模型认为就是已有的这样东西；null 为新东西 */
    val thingId: Long? = null,
)

data class ThingDrafts(
    val items: List<ThingDraft>,
    /** false = 没网、没填密钥或模型没给出可用结果，按原话生成了一条（名字=原话前 12 字，放在=空） */
    val parsed: Boolean,
)

/** 用户在确认卡上改过的内容 */
data class ThingEdit(
    val name: String,
    val action: ThingAction,
    val place: String?,
    val who: String?,
    val owner: ThingOwner,
)

interface ThingRepository {
    /** 自动从日常原话里找（默认开）；关掉后不再生成新的待确认，已有的不动 */
    val autoDetect: StateFlow<Boolean>
    fun setAutoDetect(on: Boolean)

    /** 全部东西，按最新记录时间新的在前（不含已删） */
    fun things(): Flow<List<Thing>>

    /** 按名字、别名、当前位置、借给谁模糊找（反着查“书柜里有什么”也走这里）；空串等同 [things] */
    fun search(query: String): Flow<List<Thing>>

    fun thing(id: Long): Flow<Thing?>

    /** 一样东西已确认的全部记录，新的在前 */
    fun history(thingId: Long): Flow<List<ThingRecord>>

    /** 待确认的记录，新的在前（3 天前的由界面收起） */
    fun pending(): Flow<List<ThingRecord>>

    /**
     * 确认一条。有 [ThingRecord.sameAs] 时：asNew=false 归到那样东西，true 新建一样；
     * 没有 sameAs 且 thingId 为 null 时新建。edit 不为空时按改过的内容入库（名字改了按新名字归并）
     */
    fun accept(recordId: Long, edit: ThingEdit? = null, asNew: Boolean = false)

    fun ignore(recordId: Long)

    /** 全部确认 / 全部忽略，一个事务；有 sameAs 的按 asNew=false 处理 */
    fun setAll(ids: List<Long>, status: RecordStatus)

    /** 手动添加：把一段话拆成几条，不入库。最多等 20 秒，超时按原话生成一条 */
    suspend fun draft(text: String): ThingDrafts

    /** 手动添加确认后直接入库（Accepted），记下来源和原话 */
    fun add(drafts: List<ThingDraft>, origin: ThingOrigin, sourceText: String)

    /** 借出的东西点“已还”：新增一条 Returned（Typed）；place 可空 */
    fun markReturned(thingId: Long, place: String? = null)

    fun rename(thingId: Long, name: String)

    fun setOwner(thingId: Long, owner: ThingOwner)

    /** 改一条已确认的记录 */
    fun editRecord(recordId: Long, edit: ThingEdit)

    /** 删一条记录；删掉后这样东西没有已确认记录了，就连东西一起删 */
    fun deleteRecord(recordId: Long)

    /** 删一样东西（连同全部记录）；以后同名的再出现算新东西 */
    fun deleteThing(thingId: Long)
}
