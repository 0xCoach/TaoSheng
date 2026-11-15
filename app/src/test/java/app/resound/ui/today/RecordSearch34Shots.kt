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
import app.resound.search.ExpandedSearchHit
import app.resound.search.SearchKind
import app.resound.search.SearchSource
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
        val day = java.time.LocalDate.of(2026, 11, 12).atTime(10, 5).atZone(zone).toInstant().toEpochMilli()
        val lines = listOf(
            ExpandedSearchHit(SearchSource.Line, 2, at(15, 2), "", "那份合同明天上午我拿过去给你签，顺便把发票也带上", speaker = "老王", segmentId = 1),
            ExpandedSearchHit(SearchSource.Line, 3, at(9, 41), "", "…上周说的那个合同条款，第三条要改一下，付款时间往后推一个月…", segmentId = 1))
        val events = listOf(ExpandedSearchHit(SearchSource.Event, 1, at(16, 20), "合同签约安排", "约好明天上午在公司把合同签了，老王带发票"))
        val todos = listOf(
            ExpandedSearchHit(SearchSource.Todo, 7, at(16, 25), "", "明天上午把合同给老王签", itemCategory = "待办", status = "Accepted"),
            ExpandedSearchHit(SearchSource.Todo, 8, day, "", "合同第三条付款时间改成下月", itemCategory = "待办", status = "Done"))
        val memory = listOf(ExpandedSearchHit(SearchSource.Memory, 9, day, "", "公司合同模板在共享盘“法务”文件夹", itemCategory = "关键信息"))
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
                        SheetInput("合同", "搜原话、事件、待办、记忆，空格分开多个词") {}
                        KindCapsules(null, mapOf(SearchKind.Line to 23, SearchKind.Event to 4, SearchKind.Todo to 2,
                            SearchKind.Memory to 1, SearchKind.Other to 0)) {}
                        RangeCapsules(SearchRange.All) {}
                        SectionHeader("对话", 23) {}
                        HitCard(lines, words, zone, showDay = true) {}
                        SectionHeader("事件", 4) {}
                        HitCard(events, words, zone, showDay = true) {}
                        SectionHeader("待办", 2) {}
                        HitCard(todos, words, zone, showDay = true) {}
                        SectionHeader("记忆", 1) {}
                        HitCard(memory, words, zone, showDay = true) {}
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
