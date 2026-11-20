package app.resound.digest

import app.resound.formatElapsed

/** 分母是本轮可整理的原话数；只有该批已落库才增加完成数，不按等待时间猜百分比。 */
data class DigestProgress(
    val total: Int = 0,
    val done: Int = 0,
    val batch: Int = 0,
    val batches: Int = 0,
    /** 单调时钟时间，不受系统校时影响。 */
    val batchStartedAt: Long = 0,
    val startedAt: Long = 0,
    val finishedAt: Long? = null,
    val reasoningChars: Int = 0,
    val contentChars: Int = 0,
) {
    val label: String get() = if (total > 0) "整理 $done/$total 句" else "准备整理"
    fun detail(now: Long): String {
        if (batch == 0) return "正在统计待整理原话…"
        val elapsed = ((finishedAt ?: now) - batchStartedAt).coerceAtLeast(0)
        return "第 $batch/$batches 批 · 等待模型 ${formatElapsed(elapsed)}"
    }
    val completion: String? get() = finishedAt?.takeIf { total > 0 && done == total }?.let {
        "本轮整理完成 · 用时 ${formatElapsed(it - startedAt)}"
    }
}

/** 供界面接入的文字草案；正文开始后优先展示正文进度。 */
fun formatLlmReceiveProgress(reasoningChars: Int, contentChars: Int): String? {
    fun number(value: Int) = value.toString().reversed().chunked(3).joinToString(",").reversed()
    return when {
        contentChars > 0 -> "正在写结果 · 已收到 ${number(contentChars)} 字"
        reasoningChars > 0 -> "模型思考中 · 已收到 ${number(reasoningChars)} 字"
        else -> null
    }
}
