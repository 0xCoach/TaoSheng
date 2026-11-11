package app.resound.ui.verify

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.resound.devices.DeviceState
import app.resound.devices.DeviceStatus
import app.resound.devices.driver.*
import app.resound.ui.theme.ResoundTheme
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 卡 144 设备详情截图（人工看效果，不做像素比对）。SCREEN_SHOTS=目录 时才画 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [35], qualifiers = "w393dp-h1500dp-xhdpi")
class DeviceUiShots {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun detail() {
        val dir = File(System.getenv("SCREEN_SHOTS") ?: return).also { it.mkdirs() }
        val now = System.currentTimeMillis()
        val meta = DriverMeta("demo", DriverTier.Plugin, Stability.Trial, LocalizedText("示例录音豆 X1"), maintainer = "someone")
        val status = DeviceStatusModel(listOf(
            BatteryPart(1, percent = 73, charging = false, readAtMillis = now - 60_000),
            BatteryPart(2, percent = 50, charging = true, readAtMillis = now - 6 * 60_000)),
            StorageInfo(32L shl 30, 12L shl 30, null), RecordingState.Recording)
        val info = LiveInfo(setOf(Capability.Battery, Capability.Storage, Capability.Record, Capability.AndroidOnlyLight, Capability.Files, Capability.Settings),
            listOf(LightMode(0, LightKind.Default, LocalizedText("默认")), LightMode(1, LightKind.Off, LocalizedText("关闭")), LightMode(0x80, LightKind.Preset, LocalizedText("暖橙常亮"))),
            0, listOf(
                SettingItem("autoSync", SettingKind.Switch, LocalizedText("连上后自动补传"), SettingValue.Bool(true)),
                SettingItem("autoStart", SettingKind.Switch, LocalizedText("开始收音时让设备也开始录"), SettingValue.Bool(false)),
                SettingItem("noise", SettingKind.Choice, LocalizedText("降噪"), SettingValue.Choice(1),
                    options = listOf(SettingOption(0, LocalizedText("关")), SettingOption(1, LocalizedText("会议")), SettingOption(2, LocalizedText("户外")))),
                SettingItem("fw", SettingKind.ReadOnly, LocalizedText("充电盒固件"), SettingValue.Text("1.2.2"))), status)
        val d = DeviceState("AA:BB", "我的录音豆", "demo", DeviceStatus.Live, battery = 73)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ResoundTheme(ThemeMode.Light) {
                Column(Modifier.fillMaxSize().background(Rs.colors.card).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(d.name, style = RsType.title, color = Rs.colors.text)
                    Text(deviceSubtitle(d, meta), style = RsType.subhead, color = Rs.colors.textSecondary)
                    Spacer(Modifier.height(10.dp))
                    DeviceDetailBody(d, null, meta, {}, status, info)
                }
            }
        }
        repeat(4) { compose.mainClock.advanceTimeBy(400); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
        val bitmap = compose.runOnUiThread {
            val view = compose.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
        }
        File(dir, "设备详情.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
