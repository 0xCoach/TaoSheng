package app.resound.ui.transcript

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.resound.transcribe.TranscriptLine
import app.resound.ui.theme.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.File

/** 卡 211：“已修正”标记和原地编辑框截图，只供人工查看 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
class LineEdit211ShotsTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    @Test fun editedMarkAndEditor() {
        val dir = File(System.getenv("EDIT_SHOTS") ?: File(compose.activity.cacheDir, "edit211").path).apply { mkdirs() }
        val at = 1_794_900_000_000L
        val lines = listOf(
            TranscriptLine(1, at, at + 3000, "明天上午找张伟把方案过一遍", "0", segmentId = 1, edited = true, eventId = 5),
            TranscriptLine(2, at + 3000, at + 6000, "好的，我把材料先发你", "1", segmentId = 1),
        )
        var theme by mutableStateOf(ThemeMode.Light)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ResoundTheme(theme) {
                Column(Modifier.fillMaxSize().background(Rs.colors.elevated).padding(20.dp)) {
                    Spacer(Modifier.height(24.dp))
                    SpeakerLines(lines)
                    Spacer(Modifier.height(24.dp))
                    LineEditor("明天上午找张伟把方案过一遍", {}, false, onCancel = {}, onSave = {})
                }
            }
        }
        listOf(ThemeMode.Light, ThemeMode.Dark).forEach { mode ->
            theme = mode
            repeat(4) { compose.mainClock.advanceTimeBy(300); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
            val bitmap = compose.runOnUiThread {
                val view = compose.activity.window.decorView
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
            }
            File(dir, "${mode.name}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
