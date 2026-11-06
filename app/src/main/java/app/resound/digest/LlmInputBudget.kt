package app.resound.digest

import org.json.JSONArray

/** 容量独立于界面 Contract；预算随不可变 preset 快照绑定，切换模型不会改变在途请求。 */
internal object LlmInputBudget {
    const val DEFAULT_CONTEXT_TOKENS = 128_000
    data class Capacity(val contextTokens: Int, val maxInputTokens: Int = Int.MAX_VALUE) {
        val inputChars: Int get() = minOf(maxInputTokens,
            (contextTokens.toLong() - LlmClient.MAX_JSON_OUTPUT_TOKENS - maxOf((contextTokens.toLong() + 19) / 20, 4_000)).coerceAtLeast(0).toInt())
    }
    private var catalog: Map<Pair<String, String>, Capacity> = emptyMap()
    private val snapshots = mutableMapOf<LlmPreset, Capacity>()

    fun parse(text: String): Map<Pair<String, String>, Capacity> = buildMap {
        val providers = JSONArray(text)
        for (i in 0 until providers.length()) {
            val p = providers.getJSONObject(i)
            val models = p.getJSONArray("models")
            for (j in 0 until models.length()) {
                val model = models.getString(j)
                val context = p.optJSONObject("contextTokens")?.optInt(model, 0)?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_TOKENS
                val input = p.optJSONObject("maxInputTokens")?.optInt(model, 0)?.takeIf { it > 0 } ?: Int.MAX_VALUE
                put(p.getString("id") to model, Capacity(context, input))
            }
        }
    }
    @Synchronized fun configure(text: String) { catalog = parse(text) }
    @Synchronized fun bind(preset: LlmPreset, templateId: String?): LlmPreset {
        snapshots[preset] = catalog[templateId to preset.model] ?: Capacity(DEFAULT_CONTEXT_TOKENS)
        return preset
    }
    @Synchronized fun capacity(preset: LlmPreset): Capacity = snapshots[preset] ?: Capacity(DEFAULT_CONTEXT_TOKENS)
    fun inputChars(preset: LlmPreset) = capacity(preset).inputChars
}
