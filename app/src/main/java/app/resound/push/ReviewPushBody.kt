package app.resound.push

import app.resound.review.DailyReview
import app.resound.review.ReviewSection
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun DailyReview.pushDate(): String = "${date.monthValue}月${date.dayOfMonth}日"
internal fun DailyReview.pushTitle(): String = "${pushDate()}回顾"

internal fun DailyReview.pushBody(format: PushFormat): String {
    val markdown = format == PushFormat.Markdown
    val heading = "${pushDate()} ${date.format(DateTimeFormatter.ofPattern("EEEE", Locale.SIMPLIFIED_CHINESE))} · 回顾"
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
    return if (markdown) body else PushTransport.renderedBody(body, PushFormat.Text)
}

/** 与通用网址和重试队列共用，不包含事件出处等内部信息。 */
internal fun DailyReview.pushJson(): JSONObject = JSONObject().put("date", date.toString()).put("brief", brief)
    .put("sections", JSONArray(sections.map { section ->
        JSONObject().put("title", section.title).put("lines", JSONArray(section.lines))
    }))

internal fun reviewFromPushJson(json: JSONObject): DailyReview {
    val sections = json.getJSONArray("sections")
    return DailyReview(LocalDate.parse(json.getString("date")), json.getString("brief"),
        List(sections.length()) { index ->
            val section = sections.getJSONObject(index)
            val lines = section.getJSONArray("lines")
            ReviewSection(section.getString("title"), List(lines.length()) { lines.getString(it) }, List(lines.length()) { null })
        }, generatedAt = 0)
}
