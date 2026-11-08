package app.resound.digest

import app.resound.data.TranscriptLineEntity
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * 合并用的提示词与解析：把几件事的原话合起来，重新整理成**一件**事。
 * 输入排版、日历、语音输入规则沿用 [DigestPrompt]，只换提示词模板与输出结构。
 * 纯逻辑，便于单元测试。
 */
object MergePrompt {
    /** 合并提示词：模板 + 日历 + 语音输入规则。 */
    fun system(template: String, today: LocalDate, appName: String): String =
        template.replace("{{CALENDAR}}", DigestPrompt.calendar(today)).replace("{{APP_NAME}}", appName) +
            "\n\n" + DigestPrompt.VOICE_INPUT_RULES

    /**
     * 解析大模型回复。
     * `merged` 缺失或格式完全不对时抛 [LlmException]（Format）。
     * 合并后的事件覆盖全部原话行；提取项的行号越界就不带出处，但仍然算这一件事的。
     */
    fun parse(
        content: String,
        lines: List<TranscriptLineEntity>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Pair<DigestPrompt.ParsedEvent, List<DigestPrompt.ParsedItem>> {
        if (lines.isEmpty()) throw LlmException(LlmError.Format)
        val json = try {
            JSONObject(content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```"))
        } catch (_: Exception) {
            throw LlmException(LlmError.Format)
        }
        val merged = json.optJSONObject("merged") ?: throw LlmException(LlmError.Format)
        val event = DigestPrompt.ParsedEvent(
            lineIds = lines.map { it.id },
            startAt = lines.first().startAt,
            endAt = lines.maxOf { it.endAt },
            scene = merged.optString("scene").ifBlank { "其他" },
            title = merged.optString("title").ifBlank { "未命名" }.take(30),
            summary = merged.optString("summary"),
            points = merged.optJSONArray("points").strings(),
            people = merged.optJSONArray("people").strings().filterNot { it.startsWith("说话人") },
            place = merged.optStringOrNull("place"),
            emotion = merged.optStringOrNull("emotion"),
            intensity = merged.optInt("intensity", 0).takeIf { it in 1..5 },
            important = merged.optBoolean("important", false),
        )
        val items = mutableListOf<DigestPrompt.ParsedItem>()
        if (event.scene !in DigestPrompt.NO_ITEMS_SCENES) {
            val raw = json.optJSONArray("items") ?: JSONArray()
            for (i in 0 until raw.length()) {
                val it = raw.optJSONObject(i) ?: continue
                val category = it.optString("category")
                val content = it.optString("content").trim()
                if (category !in DigestPrompt.CATEGORIES || content.isEmpty()) continue
                val line = it.optInt("line", -1).takeIf { l -> l in 1..lines.size }
                items += DigestPrompt.ParsedItem(
                    eventIndex = 0,
                    category = category,
                    kind = if (category == "待办") it.optStringOrNull("kind") else null,
                    content = content,
                    dueText = it.optStringOrNull("due"),
                    dueAt = it.optStringOrNull("due")?.let { d -> DigestPrompt.parseDue(d, zone) },
                    who = it.optStringOrNull("who"),
                    lineId = line?.let { l -> lines[l - 1].id },
                )
            }
        }
        return event to items
    }
}
