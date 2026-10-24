package app.resound.voiceprint

/** 相似度不是概率。第一候选必须过阈值，并与第二候选拉开差距才命名。 */
object SpeakerIdentity {
    data class Candidate(val id: Long, val name: String, val vector: FloatArray)
    data class Match(val personId: Long?, val name: String?, val score: Float?)
    fun match(vector: FloatArray?, candidates: List<Candidate>): Match {
        if (vector == null) return Match(null, null, null)
        val ranked = candidates.map { it to VoiceprintMath.cosine(vector, it.vector) }.sortedByDescending { it.second }
        val first = ranked.firstOrNull() ?: return Match(null, null, null)
        if (first.second < VoiceprintThresholds.SURE || (ranked.size > 1 && first.second - ranked[1].second < 0.08f))
            return Match(null, null, first.second.takeIf { candidates.size == 1 && candidates[0].id == 0L })
        return Match(first.first.id, first.first.name, first.second)
    }
}
