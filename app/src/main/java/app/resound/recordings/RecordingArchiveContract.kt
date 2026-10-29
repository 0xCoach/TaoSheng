package app.resound.recordings

import java.time.LocalDate
import kotlinx.coroutines.flow.StateFlow

/*
 * 卡 072 录音批量导出与录音推送到坚果云：界面（Claude）与底层（Codex）的约定。改签名需双方确认。
 * “录音”指手机里保存的原始录音段（OGG 原样，不转码）。
 */

// ---------- 批量导出（打包成 zip） ----------

/** 导出哪几天：闭区间；只含原声还在的段 */
data class RecordingRange(val from: LocalDate, val to: LocalDate)

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

// ---------- 推送到坚果云 ----------

enum class SyncScope(val title: String) {
    All("全部录音"),
    /** 有人说话、已转写成功的段（跳过媒体和环境音已跳过的） */
    Speech("只传人声"),
}

data class RecordingSyncSettings(
    val enabled: Boolean = false,
    val scope: SyncScope = SyncScope.Speech,
    /** 转写好后把原话 .txt 一起传 */
    val withText: Boolean = true,
    /** 只在连 WiFi 时传 */
    val wifiOnly: Boolean = true,
    /** 只在充电时传 */
    val chargingOnly: Boolean = false,
)

data class RecordingSyncState(
    /** 坚果云账号已在“数据备份”里填好 */
    val hasAccount: Boolean,
    val running: Boolean,
    val uploaded: Int,
    val pending: Int,
    /** 本月已上传字节（坚果云免费版每月上传 1 GB，界面据此提醒） */
    val bytesThisMonth: Long,
    /** 按最近 7 天估算的每月上传量 */
    val monthlyEstimateBytes: Long?,
    val lastAt: Long?,
    val lastError: String?,
    /** 云端文件夹，如 “涛声/录音” */
    val remoteFolder: String,
)

interface RecordingSync {
    val settings: StateFlow<RecordingSyncSettings>
    fun update(settings: RecordingSyncSettings)
    val state: StateFlow<RecordingSyncState>
    /** 立即上传一次（忽略 WiFi/充电条件以外的限制仍遵守） */
    fun syncNow()
    /** 打开开关时可选：把手机里现有的录音也补传；只传原声还在的 */
    fun backfill()
}
