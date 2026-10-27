package app.resound.ui

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import app.resound.ui.ask.AskScreen
import app.resound.ui.items.MemoryScreen
import app.resound.ui.items.TodoScreen
import app.resound.ui.preview.*
import app.resound.ui.settings.SettingsScreen
import app.resound.ui.theme.ResoundTheme
import app.resound.ui.theme.ThemeMode
import app.resound.ui.today.TodayScreen
import app.resound.ui.verify.VerifyScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 用假数据把主要页面画成图片，人工看不同屏幕和字体下有没有挤压、截断。不做像素比对。
 * 只在需要时手动跑：SCREEN_SHOTS=目录 ./gradlew testDebugUnitTest --tests '*ScreenSizeShots*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [35])
class ScreenSizeShots {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test @Config(qualifiers = "w320dp-h568dp-xhdpi") fun small() = shoot("1-小屏", 1f)
    @Test @Config(qualifiers = "w393dp-h852dp-xhdpi") fun normal() = shoot("2-常见", 1f)
    @Test @Config(qualifiers = "w393dp-h852dp-xhdpi") fun bigFont() = shoot("3-大字体", 1.5f)
    @Test @Config(qualifiers = "w320dp-h568dp-xhdpi") fun smallBigFont() = shoot("4-小屏大字体", 1.5f)
    @Test @Config(qualifiers = "w840dp-h900dp-xhdpi") fun unfolded() = shoot("5-折叠屏展开", 1f)
    @Test @Config(qualifiers = "w852dp-h393dp-xhdpi") fun landscape() = shoot("6-横屏", 1f)

    private fun shoot(name: String, fontScale: Float) {
        val dir = File(System.getenv("SCREEN_SHOTS") ?: return).also { it.mkdirs() }
        val capture = FakeCaptureController()
        val memory = FakeMemoryRepository()
        val transcripts = FakeTranscriptRepository()
        val digest = FakeDigestSettings()
        val tabs = listOf<Pair<String, @androidx.compose.runtime.Composable () -> Unit>>(
            "今天" to { TodayScreen(capture, FakeStatsRepository(), transcripts, memory, digest, ThemeMode.Light, {}, ShellBottomPadding) },
            "问问" to { AskScreen(FakeAskRepository(), ShellBottomPadding) },
            "记忆" to { MemoryScreen(memory, null, transcripts, null, null, {}, ShellBottomPadding) },
            "待办" to { TodoScreen(memory, ShellBottomPadding) },
            "设置" to { SettingsScreen(capture, FakeTranscribeSettings(), digest, ThemeMode.Light, {}, ShellBottomPadding) { _, _ -> } },
            "聆听" to { VerifyScreen(capture, FakeStatsRepository(), FakeSetupGuide(), ThemeMode.Light) {} },
        )
        var tab by androidx.compose.runtime.mutableIntStateOf(0)
        // 波形等动画一直在动，自动走时钟会永远等不到空闲；改成手动拨时钟
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                ResoundTheme(ThemeMode.Light) {
                    // 前五页放在底栏外壳里切换；“聆听”是设置里的子页，单独画
                    if (tab < 5) AppShell(tabs.take(5), tab to tab.toLong()) else tabs[tab].second()
                }
            }
        }
        tabs.indices.forEach { i ->
            tab = i
            // 切页要先跑完“收到请求→切页→淡入”几帧，每拨一次时钟都让主线程把排队的事做完
            repeat(4) { compose.mainClock.advanceTimeBy(400); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
            // 时钟停住时等不到“下一帧”，不能用 captureToImage；直接把窗口画到图片上
            val bitmap = compose.runOnUiThread {
                val view = compose.activity.window.decorView
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
            }
            File(dir, "$name-${tabs[i].first}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
