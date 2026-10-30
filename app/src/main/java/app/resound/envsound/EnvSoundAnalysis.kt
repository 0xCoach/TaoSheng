package app.resound.envsound

import java.io.File
import app.resound.transcribe.*
import kotlinx.coroutines.currentCoroutineContext

/** 先分类；只有本人声纹可能改变判定时才检查。采用最严格设置，避免途中改设置漏掉保护。 */
internal class EnvSoundAnalysis(
    private val read: suspend (File) -> ShortArray,
    private val infer: suspend (ShortArray) -> Map<String, Float>,
    private val recognizesMe: suspend (ShortArray) -> Boolean,
    private val phase: (String?) -> Unit = {},
    private val log: (String) -> Unit = {},
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    suspend fun analyze(file: File): Pair<Map<String, Float>, Boolean> {
        val start = now()
        var decodeMs = 0L
        var modelMs = 0L
        var identityMs = 0L
        var checked = false
        var succeeded = false
        try {
            phase("环境音判断 · 准备音频")
            val pcm = measureAsr(AsrPart.EnvDecode) { read(file) }
            decodeMs = now() - start
            phase("环境音判断 · 识别声音")
            val modelAt = now()
            val scores = infer(pcm)
            modelMs = now() - modelAt
            // 即使当前仅标记，也保护疑似环境音/影视中的本人声音；普通人声与非法输出无需额外检查。
            checked = EnvSoundRules.decide(scores, protectionSettings, false).verdict != SegmentVerdict.Keep
            val me = if (checked) {
                phase("环境音判断 · 核对本人声音")
                val identityAt = now()
                try { measureAsr(AsrPart.EnvIdentity) { recognizesMe(pcm) } } finally { identityMs = now() - identityAt }
            } else false
            succeeded = true
            return scores to me
        } finally {
            phase(null)
            log("环境音分步 解码=${decodeMs}ms 模型=${modelMs}ms 本人检查=${identityMs}ms 检查=$checked 成功=$succeeded 总计=${now()-start}ms")
        }
    }

    private companion object {
        val protectionSettings = EnvSoundSettings(enabled = true, skip = SkipMode.On, media = MediaMode.SkipTranscribe)
    }
}
