package app.resound.capture

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.LocalDate

/*
 * 界面与录音底层之间的约定。界面（Claude）只依赖这里的类型；底层（Codex）负责实现。
 * 改动这里的签名需要双方确认。
 */

enum class CaptureState {
    /** 服务没在跑 */
    Stopped,

    /** 在跑，没人说话 */
    Listening,

    /** 检测到人声，正在录这一段 */
    Recording,

    /** 用户手动暂停 */
    Paused,

    /** 麦克风被电话或其他 App 占用，等待释放后自动恢复 */
    MicBusy,

    /** 缺少麦克风或通知权限 */
    NoPermission,
}

data class CaptureStatus(
    val state: CaptureState,
    /** 进入当前状态的时间（毫秒时间戳） */
    val since: Long,
    /** 手动暂停时，计划恢复的时间；不暂停为 null */
    val pausedUntil: Long? = null,
    /** 当前音频来源的名字，例如 "手机麦克风" */
    val sourceName: String = "手机麦克风",
    /** 实时音量 0..1，给界面画声波，约每 100 毫秒更新 */
    val level: Float = 0f,
    /** 麦克风被占时的说明，例如“通话中”“可能是 小红书 或 讯飞输入法（语音输入）”；其他状态为 null */
    val busyDetail: String? = null,
    /** 卡 056：因停录时段而暂停时为该时段的名字（如“睡眠”），此时 state = Paused、pausedUntil = 时段结束；其他情况为 null */
    val quietName: String? = null,
)

interface CaptureController {
    val status: StateFlow<CaptureStatus>

    fun start()

    fun stop()

    /** 暂停若干分钟，到点自动恢复 */
    fun pause(minutes: Int)

    fun resume()
}

/** 某一天的汇总，供统计页使用 */
data class DayStats(
    val date: LocalDate,
    /** 录下的人声片段数 */
    val segmentCount: Int,
    /** 人声片段总时长 */
    val speechMillis: Long,
    /** 服务在跑（含 Listening / Recording / MicBusy）的总时长 */
    val runningMillis: Long,
    /** 被系统关掉的次数 */
    val killedCount: Int,
    /** 被关掉后自动恢复的次数 */
    val recoveredCount: Int,
    /** 被关掉到恢复之间漏掉的总时长 */
    val gapMillis: Long,
    /** 不充电时的平均耗电速度（每小时百分点）；数据不够为 null */
    val dischargePerHour: Float?,
    /** 录音文件占用空间 */
    val storageBytes: Long,
    /** 回响自身的耗电估算（每小时占电池容量的百分点），按 CPU 用时、麦克风开启时长、上传流量估算；数据不够为 null */
    val appPowerPerHour: Float? = null,
)

data class SegmentInfo(
    val id: Long,
    val startAt: Long,
    val endAt: Long,
    val sourceId: String,
    val file: File,
    val sizeBytes: Long,
)

enum class WatchdogEventType {
    ServiceStarted,
    ServiceStopped,
    /** 下次启动时发现上次是被系统关掉的（没有正常停止） */
    Killed,
    Recovered,
    Paused,
    Resumed,
    MicBusy,
    MicReleased,
    BootRestart,
    /** 卡 045：录音输入换了设备 */
    InputSwitched,
}

data class WatchdogEvent(
    val at: Long,
    val type: WatchdogEventType,
    /** 补充说明，例如被关掉的时长、恢复途径 */
    val detail: String? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
)

interface CaptureStatsRepository {
    fun dayStats(date: LocalDate): Flow<DayStats>

    fun segments(date: LocalDate): Flow<List<SegmentInfo>>

    fun watchdogEvents(date: LocalDate): Flow<List<WatchdogEvent>>

    /** 把最近几天的守护记录和统计导出成一个文本文件，方便发给开发排查 */
    suspend fun exportDiagnostics(): File
}

/** 小米设置引导中的一项 */
data class SetupItem(
    val key: String,
    val title: String,
    val description: String,
    /** true 已完成，false 未完成，null 无法自动判断（需用户自己确认） */
    val done: Boolean?,
)

interface SetupGuide {
    /** 每次调用都重新检查当前状态 */
    fun items(): List<SetupItem>

    /** 跳到对应的系统设置页 */
    fun open(key: String)

    /** 无法自动判断的项，用户点“我已设置”后记下来 */
    fun markDone(key: String)
}
