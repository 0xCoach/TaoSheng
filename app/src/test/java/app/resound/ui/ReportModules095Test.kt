package app.resound.ui

import android.content.ClipboardManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.resound.ui.theme.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class ReportModules095Test {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val blocks = listOf(
        ReportCopyBlock("header", "标题", "完整周报"),
        ReportCopyBlock("summary", "总结", "总结\n这段不选"),
        ReportCopyBlock("plan", "计划", "计划\n☐ 下周第一项\n☑ 已完成第二项"))
    private val full = blocks.joinToString("\n\n") { it.text }
    private fun content() {
        compose.setContent { ResoundTheme(ThemeMode.Light) { ReportCopyActions(blocks = { blocks }) { full } } }
    }
    private fun assertClipboard(expected: String) = compose.runOnIdle {
        assertEquals(expected, compose.activity.getSystemService(ClipboardManager::class.java)
            .primaryClip!!.getItemAt(0).text.toString())
    }

    @Test fun nonAdjacentModulesCopyInDocumentOrderAndCanBeCleared() {
        content()
        compose.onNodeWithText("复制全部内容").performClick()
        assertClipboard(full)
        compose.onNodeWithText("选择部分内容").performClick()
        compose.onNodeWithText("复制所选").assertIsNotEnabled()
        assertClipboard(full)
        compose.onNodeWithTag("report-block:plan").performScrollTo().performClick()
        compose.onNodeWithTag("report-block:header").performScrollTo().performClick()
        compose.onNodeWithText("复制所选").performClick()
        assertClipboard(blocks[0].text + "\n\n" + blocks[2].text)
        compose.onNodeWithText("清空").performClick()
        compose.onNodeWithText("复制所选").assertIsNotEnabled()
        assertClipboard(blocks[0].text + "\n\n" + blocks[2].text)
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithText("复制所选").performClick()
        assertClipboard(full)
    }

    @Test fun switchingToFreeSelectionCopiesOnlyChosenWordsAndKeepsModuleChoices() {
        content()
        compose.onNodeWithText("选择部分内容").performClick()
        compose.onNodeWithTag("report-block:header").performScrollTo().performClick()
        compose.onNodeWithText("自由选字").performClick()
        val start = full.indexOf("下周第一项")
        compose.onNodeWithTag("report-copy-selection").performSemanticsAction(SemanticsActions.SetSelection) {
            it(start, start + "下周第一项".length, false)
        }
        compose.onNodeWithText("复制选中内容").performClick()
        assertClipboard("下周第一项")
        compose.onNodeWithText("按模块").performClick()
        compose.onNodeWithText("复制所选").assertIsEnabled()
        compose.onNodeWithTag("report-block:header").assertIsOn()
    }

    @Test fun longReportKeepsCopyBarFixedWhileLastModuleIsSelected() {
        val many = (1..20).map { ReportCopyBlock("$it", "栏目$it", "栏目$it\n这是一段用于检查滚动的正文。\n完整内容仍可复制。") }
        compose.setContent { ResoundTheme(ThemeMode.Dark) {
            ReportCopyActions(blocks = { many }) { many.joinToString("\n\n") { it.text } }
        } }
        compose.onNodeWithText("选择部分内容").performClick()
        val before = compose.onNodeWithTag("report-copy-footer").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("report-block:20").performScrollTo().performClick()
        compose.onNodeWithText("已选 1 块").assertIsDisplayed()
        compose.onNodeWithText("复制所选").assertIsDisplayed().performClick()
        assertClipboard(many.last().text)
        val after = compose.onNodeWithTag("report-copy-footer").fetchSemanticsNode().boundsInRoot
        assertEquals(before, after)
    }
}
