package app.resound.transcribe

import java.io.File
import kotlinx.coroutines.flow.Flow

/*
 * 卡 068 录音导出：界面（Claude）只依赖这里；底层（Codex）实现。改签名需双方确认。
 * 把选中的录音段按时间接成一个 M4A（段间 0.5 秒空白），可附原话文字 .txt 和字幕 .srt；文件放缓存目录，1 天后自动清理。
 */

data class ExportRequest(
    /** 选中的录音段（顺序不限，导出时按时间排） */
    val segmentIds: List<Long>,
    /** 文件名主体（界面已去掉非法字符；底层再兜底一次），音频与文字同名 */
    val title: String,
    /** 附原话文字 .txt：每句带“在合成录音里的位置 + 当时时刻 + 说话人”，开头写标题、日期、总时长、涉及的事件和人名 */
    val includeText: Boolean = true,
    /** 附字幕 .srt（时间轴对齐合成录音） */
    val includeSrt: Boolean = true,
    /** 跳过落在“媒体”事件里的录音段（音频和文字都跳过） */
    val skipMedia: Boolean = true,
)

/** 导出前的预估，给面板显示 */
data class ExportEstimate(
    /** 有录音、会合进音频的段数 */
    val audioSegments: Int,
    /** 语音输入等只有文字的段数（不进音频，进文字） */
    val textOnly: Int,
    /** 录音已过保留期的段数（只进文字） */
    val expired: Int,
    /** 因 skipMedia 跳过的段数 */
    val mediaSkipped: Int,
    /** 合成后的音频时长（含段间空白） */
    val durationMillis: Long,
    /** 预计音频大小 */
    val bytes: Long,
    /** 卡 069：按本机最近几次实测速度估的合成耗时；还没有实测时用保守默认值 */
    val etaMillis: Long = 0,
)

sealed interface ExportProgress {
    /** 0..1 */
    data class Working(val fraction: Float) : ExportProgress
    /** 生成好的文件（音频在前；没有可合成的录音时只有文字/字幕） */
    data class Done(val files: List<File>) : ExportProgress
    data class Failed(val message: String) : ExportProgress
}

interface AudioExporter {
    suspend fun estimate(request: ExportRequest): ExportEstimate
    /** 收集即开始；取消收集即取消导出并删掉半成品 */
    fun export(request: ExportRequest): Flow<ExportProgress>
    /** 删掉 1 天前的导出临时文件；App 启动和每次导出前调用 */
    fun cleanup()
}

/** 批量删除录音段：删录音文件、段和其原话（整理出的事件不动）；返回删掉的段数 */
interface SegmentDeleter {
    suspend fun deleteSegments(segmentIds: List<Long>): Int
}

/*
 * 卡 069：导出改成后台任务（前台服务通知显示进度、可取消，完成/失败发通知）。界面只用下面这些。
 */

sealed interface ExportJobState {
    data class Running(val fraction: Float) : ExportJobState
    data class Done(val files: List<File>) : ExportJobState
    data class Failed(val message: String) : ExportJobState
    data object Cancelled : ExportJobState
}

data class ExportJob(val id: String, val title: String, val startedAt: Long, val state: ExportJobState)

interface ExportJobs {
    /** 进行中和最近完成的导出（完成的保留到 [dismiss] 或 1 天后） */
    val jobs: kotlinx.coroutines.flow.StateFlow<List<ExportJob>>
    /** 开始后台导出，返回任务 id；App 切走、面板关掉都不影响 */
    fun start(request: ExportRequest): String
    fun cancel(id: String)
    /** 用户看过结果后从列表里去掉（文件仍按 1 天清理） */
    fun dismiss(id: String)
}

/** 点“导出完成”通知打开 App 时，Intent 里带这个 extra（任务 id），界面据此打开结果面板 */
const val EXTRA_EXPORT_JOB = "app.resound.exportJob"
