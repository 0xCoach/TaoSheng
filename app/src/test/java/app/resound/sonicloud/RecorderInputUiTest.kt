/*
 * [INPUT]: 设备卡片、连接状态和统一输入选择行。
 * [OUTPUT]: 名称后状态电量、连接可见性及忙碌时禁止切入的界面回归。
 * [POS]: 验证来源选择入口移动后的实际回调，不连接真实硬件。
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package app.resound.sonicloud

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.resound.audio.InputChoice
import app.resound.ui.theme.ResoundTheme
import app.resound.ui.theme.ThemeMode
import app.resound.ui.verify.RecorderDeviceCard
import app.resound.ui.verify.RecorderInputRow
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
class RecorderInputUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val device = RecorderDevice("00:11:22:33:44:55", "CB08")

    @Test fun cardKeepsStatusAndBatteryAfterNameAndHidesStaleBatteryWhenDisconnected() {
        var state by mutableStateOf(RecorderStatus(device = device, connected = true, battery = 80))
        compose.setContent { ResoundTheme(ThemeMode.Light) { RecorderDeviceCard(state) {} } }
        compose.onNodeWithText("CB08（已连接 · 电量 80%）").assertExists()
        compose.onNodeWithText("管理录音卡").assertExists()
        compose.runOnIdle { state = state.copy(connected = false) }
        compose.onNodeWithText("CB08（未连接）").assertExists()
        compose.onNodeWithText("CB08（已连接 · 电量 80%）").assertDoesNotExist()
    }

    @Test fun connectedCardAppearsAndChoosesRecorderWithoutChangingChoiceOnDisconnect() {
        var state by mutableStateOf(RecorderStatus(device = device))
        var choice by mutableStateOf<InputChoice>(InputChoice.Phone)
        compose.setContent { ResoundTheme(ThemeMode.Light) {
            Column { RecorderInputRow(state, RecorderWifiStatus(), RecorderDeleteStatus(), choice) { choice = it } }
        } }
        compose.onNodeWithText(device.name).assertDoesNotExist()
        compose.runOnIdle { state = state.copy(connected = true) }
        compose.onNodeWithText(device.name).performTouchInput { click() }
        compose.runOnIdle { assertEquals(InputChoice.Recorder(device.key, device.name), choice); state = state.copy(connected = false) }
        compose.onNodeWithText(device.name).assertDoesNotExist()
        compose.runOnIdle { assertEquals(InputChoice.Recorder(device.key, device.name), choice) }
    }

    @Test fun transferAndCleanupKeepInputSelectionBlockedUntilIdle() {
        var state by mutableStateOf(RecorderStatus(device = device, connected = true, downloading = "test.opus"))
        var wifi by mutableStateOf(RecorderWifiStatus())
        var deletion by mutableStateOf(RecorderDeleteStatus())
        var selections = 0
        compose.setContent { ResoundTheme(ThemeMode.Light) {
            Column { RecorderInputRow(state, wifi, deletion, InputChoice.Phone) { selections++ } }
        } }
        compose.onNodeWithText(device.name).assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, selections); state = state.copy(downloading = null); deletion = RecorderDeleteStatus(current = "test.opus") }
        compose.onNodeWithText(device.name).assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, selections); deletion = RecorderDeleteStatus(); wifi = RecorderWifiStatus(RecorderWifiPhase.Ready) }
        compose.onNodeWithText(device.name).assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, selections); wifi = RecorderWifiStatus() }
        compose.onNodeWithText(device.name).assertIsEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, selections) }
    }
}
