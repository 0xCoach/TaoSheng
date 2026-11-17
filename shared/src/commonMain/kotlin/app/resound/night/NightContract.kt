package app.resound.night

import app.resound.capture.QuietRule
import app.resound.platform.FileRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/*
 * 卡 202 夜里：界面与底层之间的约定。界面（Claude）只依赖这里的类型；底层（Codex）负责实现。
 * 改动这里的签名需要双方确认。时间一律毫秒时间戳；“一夜”的日期是醒来那一天。
 * 夜里不记环境音：只有鼾声、咳嗽、梦话、睡眠时间，响度只给数字不给名字。
 */

/** 这一段时间用的跑法 */
@Serializable
enum class NightMode(val title: String) {
    /** 每个窗口都跑模型 */
    Full("完整识别"),
    /** 夜里只存候选片段，整理时再认 */
    Deferred("先存后认"),
    /** 电量低：只记响度曲线和睡眠时间 */
    TimesOnly("只记时间"),
    /** 电量很低：停止旁路 */
    Off("已停止"),
}

@Serializable
enum class NightState {
    /** 夜里时间段内，还没进入 */
    Waiting,
    /** 暂定进入，20 分钟内没再用手机才确认 */
    Provisional,
    /** 已确认，正在记录 */
    Active,
    /** 这一夜已结束，正在整理（先存后认时可能要等到充电） */
    Processing,
    Ready,
    /** 记录中断后没能恢复，只有部分结果 */
    Interrupted,
}

@Serializable
enum class NightConfidence(val title: String) { Good("好"), Fair("一般"), Rough("仅供参考") }

@Serializable
enum class NightEventKind {
    Snore,
    /** 对外叫“疑似咳嗽/清嗓” */
    Cough,
    /** 梦话（声纹认出是你）或夜间人声；[NightEvent.mine] 区分 */
    SleepTalk,
    /** 醒来的一段（亮屏、被拿起） */
    Awake,
    /** 手机自己在放声音 */
    Playback,
    /** 没有记录：被杀、关机、停录、麦克风被占；原因在 [NightEvent.text] */
    Gap,
    /** 手机在床上时的轻微晃动 */
    Turn,
    /** 卡 203：鼾声中断（不写医学词） */
    SnoreBreak,
}

@Serializable
enum class Feedback { None, Yes, No }

@Serializable
data class NightSettings(
    val enabled: Boolean = false,
    /** 整晚完整识别：在充电或电量 ≥ 30% 时每段都跑模型 */
    val fullNight: Boolean = true,
    /** 夜里时间段，一天里的分钟数；结束小于等于开始表示跨午夜 */
    val windowStartMinute: Int = 21 * 60,
    val windowEndMinute: Int = 12 * 60,
    /** 同屋有人：文字写“卧室里的鼾声” */
    val sharedRoom: Boolean = false,
    /** 推送（一天的线索、day_context）里包含“昨晚” */
    val includeInPush: Boolean = true,
    val lowBatteryReminder: Boolean = true,
    /** 卡 203 */
    val weeklyReport: Boolean = true,
    val dreamPrompt: Boolean = true,
    val personalModel: Boolean = true,
    val breakReminder: Boolean = true,
)

/** 用户改过的以用户为准；[onsetEstimated] 为 true 时界面写“约” */
@Serializable
data class NightTimes(
    val bedtime: Long,
    val onset: Long,
    val wake: Long,
    val onsetEstimated: Boolean,
    val editedBedtime: Boolean = false,
    val editedOnset: Boolean = false,
    val editedWake: Boolean = false,
)

@Serializable
data class NightEvent(
    val id: Long,
    val kind: NightEventKind,
    val start: Long,
    val end: Long,
    /** 最响处比背景高多少分贝；没有声音的事件（醒来、中断）为 null */
    val aboveBackgroundDb: Int? = null,
    /** 鼾声/咳嗽的片段；没有或已删除为 null */
    val clipId: Long? = null,
    /** 梦话引用的现有录音段 */
    val segmentId: Long? = null,
    /** 梦话转写文字（未转完为 null）、中断原因、醒来时“看手机 6 分”等 */
    val text: String? = null,
    /** 梦话：声纹认出是你 */
    val mine: Boolean = false,
    val feedback: Feedback = Feedback.None,
    /** 被挑进早上最多 3 段确认 */
    val asked: Boolean = false,
    /** 录音还在（没过 7 天或已确认）才能回听 */
    val audioAvailable: Boolean = false,
)

/** 每 [stepMillis]（5 秒）一个点，单位 dBFS 取整；三个数组等长 */
@Serializable
class NightEnvelope(
    val start: Long,
    val stepMillis: Long,
    val peakDb: ByteArray,
    val medianDb: ByteArray,
    val backgroundDb: ByteArray,
)

@Serializable
data class NightModeSpan(val start: Long, val end: Long, val mode: NightMode)

@Serializable
data class NightSummary(
    val id: Long,
    /** 醒来那一天 */
    val date: LocalDate,
    val state: NightState,
    val times: NightTimes?,
    val sleepMillis: Long?,
    /** 只数 ≥ 2 分钟的醒来 */
    val awakeCount: Int,
    val awakeMillis: Long,
    val snoreMillis: Long,
    val coughCount: Int,
    val talkCount: Int,
    val confidence: NightConfidence?,
    /** 如“背景较吵”“2:10–2:40 没有记录”“没听到睡眠声音，确认手机放在床边” */
    val reasons: List<String>,
    /** 早上那句话；整理完才有 */
    val sentence: String?,
    val modes: List<NightModeSpan>,
    /** 卡 203 */
    val snoreBreaks: Int = 0,
    val tags: List<NightTag> = emptyList(),
)

