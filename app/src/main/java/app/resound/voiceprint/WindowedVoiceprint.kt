package app.resound.voiceprint

import app.resound.data.TranscriptLineEntity
import app.resound.data.VoiceprintSampleEntity

/** 卡 044：连续时间窗，绝不把不相邻的语音拼成一个说话人。 */
internal object WindowedVoiceprint {
    const val WINDOW_MILLIS = 3_000L
    const val MIN_MILLIS = 1_500L
    const val SURE = 0.50f
    const val VOTE = 0.75f
    const val OUTLIER = 0.45f
    const val AUTO_SCORE = 0.85f
    const val AUTO_MILLIS = 2_000L

    data class Window(val start: Long, val end: Long)
    data class Observation(val window: Window, val feature: VoiceprintFeatureCache.Feature?)

    fun windows(start: Long, end: Long, lines: List<TranscriptLineEntity>): List<Window> {
        val merged = mutableListOf<Window>()
        for (line in lines.sortedBy { it.startAt }) {
            val a = line.startAt.coerceIn(start, end)
            val b = line.endAt.coerceIn(start, end)
            if (b <= a) continue
            val last = merged.lastOrNull()
            if (last != null && a <= last.end) merged[merged.lastIndex] = last.copy(end = maxOf(b, last.end))
            else merged += Window(a, b)
        }
        return merged.flatMap { span ->
            generateSequence(span.start) { (it + WINDOW_MILLIS).takeIf { next -> next < span.end } }
                .map { Window(it, minOf(it + WINDOW_MILLIS, span.end)) }.toList()
        }
    }

    /** 保留未知/短窗在分母中，不能用一小段高分覆盖整句。 */
    fun vote(line: TranscriptLineEntity, observations: List<Observation>, candidates: List<SpeakerIdentity.Candidate>): SpeakerIdentity.Match {
        val total = (line.endAt - line.startAt).coerceAtLeast(1).toDouble()
        val votes = mutableMapOf<Long, Double>()
        val scores = mutableMapOf<Long, Double>()
        val names = mutableMapOf<Long, String>()
        var meScore = 0.0
        var measured = 0.0
        for ((window, feature) in observations) {
            val overlap = (minOf(line.endAt, window.end) - maxOf(line.startAt, window.start)).coerceAtLeast(0).toDouble()
            if (overlap == 0.0 || feature?.vector == null) continue
            val match = SpeakerIdentity.match(feature.vector, candidates)
            measured += overlap
            if (candidates.size == 1 && candidates.single().id == 0L) meScore += (match.score ?: 0f) * overlap
            match.personId?.let { id ->
                votes[id] = (votes[id] ?: 0.0) + overlap
                scores[id] = (scores[id] ?: 0.0) + (match.score ?: 0f) * overlap
                names[id] = requireNotNull(match.name)
            }
        }
        val best = votes.maxByOrNull { it.value }
        if (best != null && best.value / total >= VOTE)
            return SpeakerIdentity.Match(best.key, names[best.key], (scores.getValue(best.key) / best.value).toFloat())
        // UI 仍按约定阈值显示问号；投票失败不得显示确定的“我”。
        return SpeakerIdentity.Match(null, null, if (measured > 0 && candidates.size == 1 && candidates.single().id == 0L)
            (meScore / total).toFloat().coerceAtMost(SURE - 0.001f) else null)
    }

    /** 自动样本不参与身份中心，切断自学习反馈。人工离群通过 sampleHealth 标记，保留多种录音条件，绝不删除。 */
    fun trusted(samples: List<VoiceprintSampleEntity>): List<VoiceprintSampleEntity> {
        val manual = samples.filter { it.source != "auto" && it.source != "line-review" }.filter {
            runCatching { VoiceprintMath.unit(VoiceprintMath.decode(it.vector)) }.isSuccess
        }
        return manual
    }

    /** 只为“我”选择设备样本；调用方先排除待判定片段，避免自己匹配自己。 */
    fun forInput(samples: List<VoiceprintSampleEntity>, sourceId: String, sources: Map<Long, String?>): List<VoiceprintSampleEntity> {
        val manual = trusted(samples)
        val same = manual.filter { sources[it.id] == sourceId }
        return if (same.size >= 3 && same.sumOf { it.durationMillis } >= 30_000) same else manual
    }

    fun autoSample(observations: List<Observation>, candidates: List<SpeakerIdentity.Candidate>): VoiceprintFeatureCache.Feature? {
        // 整组各有效窗一致才允许取其中一窗；这只是单人代理证据，不是分离重叠人声。
        if (observations.isEmpty() || observations.any { it.feature?.vector == null }) return null
        val features = observations.map { requireNotNull(it.feature) }
        if (features.any { SpeakerIdentity.match(it.vector, candidates).let { m -> m.personId != 0L || (m.score ?: 0f) < AUTO_SCORE } }) return null
        val center = VoiceprintMath.representative(features.map { requireNotNull(it.vector) })
        if (features.any { VoiceprintMath.cosine(requireNotNull(it.vector), center) < AUTO_SCORE }) return null
        return features.firstOrNull { it.durationMillis >= AUTO_MILLIS }
    }
}
