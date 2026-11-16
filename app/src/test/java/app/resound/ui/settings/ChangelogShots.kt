package app.resound.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.resound.ui.theme.ResoundTheme
import app.resound.ui.theme.Rs
import app.resound.ui.theme.ThemeMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 更新日志：安装包里能读到 CHANGELOG.md；SCREEN_SHOTS=目录 时画出版本更新页里的日志卡 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [35], qualifiers = "w393dp-h1400dp-xxhdpi")
class ChangelogShots {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun bundledChangelogParses() {
        val entries = Changelog.entries(compose.activity)
        assertTrue("安装包里应带 CHANGELOG.md", entries.size > 10)
        assertTrue(entries.any { it.version == "0.4.2" })
    }

    private fun shot(name: String, dark: Boolean) {
        val dir = File(System.getenv("SCREEN_SHOTS") ?: return).also { it.mkdirs() }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ResoundTheme(if (dark) ThemeMode.Dark else ThemeMode.Light) {
                Column(Modifier.fillMaxSize().background(Rs.colors.background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ChangelogCard("0.4.2")
                }
            }
        }
        repeat(6) { compose.mainClock.advanceTimeBy(300); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
        val bmp = compose.runOnUiThread {
            val v = compose.activity.window.decorView
            Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(android.graphics.Canvas(it)) }
        }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun light() = shot("changelog-light", false)
    @Test fun dark() = shot("changelog-dark", true)
}