@Serializable
data class NightDetail(
    val summary: NightSummary,
    /** 按开始时间排 */
    val events: List<NightEvent>,
    val envelope: NightEnvelope?,
    /** 早上要问的事件 id，最多 3 个，按把握从小到大 */
    val questions: List<Long>,
    /** 卡 203 */
    val suggestedTags: List<NightTag> = emptyList(),
    val dreams: List<Dream> = emptyList(),
    /** 鼾声中断提示（30 天最多一次），没有为 null */
    val breakNotice: String? = null,
    /** 个人判断否决掉的事件 id（仍在 events 里，可纠正） */
    val vetoed: Set<Long> = emptySet(),
)

@Serializable
data class NightLive(val state: NightState, val mode: NightMode?, val since: Long?)

interface NightRepository {
    val settings: StateFlow<NightSettings>
    /** 打开总开关时若环境音模型未下载会自动下载 */
    fun update(settings: NightSettings)

    /** 启用的停录规则与夜里时间段重叠时非 null，界面据此询问要不要关掉那条停录 */
    val quietConflict: StateFlow<QuietRule?>

    val live: StateFlow<NightLive>

    /** 含两端，新的在前 */
    fun nights(from: LocalDate, to: LocalDate): Flow<List<NightSummary>>

    /** 醒来那天的一夜；没有为 null */
    fun night(date: LocalDate): Flow<NightDetail?>

    /** “不是”：事件从统计去掉、录音立即删除、分数留作反馈；“是”：片段长期保存 */
    suspend fun feedback(eventId: Long, value: Feedback)

    /** 传 null 的项不改；改完重算并触发线索重写 */
    suspend fun editTimes(nightId: Long, bedtime: Long?, onset: Long?, wake: Long?)

    /** 隐私删除：记录、片段一并删，并重写那天的线索 */
    suspend fun deleteNight(nightId: Long)

    /** 回听用；录音已删为 null */
    suspend fun audio(clipId: Long): FileRef?
}

// ---------------- 卡 203 ----------------

/** [preset] 为 true 的是预置标签，不能删 */
@Serializable
data class NightTag(val id: Long, val name: String, val preset: Boolean)

/** 记梦：说的（segmentIds 转写后有 text）或写的 */
@Serializable
data class Dream(val id: Long, val nightId: Long, val at: Long, val text: String?, val segmentIds: List<Long>, val transcribing: Boolean)

@Serializable
enum class CompareMetric(val title: String) { Snore("鼾声"), Sleep("睡眠"), Latency("入睡用时"), Awake("醒来次数") }

/** “标了‘侧睡’的 5 晚，鼾声中位数比其他晚少 38 分钟（可能有关，样本还少）” */
@Serializable
data class TagFinding(val tag: NightTag, val metric: CompareMetric, val withNights: Int, val withoutNights: Int,
    val withMedian: Long, val withoutMedian: Long, val text: String)

@Serializable
data class WeeklyReport(val weekStart: LocalDate, val nights: Int, val markdown: String,
    val finding: String?, val suggestion: String?, val polished: Boolean, val createdAt: Long)

@Serializable
enum class PersonalKind { Snore, Cough }
@Serializable
data class PersonalModelStatus(val kind: PersonalKind, val yes: Int, val no: Int, val active: Boolean,
    /** 交叉验证准确率 0..1；没训练过为 null */
    val accuracy: Float?, val ruleAccuracy: Float?, val trainedAt: Long?)

/** 趋势图的一晚 */
@Serializable
data class NightPoint(val date: LocalDate, val bedtime: Long?, val onset: Long?, val wake: Long?, val sleepMillis: Long?,
    val snoreMillis: Long, val coughCount: Int, val awakeCount: Int, val snoreBreaks: Int,
    val confidence: NightConfidence?, val tags: List<String>)

data class DoctorReport(val from: LocalDate, val to: LocalDate, val markdown: String, val clips: List<FileRef>)

interface NightInsights {
    val tags: Flow<List<NightTag>>
    suspend fun createTag(name: String): Result<NightTag>
    suspend fun deleteTag(tagId: Long)
    /** 打标签或取消；也用于确认建议 */
    suspend fun setTag(nightId: Long, tagId: Long, on: Boolean)
    suspend fun dismissSuggestion(nightId: Long, tagId: Long)

    /** 最近 60 晚的对比结论，没有为空 */
    fun findings(): Flow<List<TagFinding>>

    /** 新的在前 */
    fun weeklyReports(): Flow<List<WeeklyReport>>

    val personal: Flow<List<PersonalModelStatus>>

    /** 之后 3 分钟内录下的人声归为这一夜的梦 */
    suspend fun startDream(nightId: Long): Dream
    suspend fun writeDream(nightId: Long, text: String): Dream
    suspend fun deleteDream(dreamId: Long)

    /** 最响 15 秒 m4a；没有鼾声片段为 null；第二项 true 表示片段没确认过 */
    suspend fun loudestClip(nightId: Long): Pair<FileRef, Boolean>?

    /** 鼾声中断提示“不再提示” */
    suspend fun muteBreakNotice()

    /** 含两端，按日期升序 */
    fun trend(from: LocalDate, to: LocalDate): Flow<List<NightPoint>>
    suspend fun doctorReport(from: LocalDate, to: LocalDate): DoctorReport
}
