package app.resound.ui

import android.content.ClipboardManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.resound.review.DailyReview
import app.resound.review.ReviewSection
import app.resound.skills.*
import app.resound.ui.review.ReviewSheet
import app.resound.ui.theme.ResoundTheme
import app.resound.ui.theme.ThemeMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class ReportCopy090Test {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val date = LocalDate.of(2026, 11, 2)
    private val at = date.atTime(21, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun card(fields: List<SkillField>, values: Map<String, FieldValue>, id: String = "meeting") =
        SkillCard(1, id, "报告", "meeting", date, at, at + 60_000, "完整报告", "摘要", fields, values, emptyList(), at, false)

    @Test fun dailyButtonCopiesSectionsBelowTheVisibleArea() {
        val review = DailyReview(date, "完整日报摘要", (1..20).map {
            ReviewSection("栏目$it", listOf("内容$it 第一条", "内容$it 第二条"), emptyList())
        }, at)
        compose.setContent { ResoundTheme(ThemeMode.Light) { ReviewSheet(review) {} } }
        compose.onNodeWithText("复制全部内容").performClick()
        compose.runOnIdle {
            val clip = compose.activity.getSystemService(ClipboardManager::class.java).primaryClip!!
            val text = clip.getItemAt(0).text.toString()
            assertEquals(dailyReviewCopyText(review), text)
            assertTrue(text.contains("2026-11-02 · 日报"))
            assertTrue(text.contains("内容20 第二条"))
        }
    }

    @Test fun allCardFieldTypesKeepTheirContentAndChecklistState() {
        val fields = FieldType.entries.map { SkillField(it.name, "栏目${it.name}", it, "") }
        val values = mapOf(
            "Text" to FieldValue(text = "一句话"), "Paragraph" to FieldValue(text = "第一段\n第二段"),
            "Bullets" to FieldValue(items = listOf("要点一", "要点二")),
            "Tags" to FieldValue(items = listOf("标签一", "标签二")),
            "Checklist" to FieldValue(items = listOf("完成项", "未完成项"), checked = listOf(true, false)),
            "Quotes" to FieldValue(quotes = listOf(CardQuote(null, "我", at, "完整原话"))),
            "Number" to FieldValue(number = 12.75, unit = "小时"), "Rating" to FieldValue(rating = 4))
        val text = skillCardCopyText(card(fields, values))
        listOf("完整报告", "摘要", "第二段", "要点二", "标签二", "☑ 完成项", "☐ 未完成项",
            "我 · 2026-11-02 21:00", "完整原话", "12.75 小时", "4 / 5").forEach { assertTrue(it, text.contains(it)) }
        fields.forEach { assertTrue(text.contains(it.label)) }
    }

    @Test fun weeklyCopyMatchesVisibleReportWithoutOldEnvironmentDetails() {
        val fields = listOf("one_line", "highlights", "next_week", "env_sound_impression", "env_sound_observations", "env_sound_overview")
            .map { SkillField(it, it, FieldType.Text, "") }
        val report = card(fields, fields.associate { it.key to FieldValue(text = "内容_${it.key}") }, "weekly")
        val text = skillCardCopyText(report)
        assertTrue(text.contains("内容_next_week")); assertTrue(text.contains("内容_env_sound_impression"))
        assertFalse(text.contains("env_sound_observations")); assertFalse(text.contains("env_sound_overview"))
        assertFalse(text.contains("摘要"))
    }

    @Test fun longReportIsNotTruncatedAndEmptyFieldsAreOmitted() {
        val content = "长正文".repeat(30_000) + "报告最后一句"
        val fields = listOf(SkillField("body", "正文", FieldType.Paragraph, ""), SkillField("empty", "空栏目", FieldType.Number, ""))
        val text = skillCardCopyText(card(fields, mapOf("body" to FieldValue(text = content), "empty" to FieldValue())))
        assertTrue(text.contains(content)); assertFalse(text.contains("空栏目"))
    }
}
