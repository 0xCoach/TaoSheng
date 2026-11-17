package app.resound.night

import app.resound.capture.QuietRule
import app.resound.platform.FileRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate

/*
 * 卡 202 夜里：界面与底层之间的约定。界面（Claude）只依赖这里的类型；底层（Codex）负责实现。
 * 改动这里的签名需要双方确认。时间一律毫秒时间戳；“一夜”的日期是醒来那一天。
 * 夜里不记环境音：只有鼾声、咳嗽、梦话、睡眠时间，响度只给数字不给名字。
 */

/** 这一段时间用的跑法 */
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

enum class NightConfidence(val title: String) { Good("好"), Fair("一般"), Rough("仅供参考") }

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
}

enum class Feedback { None, Yes, No }

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
)

/** 用户改过的以用户为准；[onsetEstimated] 为 true 时界面写“约” */
data class NightTimes(
    val bedtime: Long,
    val onset: Long,
    val wake: Long,
    val onsetEstimated: Boolean,
    val editedBedtime: Boolean = false,
    val editedOnset: Boolean = false,
    val editedWake: Boolean = false,
)

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
class NightEnvelope(
    val start: Long,
    val stepMillis: Long,
    val peakDb: ByteArray,
    val medianDb: ByteArray,
    val backgroundDb: ByteArray,
)

data class NightModeSpan(val start: Long, val end: Long, val mode: NightMode)

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
)

data class NightDetail(
    val summary: NightSummary,
    /** 按开始时间排 */
    val events: List<NightEvent>,
    val envelope: NightEnvelope?,
    /** 早上要问的事件 id，最多 3 个，按把握从小到大 */
    val questions: List<Long>,
)

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
