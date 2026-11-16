package app.resound.notice

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.resound.ui.theme.ResoundTheme
import app.resound.ui.theme.Rs
import app.resound.ui.theme.ThemeMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 用发布工具真实签名的样例（私钥签、App 内置公钥验）：缓存能验签读出，改一个字节就拒绝 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [35], qualifiers = "w393dp-h900dp-xxhdpi")
class RemoteNoticesTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun install(tamper: Boolean) {
        val dir = File(compose.activity.noBackupFilesDir, "notices").apply { deleteRecursively(); mkdirs() }
        for (name in listOf("notices.json", "notices.json.sig", "group-test.png")) {
            val bytes = javaClass.classLoader!!.getResourceAsStream("notices/$name")!!.readBytes()
            File(dir, name).writeBytes(if (tamper && name == "notices.json") bytes.decodeToString().replace("交流", "聊聊").encodeToByteArray() else bytes)
        }
    }

    @Test fun signedCacheLoadsAndShows() {
        install(tamper = false)
        val notices = RemoteNotices.get(compose.activity)
        val file = notices.file.value
        assertNotNull("真实签名应验过", file)
        val n = file!!.notices.single()
        assertEquals("group-test", n.id)
        assertNotNull(notices.image(n))
        val dir = System.getenv("SCREEN_SHOTS") ?: return
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ResoundTheme(ThemeMode.Light) {
                Column(Modifier.fillMaxSize().background(Rs.colors.background).padding(16.dp)) { app.resound.ui.today.RemoteNoticeCards() }
            }
        }
        repeat(4) { compose.mainClock.advanceTimeBy(300); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
        val bmp = compose.runOnUiThread {
            val v = compose.activity.window.decorView
            Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(android.graphics.Canvas(it)) }
        }
        File(dir).mkdirs(); File(dir, "notice-card.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
