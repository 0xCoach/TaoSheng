/*
 * [INPUT]: 文件管理区、模拟目录与用户点击。
 * [OUTPUT]: 删除二次确认、跨页选择及录音时禁删的界面回归。
 * [POS]: 只验证操作回调，不发送真实卡内删除指令。
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package app.resound.sonicloud

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.resound.ui.theme.ResoundTheme
import app.resound.ui.theme.ThemeMode
import app.resound.ui.verify.RecorderFilesSection
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [32], qualifiers = "w393dp-h852dp-xhdpi")
class RecorderFilesUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val device = RecorderDevice("00:11:22:33:44:55", "测试卡")
    private fun click(text: String) {
        compose.onNodeWithText(text).performScrollTo().performTouchInput { click() }
    }

    @Test fun importedOriginalCanOnlyBeDeletedAfterConfirmation() {
        val file = RecorderFile("note 2026-11-08_22-18-12.opus", 80, 2, imported = true)
        val state = RecorderStatus(device = device, connected = true, recording = RecorderRecording.Stopped, files = listOf(file))
        val deleted = mutableListOf<List<RecorderFile>>()
        compose.setContent { ResoundTheme(ThemeMode.Light) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                RecorderFilesSection(state, RecorderDeleteStatus(), {},
                    { key, files -> assertEquals(device.key, key); deleted += files }, {})
            }
        } }
        click(file.name)
        click("删除卡内所选 1 个文件")
        compose.runOnIdle { assertTrue(deleted.isEmpty()) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertTrue(deleted.isEmpty()) }
        click("删除卡内所选 1 个文件")
        compose.onNodeWithText("永久删除").performClick()
        compose.runOnIdle { assertEquals(listOf(listOf(file)), deleted) }
    }

    @Test fun pageSelectionSurvivesNavigationButRecordingDisablesDeletion() {
        val files = (0..40).map { RecorderFile("file-${it.toString().padStart(2, '0')}.opus", 80, 2) }
        var state by mutableStateOf(RecorderStatus(device = device, connected = true,
            recording = RecorderRecording.Stopped, files = files))
        var deletes = 0
        compose.setContent { ResoundTheme(ThemeMode.Light) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                RecorderFilesSection(state, RecorderDeleteStatus(), {}, { _, _ -> deletes++ }, {})
            }
        } }
        click(files.first().name)
        click("下一页")
        compose.onNodeWithText(files.first().name).assertDoesNotExist()
        compose.onNodeWithText("符合筛选 41 个 · 已选 1 个（含其他页面）").assertExists()
        click(files.last().name)
        compose.onNodeWithText("符合筛选 41 个 · 已选 2 个（含其他页面）").assertExists()
        compose.runOnIdle { state = state.copy(recording = RecorderRecording.Recording) }
        click("删除卡内所选 2 个文件")
        compose.onNodeWithText("永久删除 2 个卡内录音？").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, deletes) }
    }
}
