package app.resound.transcribe

import java.io.File

/** 后续服务商/自建服务实现此接口；地址与资源统一从配置传入。 */
interface AsrProvider {
    suspend fun recognize(audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit): AsrResult
}
data class AsrAudio(val file: File, val format: String, val durationMillis: Long, val separateSpeakers: Boolean = true)
data class AsrSentence(val startMillis: Long, val endMillis: Long, val text: String, val speakerTag: String?)
data class AsrResult(val sentences: List<AsrSentence>, val durationMillis: Long, val logId: String?)
enum class AsrError(val human: String, val retryable: Boolean = false) {
    Speakers("说话人分段失败，请重新下载分段模型或在人物与声纹中关闭多人分段"),
    ModelMissing("请在转写设置中下载所需的本地模型"), LocalEngine("本机识别失败，可改用 CPU 识别或重新下载模型"), Auth("密钥无效或未开通"), Network("网络连接失败，等待重试", true), Timeout("转写超时，等待重试", true),
    Server("服务暂时不可用，等待重试", true), Protocol("识别响应不完整或格式异常"), Audio("录音文件不可读取或解码失败"),
    Interrupted("任务中断，等待重试", true), Storage("本地存储失败，等待重试", true)
}
/** 仅使用固定文案，禁止将 Throwable、服务端消息或请求对象作为 cause/日志保存。 */
class AsrException(val type: AsrError, val logId: String? = null) : Exception(type.human)
data class DoubaoConfig(
    val url: String = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_nostream",
    val resourceId: String = "volc.seedasr.sauc.duration",
    /** 仅测试/特殊接入显式覆盖；默认随时长增长。 */
    val timeoutMillis: Long? = null,
    val paceMillis: Long = 0,
) {
    // 短段保留原有 90 秒余量；5 分钟段为 480 秒，单请求不超过 Worker 的 10 分钟窗口。
    fun timeoutFor(durationMillis: Long): Long = timeoutMillis
        ?: maxOf(90_000L, 30_000L + durationMillis.coerceAtLeast(0) * 3 / 2)
}
object TranscribePolicy {
    const val RETENTION_MILLIS = 24 * 60 * 60 * 1000L
    val ROUND_STOPPERS = setOf(AsrError.Speakers, AsrError.Network, AsrError.Server, AsrError.ModelMissing, AsrError.LocalEngine)
    fun canDelete(status: String, completedAt: Long?, now: Long) =
        status in setOf("Done", "Empty", "SkippedPlayback") && completedAt != null && now >= completedAt && now - completedAt >= RETENTION_MILLIS
    fun retryAt(type: AsrError, attempts: Int, now: Long): Long? =
        if (type.retryable && attempts < 5) now + (60_000L shl (attempts - 1).coerceIn(0, 4)) else null
    fun safeLogId(value: String?, key: String): String? = value?.takeIf {
        it.matches(Regex("[A-Za-z0-9_-]{1,128}")) &&
            (listOf(key) + key.split('-')).filter { part -> part.isNotEmpty() }.none { part -> it.contains(part, true) }
    }
}
