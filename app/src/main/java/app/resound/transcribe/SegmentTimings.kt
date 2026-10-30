package app.resound.transcribe

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

/** 只允许固定字段与数字；不接收路径、文字、凭据或服务端消息。 */
internal enum class AsrPart(val title: String) {
    EnvDecode("环境解码"), EnvLoad("环境加载"), EnvInfer("环境推理"), EnvPrepare("环境采样"), EnvIdentity("环境本人保护"),
    Prepare("音频准备"), Connect("建连"), Upload("上传"), Wait("等结果"),
    Diarize("分人"), ModelLoad("识别加载"), Recognize("识别"), Voiceprint("声纹")
}

internal class SegmentTimings(
    private val segmentId: Long,
    private val source: TranscribeSource?,
    private val audioMillis: Long,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SegmentTimings>
    private val began = clock()
    private val values = mutableMapOf<AsrPart, Long>()
    var outcome: String = "未执行"
    @Synchronized fun add(part: AsrPart, millis: Long) { values[part] = (values[part] ?: 0) + millis.coerceAtLeast(0) }
    @Synchronized fun line(): String = buildString {
        append("073段耗时 id=$segmentId 方式=${source?.name ?: "Unknown"} 录音=${audioMillis.coerceAtLeast(0)}ms")
        AsrPart.entries.forEach { part ->
            // 云端分人和识别无法独立计时，不能写成已测得的 0。
            val value = values[part] ?: if (part in setOf(AsrPart.Diarize, AsrPart.Recognize) && source != TranscribeSource.Local) null else 0L
            append(" ${part.title}=${value?.let { "${it}ms" } ?: "未提供"}")
        }
        append(" 总计=${(clock()-began).coerceAtLeast(0)}ms 状态=${outcome.takeIf { it in setOf("Done", "Empty", "Failed", "SkippedEnv", "Abandoned", "Quiet", "未执行") } ?: "Failed"}")
    }
}

internal suspend fun <T> measureAsr(part: AsrPart, block: suspend () -> T): T {
    val timing = currentCoroutineContext()[SegmentTimings]
    val start = System.nanoTime()
    try { return block() }
    finally { timing?.add(part, (System.nanoTime() - start) / 1_000_000) }
}
