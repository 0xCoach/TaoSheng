package app.resound.push

import app.resound.review.DailyReview
import app.resound.review.ReviewSection
import org.json.JSONObject
import java.time.LocalDate

internal fun DailyReview.pushDate(): String = "${date.monthValue}月${date.dayOfMonth}日"
internal fun DailyReview.pushTitle(): String = "${pushDate()}回顾"

internal fun DailyReview.pushBody(format: PushFormat): String = PushBodyRules.body(
    kotlinx.datetime.LocalDate.parse(date.toString()), brief, sections.map { PushReviewSection(it.title, it.lines) }, format)

internal fun DailyReview.pushJson(): JSONObject = JSONObject(PushBodyRules.reviewJson(date.toString(), brief,
    sections.map { PushReviewSection(it.title, it.lines) }).toString())

internal fun reviewFromPushJson(json: JSONObject): DailyReview {
    val sections = json.getJSONArray("sections")
    return DailyReview(LocalDate.parse(json.getString("date")), json.getString("brief"),
        List(sections.length()) { index ->
            val section = sections.getJSONObject(index)
            val lines = section.getJSONArray("lines")
            ReviewSection(section.getString("title"), List(lines.length()) { lines.getString(it) }, List(lines.length()) { null })
        }, generatedAt = 0)
}
