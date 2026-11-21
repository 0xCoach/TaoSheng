package app.resound.ui.today

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.resound.ui.settings.NewVersionBody
import app.resound.ui.theme.ResoundTheme
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.theme.ThemeMode
import app.resound.update.AppUpdater
import app.resound.update.UpdateInfo
import app.resound.update.UpdateState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 卡 188 首页新版本弹窗、横幅、后台受限卡片截图（人工看效果）。SCREEN_SHOTS=目录 时才画 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [35], qualifiers = "w393dp-h1700dp-xhdpi")
class HomeNotices188Shots {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val info = UpdateInfo(80, "0.3.2", "### 这次更新\n- 鸿蒙、华为手机后台也能按时整理\n- 新版本在首页直接提示\n- 问题记录合并到“高级与诊断”\n- 去掉旧网络实现\n- 修复若干小问题\n- 第六行\n- 第七行\n- 第八行", 3_400_000, true)
    private val updater = object : AppUpdater {
        override val currentVersion = "0.3.1"
        override val state = MutableStateFlow<UpdateState>(UpdateState.Available(info))
        override fun check(manual: Boolean) {}
        override fun download() {}
        override fun install() {}
        override fun later() {}
        override fun skip() {}
        override val autoCheck = MutableStateFlow(true)
        override fun setAutoCheck(enabled: Boolean) {}
        override fun canInstall() = true
    }

    private fun shot(name: String, dark: Boolean) {
        val dir = File(System.getenv("SCREEN_SHOTS") ?: return).also { it.mkdirs() }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ResoundTheme(if (dark) ThemeMode.Dark else ThemeMode.Light) {
                val c = Rs.colors
                Column(Modifier.fillMaxSize().background(c.background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("首页横幅（各状态）", style = RsType.caption, color = c.textTertiary)
                    UpdateBanner("0.3.2", UpdateState.Available(info)) {}
                    UpdateBanner("0.3.2", UpdateState.Downloading(info, 1_200_000, 3_400_000)) {}
                    UpdateBanner("0.3.2", UpdateState.Ready(info)) {}
                    UpdateBanner("0.3.2", UpdateState.Failed("安装没成功", info)) {}
                    Text("后台受限卡片", style = RsType.caption, color = c.textTertiary)
                    BackgroundLimitCard("把声声不息的“应用启动管理”改成手动、三项全开，整理就能按时进行。", {}, {})
                    Text("弹出面板内容（有新版本 / 下载中 / 安装失败）", style = RsType.caption, color = c.textTertiary)
                    for (s in listOf<UpdateState>(UpdateState.Available(info), UpdateState.Downloading(info, 1_200_000, 3_400_000), UpdateState.Failed("系统没让安装，可以用浏览器下载", info)))
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.elevated).padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("声声不息 0.3.2 来了", style = RsType.title, color = c.text)
                            NewVersionBody(info, s, true, updater, {}, previewLines = 5, showVersion = false)
                        }
                }
            }
        }
        repeat(4) { compose.mainClock.advanceTimeBy(400); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
        val bitmap = compose.runOnUiThread {
            val view = compose.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
        }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun light() = shot("188-首页提示-日间", false)
    @Test fun dark() = shot("188-首页提示-夜间", true)
}
