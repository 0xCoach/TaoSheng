package app.resound.skills

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/*
 * 技能与界面之间的约定。界面（Claude）只依赖这里；底层（Codex）实现。改签名需双方确认。
 *
 * 技能 = 一份“说明”，不含程序：什么时候跑（触发）、用哪些数据（范围）、让大模型怎么写（要求）、
 * 卡片上有哪些内容（积木字段）、结果放哪（卡片 / 推送）。官方技能放 assets/skills/<id>/，
 * 用户改过的部分和自建技能单独存，官方默认永远不被覆盖。
 */

/** 技能来源 */
enum class SkillOrigin {
    /** 官方技能（assets 内置） */
    Builtin,

    /** 从官方复制出来改的“我的版本”，或自己新建的 */
    Mine,

    /** 已有的核心功能（时间线整理、每日回顾），列在技能页里，设置仍走原来的页面 */
    Core,
}

/** 什么时候跑 */
sealed interface SkillTrigger {
    /** 每天固定时间；weekdays 为空表示每天，否则只在这些星期几（1=周一 … 7=周日） */
    data class Daily(val minutes: Int, val weekdays: Set<Int> = emptySet()) : SkillTrigger

    /** 每轮整理之后，看新整理出来的内容（如会议）；最多 [minGapMinutes] 分钟跑一次 */
    data class AfterDigest(val minGapMinutes: Int = 60) : SkillTrigger

    /** 只在手动点“生成”时跑 */
    data object Manual : SkillTrigger
}

/** 用哪些数据 */
data class SkillScope(
    /** 时间范围 */
    val range: SkillRange = SkillRange.Day,
    /** 原话里只用“我”说的，还是所有人的 */
    val speakers: SkillSpeakers = SkillSpeakers.All,
    /** 要不要带原话（带了更准、更贵）；不带只用事件和条目 */
    val includeLines: Boolean = true,
)

enum class SkillRange { Day, Week, SinceLastRun }

enum class SkillSpeakers { MeOnly, All }

/** 卡片积木：卡片上一项内容的类型 */
enum class FieldType {
    /** 一句话 */
    Text,

    /** 一段话 */
    Paragraph,

    /** 要点列表 */
    Bullets,

    /** 原话引用（带说话人、时间，点了跳到原话） */
    Quotes,

    /** 数字 + 单位，如 “3 小时” */
    Number,

    /** 标签 */
    Tags,

    /** 勾选清单（如明日计划） */
    Checklist,

    /** 1～5 分 */
    Rating,
}

/** 卡片上的一项 */
data class SkillField(
    /** 英文小写+下划线，如 decisions；旧卡片存了生成时的字段，删改字段不影响旧卡 */
    val key: String,
    /** 给人看的名字，如“结论” */
    val label: String,
    val type: FieldType,
    /** 给大模型的一句话：这一项写什么 */
    val hint: String,
    /** 必填项为空时这张卡不出 */
    val required: Boolean = false,
)

/** 一个技能当前生效的样子（官方默认 + 用户改动合并后） */
data class Skill(
    /** 英文小写+下划线，如 meeting；我的版本以 my_ 开头 */
    val id: String,
    val name: String,
    /** 一句话说明，显示在技能列表 */
    val summary: String,
    /** 图标名，界面按名字选图标：meeting / sunset / calendar_week / review / timeline / mic / heart / bulb / chat / star */
    val icon: String,
    val origin: SkillOrigin,
    /** 技能格式版本，字段结构变化时 +1；卡片记下生成时的版本 */
    val version: Int,
    val enabled: Boolean,
    val trigger: SkillTrigger,
    val scope: SkillScope,
    val fields: List<SkillField>,
    /** 每天最多几张卡（会议可多张，收工打卡 1 张） */
    val maxCardsPerDay: Int,
    /** 定时生成后是否推送 */
    val push: Boolean,
    /** 用哪个大模型服务（LlmPreset.id）；null 跟随“整理模型” */
    val presetId: String?,
    /** 要求（提示词）改过 */
    val promptCustomized: Boolean,
    /** 这个技能有官方新版本可合并（仅 Mine 中从官方复制来的） */
    val upstreamUpdated: Boolean = false,
)

/** 卡片上一项的值；按 FieldType 只用对应的那个属性 */
data class FieldValue(
    val text: String? = null,
    val items: List<String> = emptyList(),
    /** Quotes：每条引用 */
    val quotes: List<CardQuote> = emptyList(),
    val number: Double? = null,
    val unit: String? = null,
    /** Checklist：和 items 一一对应 */
    val checked: List<Boolean> = emptyList(),
    /** Rating：1～5 */
    val rating: Int? = null,
)

data class CardQuote(
    /** transcript_lines.id；没有为 null */
    val lineId: Long?,
    /** “我” / 人名 / “说话人2” / “待确认” */
    val speaker: String,
    val at: Long,
    val text: String,
)

