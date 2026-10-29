package app.resound.transcribe

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/*
 * 转写功能的界面约定。界面（Claude）只依赖这里的类型；底层（Codex）负责实现。
 * 改动签名需要双方确认。
 */

enum class TranscriptStatus {
    /** 等待转写 */
    Pending,

    /** 正在上传 / 识别 */
    Running,

    /** 已转写 */
    Done,

    /** 识别完成但没有文字（如只有噪音） */
    Empty,

    /** 失败，会自动重试；超过次数后停在这里等手动重试 */
    Failed,

    /** 手机用扬声器外放媒体时录的：不转写（可手动“仍然转写”），录音 1 天后删除 */
    SkippedPlayback,

    /** 卡 065：录音文件丢失、损坏或解码失败，重试也修不好：不再提醒，可删除；原声按保留天数正常清理 */
    Abandoned,

    /** 卡 065：还能修的失败，但用户点了“忽略”或已失败超过 3 天：不再提醒，列表里灰色显示，可手动重试或删除 */
    Quiet,
}

/** 转写出来的一句话 */
data class TranscriptLine(
    val id: Long,
    /** 绝对时间（毫秒时间戳） */
    val startAt: Long,
    val endAt: Long,
    val text: String,
    /** 云端给的临时说话人编号，只在同一次请求内有意义；没有为 null */
    val speakerTag: String?,
    /** 本地声纹认出的人名（例如“我”“老王”）；认人功能上线前一直为 null */
    val speakerName: String? = null,
    /** 声纹相似度 0..1（和 speakerName 对应的人比）；没比对为 null。界面据此显示“我”或“可能是我？” */
    val speakerScore: Float? = null,
    val segmentId: Long = 0,
    /** 卡 048：这句来自语音输入（不是录音转写）时的来源和上下文；录音转写的为 null */
    val voiceInput: VoiceInputMeta? = null,
)

/** 卡 048：语音输入的来源与当时屏幕上的上下文 */
data class VoiceInputMeta(
    /** 应用名，如“微信” */
    val app: String,
    val packageName: String,
    /** 界面标题，如聊天对象“老王”；读不到为 null */
    val title: String?,
    /** 语音输入前屏幕上的最近内容（最多二十来行）；没开上下文或读不到为 null */
    val before: String?,
    /** 发出后 2 分钟内同一界面新出现的内容；没有为 null */
    val after: String?,
)

/** 一段录音及其转写结果 */
data class SegmentTranscript(
    val segmentId: Long,
    val startAt: Long,
    val endAt: Long,
    val status: TranscriptStatus,
    val lines: List<TranscriptLine>,
    /** 失败时给人看的原因（不含密钥） */
    val error: String? = null,
    /** 录音文件是否还在（转写成功 1 天后删除） */
    val audioAvailable: Boolean,
)

data class TranscribeQueueState(
    val pending: Int,
    val failed: Int,
    val running: Boolean,
    val lastRunAt: Long?,
    val nextRunAt: Long?,
    val lastError: String?,
    /** 今天已送去识别的音频秒数（用于估算费用） */
    val usedSecondsToday: Long,
    /** 累计已送去识别的音频秒数 */
    val usedSecondsTotal: Long,
    /** 本轮要转写的段数（开始时确定）；不在转写时为 0。界面用来画真实进度 */
    val runTotal: Int = 0,
    /** 本轮已处理完的段数（成功、没文字、失败都算） */
    val runDone: Int = 0,
)

interface TranscriptRepository {
    /** 某一天的全部录音段（按开始时间倒序），含转写状态和文字 */
    fun day(date: LocalDate): Flow<List<SegmentTranscript>>

    val queue: StateFlow<TranscribeQueueState>

    /** 立即跑一轮（不等定时） */
    fun runNow()

    /** 把停在 Failed 的段重新放回队列 */
    fun retryFailed()

    /** 外放时录的片段，用户要求仍然转写：放回队列并立即跑一轮 */
    fun transcribeAnyway(segmentId: Long) {}

    /** 卡 065：忽略现在提醒里的所有失败段（变成 Quiet，不再计入 failed），以后仍可手动重试 */
    fun ignoreFailed() {}

    /** 卡 065：重试某一段（Quiet / Abandoned 也可试） */
    fun retrySegment(segmentId: Long) {}

    /** 卡 065：删除一段没转成功的录音（文件和记录），只对 Failed / Quiet / Abandoned 有效 */
    fun deleteFailed(segmentId: Long) {}
}

/** 转写服务设置。密钥只进系统安全区，任何地方都不回显。 */
/** 自动转写间隔范围（分钟）；下限是安卓后台定时的最短周期。 */
const val MIN_INTERVAL = 15
const val MAX_INTERVAL = 720

interface TranscribeSettings {
    /** 是否已填密钥 */
    val hasKey: StateFlow<Boolean>

    /** 当前服务能否运行；本地模式不需要密钥。 */
    val ready: StateFlow<Boolean> get() = hasKey

    fun saveKey(key: String)

    fun clearKey()

    /** 定时间隔（分钟），常用 30 / 45 / 60，也可自定义 15～720（安卓后台定时最短 15 分钟） */
    val intervalMinutes: StateFlow<Int>

    fun setIntervalMinutes(minutes: Int)

    /** 只在 WiFi 下转写 */
    val wifiOnly: StateFlow<Boolean>

    fun setWifiOnly(value: Boolean)

    /** 用极短的测试音频检查密钥和服务是否可用；成功返回简短说明，失败返回给人看的原因 */
    suspend fun test(): Result<String>
}
