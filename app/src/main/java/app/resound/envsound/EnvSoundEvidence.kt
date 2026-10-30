package app.resound.envsound

import org.json.JSONObject

/** 整段证据只决定过滤；间隙证据只决定标签。不是界面 Contract。 */
data class EnvSoundEvidence(
    val whole: Map<String, Float>,
    val gaps: Map<String, Float> = emptyMap(),
    val availableGapSamples: Int = 0,
    val usedGapSamples: Int = 0,
    val gapFailed: Boolean = false,
) {
    internal fun json(): String {
        val names = EnvSoundRules.speech + EnvSoundRules.human +
            EnvSoundRules.tags.values.flatMap { it.second } + EnvSoundRules.sounds.values.flatMap { it.second } +
            listOf("Subway, metro, underground", "Rail transport", "Train")
        // 顶层保留旧分数字段；新证据仅在同一JSON加键，无表迁移，不重算历史行。
        return JSONObject(whole.filterKeys { it in names }).put("_gap075", JSONObject()
            .put("availableSamples", availableGapSamples).put("usedSamples", usedGapSamples)
            .put("status", when { gapFailed -> "failed"; usedGapSamples < EnvSoundGaps.MIN_SAMPLES -> "insufficient"; else -> "ready" })
            .put("scores", JSONObject(gaps.filterKeys { it in names }))).toString()
    }
}
