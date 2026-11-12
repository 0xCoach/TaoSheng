package app.resound.push

import app.resound.platform.legacyText
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.serialization.json.*

data class PushReviewSection(val title: String, val lines: List<String>)

object PushBodyRules {
    private val weekdays = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
fun body(date: LocalDate, brief: String, sections: List<PushReviewSection>, format: PushFormat): String {
    val markdown = format == PushFormat.Markdown
    val heading = "${date.month.ordinal + 1}月${date.day}日 ${weekdays[date.dayOfWeek.isoDayNumber - 1]} · 回顾"
    val lead = sections.firstOrNull { it.title == "一句话" }?.lines
        ?.filter { it.isNotBlank() }?.joinToString("\n")?.takeIf { it.isNotBlank() } ?: brief
    val blocks = mutableListOf(if (markdown) "**$heading**" else heading, lead)
    sections.filter { it.title != "一句话" }.forEach { section ->
        val lines = section.lines.filter { it.isNotBlank() }
        if (lines.isNotEmpty()) {
            val title = if (markdown) "### ${section.title}" else "【${section.title}】"
            blocks += title + "\n" + lines.joinToString("\n") { "- $it" }
        }
    }
    val body = blocks.joinToString("\n\n")
    return when (format) {
        PushFormat.Markdown -> body
        // JSON 维持既有正文协议，包括列表前缀及链接只留文字的行为。
        PushFormat.Json -> body.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
            .replace(Regex("!?\\[([^\\]]*)\\]\\([^)]+\\)"), "$1")
            .replace("**", "").replace("__", "").replace("`", "")
        PushFormat.Text -> renderedBody(body, PushFormat.Text)
    }
}

        fun renderedBody(body: String, format: PushFormat): String = if (format != PushFormat.Text) body else body
            .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
            .replace(Regex("!?\\[([^\\]]*)\\]\\(([^)]+)\\)"), "$1 ($2)")
            .replace(Regex("(?m)^([ \\t]*)[-*+]\\s+\\[[ xX]\\]\\s*"), "$1")
            .replace(Regex("(?m)^([ \\t]*)[-*+]\\s+"), "$1")
            .replace(Regex("(?m)^\\s*>\\s?"), "")
            .replace("**", "").replace("__", "").replace("`", "")
    fun reviewJson(date: String, brief: String, sections: List<PushReviewSection>): JsonObject = buildJsonObject {
        put("date", date); put("brief", brief)
        put("sections", JsonArray(sections.map { section -> buildJsonObject {
            put("title", section.title); put("lines", JsonArray(section.lines.map(::JsonPrimitive)))
        } }))
    }
    fun dailyMemory(date: String, generatedAt: Long, markdown: String, review: JsonElement,
        events: JsonArray, items: JsonArray, cards: JsonArray, lines: JsonArray?): JsonObject = buildJsonObject {
        put("type", "daily_memory"); put("version", 1); put("app", "resound"); put("date", date)
        put("generatedAt", generatedAt); put("markdown", markdown)
        put("data", buildJsonObject {
            put("review", review); put("events", events); put("items", items); put("cards", cards)
            if (lines != null) put("lines", lines)
        })
    }
    const val UPDATED = "{{RESOUND_UPDATED}}"
    fun card(type: String, date: String, content: String, title: String, count: Int, body: String): String =
        "---\n类型: ${JsonPrimitive(type).legacyText()}\n日期: ${JsonPrimitive(date).legacyText()}\n内容: ${JsonPrimitive(content).legacyText()}\n更新: \"$UPDATED\"\n来源: 自动生成，请勿手动修改（改了会被覆盖）\n条数: $count\n---\n# $title\n\n${body.trim()}\n"
}
