package app.resound.envsound

import java.io.File
import app.resound.transcribe.*
import kotlinx.coroutines.CancellationException

/** 先分类；只有本人声纹可能改变判定时才检查。采用最严格设置，避免途中改设置漏掉保护。 */
internal class EnvSoundAnalysis(
    private val read: suspend (File) -> ShortArray,
    private val infer: suspend (ShortArray) -> Map<String, Float>,
    private val recognizesMe: suspend (ShortArray) -> Boolean,
    private val phase: (String?) -> Unit = {},
    private val log: (String) -> Unit = {},
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val extractGaps: suspend (ShortArray) -> EnvSoundGaps.Result = { EnvSoundGaps.Result(ShortArray(0), 0) },
) {
    suspend fun analyze(file: File): Pair<EnvSoundEvidence, Boolean> {
        val start = now()
        var decodeMs = 0L
        var modelMs = 0L
        var identityMs = 0L
        var gapExtractMs = 0L
        var gapInferMs = 0L
        var gapFailed = false
        var available = 0
        var used = 0
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
            var gapScores = emptyMap<String, Float>()
            try {
                phase("环境音判断 · 提取说话间隙")
                val gapAt = now()
                val gaps = try { extractGaps(pcm) } finally { gapExtractMs = now() - gapAt }
                available = gaps.availableSamples
                used = gaps.pcm.size
                if (used >= EnvSoundGaps.MIN_SAMPLES) {
                    phase("环境音判断 · 识别间隙声音")
                    val inferAt = now()
                    // 间隙PCM最多30秒，原模型窗口规则自然只推理一次。
                    gapScores = try { infer(gaps.pcm) } finally { gapInferMs = now() - inferAt }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { gapFailed = true }
            catch (_: LinkageError) { gapFailed = true }
            // 间隙失败不猜标签，也不改变已成功的原过滤判定。
            succeeded = true
            return EnvSoundEvidence(scores, gapScores, available, used, gapFailed) to me
        } finally {
            phase(null)
            log("环境音分步 解码=${decodeMs}ms 模型=${modelMs}ms 本人检查=${identityMs}ms 间隙提取=${gapExtractMs}ms 间隙推理=${gapInferMs}ms 间隙总样本=$available 间隙使用样本=$used 间隙失败=$gapFailed 检查=$checked 成功=$succeeded 总计=${now()-start}ms")
        }
    }

    private companion object {
        val protectionSettings = EnvSoundSettings(enabled = true, skip = SkipMode.On, media = MediaMode.SkipTranscribe)
    }
}
