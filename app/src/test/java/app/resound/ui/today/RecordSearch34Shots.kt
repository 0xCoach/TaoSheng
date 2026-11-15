package app.resound.ui.today

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.resound.search.SearchHit
import app.resound.ui.theme.ResoundTheme
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.theme.ThemeMode
import app.resound.ui.verify.SheetInput
import app.resound.ui.verify.ThemeModePill
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.ZoneId

/** 议题 #34 搜索界面截图（人工看效果）。SCREEN_SHOTS=目录 时才画 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [35], qualifiers = "w393dp-h1100dp-xhdpi")
class RecordSearch34Shots {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(h: Int, m: Int) = java.time.LocalDate.of(2026, 11, 15).atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun shot(name: String, dark: Boolean) {
        val dir = File(System.getenv("SCREEN_SHOTS") ?: return).also { it.mkdirs() }
        val words = listOf("合同")
        val hits = listOf(
            SearchHit(SearchHit.Kind.Event, 1, at(16, 20), "合同签约安排"),
            SearchHit(SearchHit.Kind.Line, 2, at(15, 2), "那份合同明天上午我拿过去给你签，顺便把发票也带上", "老王", 1),
            SearchHit(SearchHit.Kind.Line, 3, at(9, 41), "…上周说的那个合同条款，第三条要改一下，付款时间往后推一个月…", null, 1))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ResoundTheme(if (dark) ThemeMode.Dark else ThemeMode.Light) {
                val c = Rs.colors
                Column(Modifier.fillMaxSize().background(c.background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            Text("今天 · 星期日", style = RsType.subhead, color = c.textSecondary)
                            Text("11月15日", style = RsType.largeTitle, color = c.text)
                        }
                        SearchButton {}; Spacer(Modifier.width(8.dp)); ThemeModePill(ThemeMode.Light) {}
                    }
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.elevated).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("搜索记录", style = RsType.title, color = c.text)
                        SheetInput("合同", "搜原话或事件，空格分开多个词") {}
                        Text("今天 · 11月15日 星期日", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.textSecondary,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.card)) {
                            hits.forEachIndexed { i, h ->
                                HitRow(h, words, zone) {}
                                if (i != hits.lastIndex) Box(Modifier.fillMaxWidth().padding(start = 64.dp).height(0.5.dp).background(c.separator))
                            }
                        }
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
    @Test fun light() = shot("34-搜索-日间", false)
    @Test fun dark() = shot("34-搜索-夜间", true)
}
