package app.resound.ui.items

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.resound.digest.ItemStatus
import app.resound.digest.MemoryItem
import app.resound.digest.MemoryRepository
import app.resound.ui.preview.FakeMemoryRepository
import app.resound.ui.theme.ResoundTheme
import app.resound.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** 待办日历视图截图（人工看效果）。SCREEN_SHOTS=目录 时才画 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class TodoAgendaShots {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now()
    private fun at(d: LocalDate, h: Int = 0, m: Int = 0) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun item(id: Long, content: String, day: Long?, h: Int? = null, category: String = "待办", kind: String? = "我要做", who: String? = null, status: ItemStatus = ItemStatus.Accepted): MemoryItem {
        val d = day?.let { today.plusDays(it) }
        val due = d?.let { if (h != null) at(it, h) else at(it) }
        val text = d?.let { if (h != null) "$it ${"%02d".format(h)}:00" else "$it" }
        return MemoryItem(id, null, category, kind, content, text, due, who, status, null, at = at(today.minusDays(1), 10))
    }
    private val ongoing = listOf(
        item(1, "交报销单", -2), item(2, "给房东回电话", -1, 9),
        item(3, "把发布会演示稿发给小林", 0, 15, kind = "我答应别人", who = "小林"), item(4, "体检报告拍照发给李医生", 0),
        item(5, "三楼会议室开评审会", 3, 14, category = "约定", kind = null, who = "小林、老王"),
        item(6, "小林答应发评审材料", 4, kind = "别人答应我", who = "小林"),
        item(7, "交季度总结", 9), item(8, "续签房租合同", 26),
        item(9, "有空把报销流程问清楚", null), item(10, "研究一下新的记账方法", null))
    private val pending = listOf(item(11, "和老王吃饭", 3, 19, category = "约定", kind = null, who = "老王", status = ItemStatus.Pending))
    private val repo: MemoryRepository = object : MemoryRepository by FakeMemoryRepository() {
        override fun ongoing(): Flow<List<MemoryItem>> = flowOf(ongoing)
        override fun items(categories: Set<String>, statuses: Set<ItemStatus>?): Flow<List<MemoryItem>> =
            flowOf(if (statuses == setOf(ItemStatus.Pending)) pending else emptyList())
        override fun doneItems(from: kotlinx.datetime.LocalDate): Flow<List<MemoryItem>> = flowOf(emptyList())
    }

    private fun shot(name: String, dark: Boolean, month: Boolean = false) {
        val dir = File(System.getenv("SCREEN_SHOTS") ?: return).also { it.mkdirs() }
        compose.mainClock.autoAdvance = false
        compose.setContent { ResoundTheme(if (dark) ThemeMode.Dark else ThemeMode.Light) { TodoScreen(repo, androidx.compose.ui.unit.Dp(0f)) } }
        fun idle() = repeat(6) { compose.mainClock.advanceTimeBy(300); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
        idle()
        // 有待确认时会先停在“待确认”，往右翻到“进行中”
        compose.onNodeWithText("进行中", substring = true).performClick(); idle()
        if (month) { compose.onNodeWithText(today.year.toString() + "年" + today.monthValue + "月").performClick(); idle() }
        val bitmap = compose.runOnUiThread {
            val view = compose.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
        }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun light() = shot("todo-agenda-light", false)
    @Test fun dark() = shot("todo-agenda-dark", true)
    @Test fun month() = shot("todo-agenda-month", false, month = true)
}
