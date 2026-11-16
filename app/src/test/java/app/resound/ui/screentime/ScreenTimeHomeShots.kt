package app.resound.ui.screentime

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.resound.digest.MemoryEvent
import app.resound.transcribe.TranscriptLine
import app.resound.ui.preview.FakeCaptureController
import app.resound.ui.preview.FakeMemoryRepository
import app.resound.ui.preview.FakeTranscriptRepository
import app.resound.ui.theme.ResoundTheme
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.theme.ThemeMode
import app.resound.ui.today.*
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.RsToggle
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** 屏幕时间首页真实组件渲染（假数据）。SCREEN_SHOTS=目录 时才画 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class ScreenTimeHomeShots {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now()
    private fun at(h: Int, m: Int) = today.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun ev(id: Long, h: Int, m: Int, mins: Int, scene: String, title: String, summary: String, people: List<String>, starred: Boolean = false) =
        MemoryEvent(id, at(h, m), at(h, m) + mins * 60_000L, scene, title, summary, emptyList(), people, null, "平静", 2, false, emptyList(), starred)

    private val events = listOf(
        ev(1, 9, 12, 3, "独白", "安排今天要做的事", "先改发布会演示稿，下午和小林过一遍，记得把体检报告发给李医生。", listOf("小林", "李医生")),
        ev(2, 10, 30, 42, "会议", "发布会彩排安排", "确定周三下午彩排，我负责周一前把演示稿发给小林，预算按十二万报。", listOf("小林", "老王"), starred = true),
        ev(3, 12, 5, 25, "闲聊", "午饭聊新开的酸菜鱼", "这家比上次那家好吃，下次带爸妈来，周末要提前订位。", listOf("爸妈")),
        ev(4, 15, 40, 8, "电话", "和老王改饭局时间", "饭局改到下周四晚上七点，老王订位，我负责通知其他人。", listOf("老王")))
    /** 每小时说话分钟数，造出句子给节奏图用 */
    private val talk = mapOf(9 to 6, 10 to 34, 11 to 12, 12 to 20, 13 to 4, 14 to 6, 15 to 14, 16 to 8, 18 to 10, 19 to 16, 20 to 9, 21 to 5)
    private val lines = talk.flatMap { (h, mins) -> (0 until mins).map { i -> TranscriptLine(h * 100L + i, at(h, i), at(h, i) + 50_000L, "…", null) } }
    private val phone = listOf(0, 0, 0, 0, 0, 0, 0, 4, 12, 9, 6, 8, 28, 14, 10, 22, 9, 7, 6, 11, 13, 26, 7, 0)
    private data class App(val name: String, val minutes: Int, val tint: Color)
    private val apps = listOf(
        App("微信", 58, Color(0xFF2BAE66)), App("哔哩哔哩", 41, Color(0xFFF06A9B)), App("小红书", 27, Color(0xFFE5484D)),
        App("飞书", 22, Color(0xFF3370FF)), App("抖音", 18, Color(0xFF222222)), App("淘宝", 12, Color(0xFFFF6A00)),
        App("高德地图", 8, Color(0xFF2E7CF6)), App("小宇宙", 6, Color(0xFF7B61FF)))

    private fun capture(name: String, dark: Boolean, content: @Composable () -> Unit) {
        val dir = File(System.getenv("SCREEN_SHOTS") ?: return).also { it.mkdirs() }
        compose.mainClock.autoAdvance = false
        compose.setContent { ResoundTheme(if (dark) ThemeMode.Dark else ThemeMode.Light) { content() } }
        repeat(8) { compose.mainClock.advanceTimeBy(400); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
        val bitmap = compose.runOnUiThread {
            val root = compose.activity.window.decorView
            Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
        }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }


    private fun home(dark: Boolean, name: String) = capture(name, dark) {
        val c = Rs.colors
        Column(Modifier.fillMaxSize().background(c.background).padding(horizontal = 16.dp).padding(top = 44.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OverviewCard(FakeCaptureController(), FakeMemoryRepository(), FakeTranscriptRepository(), true, 2 * 3600_000L + 24 * 60_000L, 12, 2, null, {}, {},
                screenMinutes = 192)
            OverviewCard(FakeCaptureController(), FakeMemoryRepository(), FakeTranscriptRepository(), true, 2 * 3600_000L + 24 * 60_000L, 12, 0, null, {}, {})
            RhythmCard(events, lines, today, 0, {}, {}, {}, screenByHour = phone)
        }
    }
    @Test fun light() = home(false, "home-real-light")
    @Test fun dark() = home(true, "home-real-dark")
}
