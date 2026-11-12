package app.resound.digest

import app.resound.net.Arr as JSONArray

/** 容量独立于界面 Contract；预算随不可变 preset 快照绑定，切换模型不会改变在途请求。 */
object InputCapacity {
    const val DEFAULT_CONTEXT_TOKENS = 128_000
    data class Capacity(val contextTokens: Int, val maxInputTokens: Int = Int.MAX_VALUE) {
        val inputChars: Int get() = minOf(maxInputTokens,
            (contextTokens.toLong() - app.resound.net.NetLlmClient.MAX_JSON_OUTPUT_TOKENS - maxOf((contextTokens.toLong() + 19) / 20, 4_000)).coerceAtLeast(0).toInt())
    }

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
}
