/*
 * [INPUT]: 快传操作区、录音状态与模拟点击。
 * [OUTPUT]: 入口可用性、连接中取消与就绪传输提示的界面回归。
 * [POS]: Wi-Fi 页面的最小离线验证；系统权限弹窗仍由真机验证。
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package app.resound.sonicloud

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.resound.ui.theme.ResoundTheme
import app.resound.ui.theme.ThemeMode
import app.resound.ui.verify.RecorderWifiActions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [32], qualifiers = "w393dp-h852dp-xhdpi")
class RecorderWifiUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun stoppedCardCanOpenWifiButRecordingCardCannot() {
        var state by mutableStateOf(RecorderStatus(connected = true, recording = RecorderRecording.Recording))
        var connects = 0
        compose.setContent { ResoundTheme(ThemeMode.Light) {
            Column { RecorderWifiActions(state, RecorderWifiStatus(), { connects++ }, {}) }
        } }
        compose.onNodeWithText("传输方式：蓝牙").assertExists()
        compose.onNodeWithText("开启 Wi-Fi 快传").performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, connects); state = state.copy(recording = RecorderRecording.Stopped) }
        compose.onNodeWithText("开启 Wi-Fi 快传").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, connects) }
    }

    @Test fun connectingShowsSystemPromptAndAllowsCancellation() {
        var cancels = 0
        compose.setContent { ResoundTheme(ThemeMode.Light) {
            Column { RecorderWifiActions(
                RecorderStatus(connected = true, recording = RecorderRecording.Stopped),
                RecorderWifiStatus(RecorderWifiPhase.Joining, "Soni-Test"), {}, { cancels++ },
            ) }
        } }
        compose.onNodeWithText("Wi-Fi 快传：请在系统弹窗中允许连接 Soni-Test").assertExists()
        compose.onNodeWithText("开启 Wi-Fi 快传").assertDoesNotExist()
        compose.onNodeWithText("取消 Wi-Fi 连接").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, cancels) }
    }

    @Test fun activeDownloadIdentifiesWifiAndRetainsCancelAction() {
        var cancels = 0
        compose.setContent { ResoundTheme(ThemeMode.Light) {
            Column { RecorderWifiActions(
                RecorderStatus(connected = true, downloading = "sample.opus"),
                RecorderWifiStatus(RecorderWifiPhase.Ready, "Soni-Test"), {}, { cancels++ },
            ) }
        } }
        compose.onNodeWithText("传输方式：Wi-Fi 快传 · Soni-Test").assertExists()
        compose.onNodeWithText("停止快传（保留断点）").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, cancels) }
    }
}
