package app.resound.review

import app.resound.digest.LlmInputBudget
import app.resound.digest.LlmInputLimitException
import app.resound.digest.LlmPreset
import org.json.JSONObject

internal const val REVIEW_FAILURE = "生成失败，请稍后重试"

internal object ReviewInputBudget {
    /** 只在超限时修改请求副本，原始资料和输出结构不变。所有提取项、独立事项、旧重点均保留。 */
    fun fit(input: String, system: String, preset: LlmPreset): String {
        val limit = LlmInputBudget.inputChars(preset).toLong() - system.length
        if (input.length <= limit) return input
        val json = JSONObject(input)
        json.put("compressed", "内容超出模型容量，部分普通事件省略了细节")
        val events = json.getJSONArray("events")
        val ordinary = (0 until events.length()).map(events::getJSONObject).filter { e ->
            val items = e.getJSONArray("items")
            !e.getBoolean("starred") && !e.getBoolean("important") &&
                (0 until items.length()).none { i ->
                    val item = items.getJSONObject(i)
                    item.optString("category") in setOf("待办", "约定") && item.optString("status") in setOf("Pending", "Accepted")
                }
        }
        // 用每个字段的 JSON 编码长度计算差值，避免每删一个字段都重编码整份长输入。
        var size = json.toString().length.toLong()
        for (e in ordinary) {
            val before = e.toString().length
            e.remove("points")
            size += e.toString().length - before
            if (size <= limit) return json.toString()
        }
        for (e in ordinary) {
            val summary = e.getString("summary")
            if (summary.isEmpty()) continue
            val before = e.toString().length
            // 至少保留开头 80 个字符；避免截断 UTF-16 代理对。
            var end = maxOf(80L, summary.length - (size - limit) - 1).coerceAtMost(summary.length.toLong()).toInt()
            if (end == summary.length) continue
            if (end > 0 && summary[end - 1].isHighSurrogate()) end--
            e.put("summary", summary.take(end) + "…")
            size += e.toString().length - before
            if (size <= limit) return json.toString()
        }
        throw LlmInputLimitException()
    }
}
