package app.resound.recordings

import java.time.LocalDate
import kotlinx.coroutines.flow.StateFlow

/*
 * 卡 072 录音批量导出：界面（Claude）与底层（Codex）的约定。改签名需双方确认。
 * 录音推送不在这里，是推送通道订阅里的一项（见 push/PushContract.kt 的 AudioPush）。
 * “录音”指手机里保存的原始录音段（OGG 原样，不转码）。
 */

// ---------- 批量导出（打包成 zip） ----------

/** 导出哪段时间：从 [from] 当天 [fromTime] 到 [to] 当天 [toTime]（按录音开始时刻，闭区间）；时刻默认整天。只含原声还在的段 */
data class RecordingRange(val from: LocalDate, val to: LocalDate,
    val fromTime: java.time.LocalTime = java.time.LocalTime.MIN, val toTime: java.time.LocalTime = java.time.LocalTime.MAX) {
    val wholeDays get() = fromTime == java.time.LocalTime.MIN && toTime == java.time.LocalTime.MAX
    internal fun startMillis() = from.atTime(fromTime).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    internal fun endMillis() = to.atTime(toTime).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
}

data class RecordingPackOptions(
    /** 附上原话：每段一个同名 .txt，外加一份总表 原话.txt（时间、时长、文件名、原话） */
    val withText: Boolean = true,
    /** 跳过媒体（电视、视频）和环境音判断为已跳过的段 */
    val skipMedia: Boolean = false,
)

data class RecordingPackEstimate(val days: Int, val segments: Int, val bytes: Long, val audioMs: Long)

sealed interface RecordingPackState {
    data class Running(val fraction: Float, val done: Int, val total: Int) : RecordingPackState
    /** 已保存到 下载/Resound/录音包/ 下；name 为文件名 */
    data class Done(val name: String, val bytes: Long, val segments: Int) : RecordingPackState
    data class Failed(val message: String) : RecordingPackState
    data object Cancelled : RecordingPackState
}

interface RecordingPacker {
    /** 原声还在的日子（新的在前），给日期选择用 */
    val availableDays: StateFlow<List<LocalDate>>
    suspend fun estimate(range: RecordingRange, options: RecordingPackOptions): RecordingPackEstimate
    /** 后台打包；同一时间一个。返回任务 id */
    fun start(range: RecordingRange, options: RecordingPackOptions): String
    fun cancel(id: String)
    /** 最近一个任务的状态（没有为 null） */
    val current: StateFlow<Pair<String, RecordingPackState>?>
    /** 打好的包（content:// 地址），给分享用；不存在为 null */
    fun shareUri(id: String): android.net.Uri?
}
