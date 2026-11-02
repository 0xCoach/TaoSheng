package app.resound.ui

import android.content.ClipboardManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import app.resound.ui.theme.ResoundTheme
import app.resound.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class ReportSelection092Test {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val report = "周报标题\n摘要不复制\n☑ 已完成任务\n☐ 下周计划\n我 · 2026-11-02 21:00\n“引用原话”\n4 / 5"

    private fun selectAndCopy(start: Int, end: Int) {
        compose.setContent { ResoundTheme(ThemeMode.Light) { ReportCopyActions { report } } }
        compose.onNodeWithText("选择部分内容").performClick()
        compose.onNodeWithTag("report-copy-selection").performSemanticsAction(SemanticsActions.SetSelection) {
            it(start, end, false)
        }
        compose.onNodeWithText("复制选中内容").performClick()
        compose.runOnIdle {
            val clip = compose.activity.getSystemService(ClipboardManager::class.java).primaryClip!!
            assertEquals(report.substring(minOf(start, end), maxOf(start, end)), clip.getItemAt(0).text.toString())
        }
        compose.onNodeWithTag("report-copy-selection").assertTextEquals(report)
    }

    @Test fun partialCopyIncludesOnlySelectedChecklistLines() {
        selectAndCopy(report.indexOf("☑"), report.indexOf("\n我"))
    }

    @Test fun reverseSelectionCanCopyQuoteAndRatingWithoutChangingReport() {
        selectAndCopy(report.length, report.indexOf("“"))
    }
}
