package app.resound.diagnostics

import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * 卡 052 · 问题记录的约定（Claude 定，Codex 实现，签名不要自行修改）。
 *
 * 闪退自动记下来；用户在“关于涛声”点“导出问题记录”时，把版本、通道、手机型号、最近的运行记录和闪退记录
 * 打成一个文件，交给系统分享。不含原话、待办内容、人名、地点和任何密钥；App 自己不往外传。
 */
interface ProblemReports {
    /** 上次异常退出（闪退）的时间；没有或已看过为 null。今天页据此提示一次 */
    val lastCrashAt: StateFlow<Long?>

    /** 用户看过提示（点了导出或关掉提示） */
    fun dismissCrashNotice()

    /** 生成问题记录文件（放在 cache 下，供 FileProvider 分享），文件名如“涛声问题记录-0.2.12-preview.1-1028-1530.txt” */
    suspend fun export(): File
}