/** 一张技能卡 */
data class SkillCard(
    val id: Long,
    val skillId: String,
    /** 生成时技能的名字和图标（技能后来改名或删除，旧卡照样显示） */
    val skillName: String,
    val icon: String,
    /** 归属日期 */
    val date: LocalDate,
    /** 卡片在这天里的时间（会议为开始时间，定时卡为生成时刻），时间线按它插入 */
    val at: Long,
    /** 会议这类有时段的卡：结束时间 */
    val endAt: Long?,
    val title: String,
    /** 一句话摘要（推送和列表用） */
    val summary: String,
    /** 生成时的字段定义（按这个显示，不受技能后来改字段影响） */
    val fields: List<SkillField>,
    val values: Map<String, FieldValue>,
    /** 依据的原话 id，详情底部“原话出处”用 */
    val evidenceLineIds: List<Long>,
    val generatedAt: Long,
    /** 手动生成的（非定时） */
    val manual: Boolean,
)

/** 一次运行的结果 */
sealed interface SkillRunResult {
    /** 出了 n 张卡（试跑时 cards 只是预览，没存） */
    data class Cards(val cards: List<SkillCard>) : SkillRunResult

    /** 没有够格的内容，不出卡（给人看的原因，如“今天没有会议”） */
    data class Nothing(val reason: String) : SkillRunResult

    /** 失败（给人看的原因，如“还没填写大模型密钥”“网络不通”） */
    data class Failed(val reason: String) : SkillRunResult
}

/** 技能运行情况 */
data class SkillStatus(
    val skillId: String,
    /** 正在跑（包括试跑） */
    val running: Boolean,
    val lastRunAt: Long?,
    /** 上次结果一句话：“出了 2 张卡” / “今天没有会议” / 失败原因 */
    val lastResult: String?,
    val lastFailed: Boolean,
    /** 下次定时运行时间；不定时为 null */
    val nextRunAt: Long?,
    /** 最近 30 天用量（token） */
    val tokens30d: Long,
)

/** 智能生成的草稿：技能定义 + 要求，还没保存；界面填进编辑器给人看过再存 */
data class SkillDraft(val skill: Skill, val prompt: String)

/** 智能生成对话里的一句：fromUser=true 是用户说的，否则是大模型的追问 */
data class DraftTurn(val fromUser: Boolean, val text: String)

/** 智能生成的一步：信息不够就追问（附 2～4 个可直接点的选项），够了就给出草稿 */
sealed interface DraftStep {
    data class Ask(val question: String, val options: List<String>) : DraftStep
    data class Done(val draft: SkillDraft) : DraftStep
}

interface SkillRepository {
    /** 全部技能（Core 在前，然后官方，然后我的），按显示顺序 */
    val skills: StateFlow<List<Skill>>

    val status: StateFlow<Map<String, SkillStatus>>

    /** 某天的卡片（按 at 排序） */
    fun cards(date: LocalDate): Flow<List<SkillCard>>

    /** 某技能的历史卡片（新的在前，最多 200 张） */
    fun cardsOf(skillId: String): Flow<List<SkillCard>>

    fun card(id: Long): Flow<SkillCard?>

    /** 有卡片的技能 id（记忆按类的胶囊用），按最近出卡排序 */
    val skillsWithCards: Flow<List<String>>

    fun setEnabled(skillId: String, enabled: Boolean)

    /** 只对 Daily 有效：改时间、星期 */
    fun setSchedule(skillId: String, minutes: Int, weekdays: Set<Int>)

    fun setPush(skillId: String, push: Boolean)

    fun setPreset(skillId: String, presetId: String?)

    /** 当前生效的要求（提示词）和默认要求 */
    fun prompt(skillId: String): String
    fun defaultPrompt(skillId: String): String
    fun savePrompt(skillId: String, text: String): Result<Unit>
    fun resetPrompt(skillId: String)

    /**
     * 给某天生成（正式，存库）。同一实体（同一场会、同一天的收工卡）沿用旧卡更新，不重复出卡。
     * 返回结果同时更新 status。
     */
    suspend fun runNow(skillId: String, date: LocalDate): SkillRunResult

    /** 只用 [from, to] 这段时间的原话跑一次（手动速记用），正式存库；不受每天自动次数限制 */
    suspend fun runWindow(skillId: String, from: Long, to: Long): SkillRunResult = SkillRunResult.Failed("暂不支持")

    /** 试跑：用某天的真实数据跑一次，只返回预览，不存库、不推送；可传入还没保存的要求 */
    suspend fun trial(skillId: String, date: LocalDate, promptOverride: String? = null): SkillRunResult

    fun deleteCard(id: Long)

    /** 把官方技能复制成“我的版本”，返回新 id */
    fun copyAsMine(skillId: String): Result<String>

    /** 保存“我的版本”整份定义（改字段、触发、范围等）；id 是新的 my_ 开头时即新建 */
    fun saveMine(skill: Skill, prompt: String): Result<Unit>

    /** 删除“我的版本”（已出的卡保留） */
    fun deleteMine(skillId: String)

    /**
     * 智能生成：按目前为止的对话（第一句是用户的描述）起草新技能，用整理模型。
     * 关键信息缺失时返回追问；用户回答 3 轮后一定给出草稿。不保存；失败给人看的原因。
     */
    suspend fun draft(conversation: List<DraftTurn>): Result<DraftStep>
}
