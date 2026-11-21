package app.resound.ui.promo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.resound.digest.MemoryEvent
import app.resound.digest.TodoDraft
import app.resound.digest.TodoDrafts
import app.resound.search.ExpandedSearchHit
import app.resound.search.SearchKind
import app.resound.search.SearchSource
import app.resound.ui.items.DraftSheet
import app.resound.ui.preview.FakeCaptureController
import app.resound.ui.preview.FakeMemoryRepository
import app.resound.ui.preview.FakeTranscriptRepository
import app.resound.ui.theme.ResoundTheme
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.theme.ThemeMode
import app.resound.ui.today.*
import app.resound.ui.verify.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** 宣传图用的真实界面渲染（假数据）。SCREEN_SHOTS=目录 时才画 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class Promo041Shots {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now()
    private fun at(h: Int, m: Int, d: LocalDate = today) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun ev(id: Long, h: Int, m: Int, mins: Int, scene: String, title: String, summary: String, people: List<String>, starred: Boolean = false) =
        MemoryEvent(id, at(h, m), at(h, m) + mins * 60_000L, scene, title, summary, emptyList(), people, null, "平静", 2, false, emptyList(), starred)

    private val morning = listOf(
        ev(1, 9, 12, 3, "独白", "安排今天要做的事", "先改发布会演示稿，下午和小林过一遍，记得把体检报告发给李医生。", listOf("小林", "李医生")),
        ev(2, 10, 30, 42, "会议", "发布会彩排安排", "确定周三下午彩排，我负责周一前把演示稿发给小林，预算按十二万报。", listOf("小林", "老王"), starred = true))
    private val afternoon = listOf(
        ev(3, 12, 5, 25, "闲聊", "午饭聊新开的酸菜鱼", "这家比上次那家好吃，下次带爸妈来，周末要提前订位。", listOf("爸妈")),
        ev(4, 15, 40, 8, "电话", "和老王改饭局时间", "饭局改到下周四晚上七点，老王订位，我负责通知其他人。", listOf("老王")))

    private fun capture(name: String, dark: Boolean, allWindows: Boolean = false, content: @Composable () -> Unit) {
        val dir = File(System.getenv("SCREEN_SHOTS") ?: return).also { it.mkdirs() }
        compose.mainClock.autoAdvance = false
        compose.setContent { ResoundTheme(if (dark) ThemeMode.Dark else ThemeMode.Light) { content() } }
        repeat(8) { compose.mainClock.advanceTimeBy(400); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
        val bitmap = compose.runOnUiThread {
            val root = compose.activity.window.decorView
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val views: List<View> = if (!allWindows) listOf(root) else {
                val wmg = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
                @Suppress("UNCHECKED_CAST")
                (wmg.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(wmg) as List<View>).toList()
            }
            views.forEach { v ->
                val loc = IntArray(2); v.getLocationOnScreen(loc)
                canvas.save(); canvas.translate(loc[0].toFloat(), loc[1].toFloat()); v.draw(canvas); canvas.restore()
            }
            bmp
        }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable private fun HomeHeader(dark: Boolean) {
        val c = Rs.colors
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("今天 · 星期" + listOf("一", "二", "三", "四", "五", "六", "日")[today.dayOfWeek.value - 1], style = RsType.subhead, color = c.textSecondary)
                Text("${today.monthValue}月${today.dayOfMonth}日", style = RsType.largeTitle, color = c.text)
            }
            SearchButton {}; Spacer(Modifier.width(8.dp)); ThemeModePill(if (dark) ThemeMode.Dark else ThemeMode.Light) {}
        }
    }

    private fun home(dark: Boolean) = capture(if (dark) "home-dark" else "home-light", dark) {
        val c = Rs.colors
        Column(Modifier.fillMaxSize().background(c.background).padding(horizontal = 16.dp).padding(top = 44.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            HomeHeader(dark)
            OverviewCard(FakeCaptureController(), FakeMemoryRepository(), FakeTranscriptRepository(), true, 3 * 3600_000L + 42 * 60_000L, 12, 3, null, {}, {})
            TimelineGroup("上午", morning.map { TimelineEntry.Event(it) }) {}
            TimelineGroup("下午", afternoon.map { TimelineEntry.Event(it) }) {}
        }
    }
    @Test fun homeLight() = home(false)
    @Test fun homeDark() = home(true)

    private fun search(dark: Boolean) = capture(if (dark) "search-dark" else "search-light", dark) {
        val c = Rs.colors
        val words = listOf("小林")
        val todos = listOf(
            ExpandedSearchHit(SearchSource.Todo, 7, at(10, 40), "", "周一前把演示稿发给小林", itemCategory = "待办", status = "Accepted"),
            ExpandedSearchHit(SearchSource.Todo, 8, at(16, 0, today.minusDays(5)), "", "帮小林问体检中心的预约电话", itemCategory = "待办", status = "Done"))
        val memory = listOf(ExpandedSearchHit(SearchSource.Memory, 9, at(20, 10, today.minusDays(45)), "", "小林的生日是 3 月 8 日，喜欢喝美式", itemCategory = "关键信息"))
        val lines = listOf(ExpandedSearchHit(SearchSource.Line, 2, at(10, 35), "", "演示稿你周一前发我就行，我周二再过一遍", speaker = "小林", segmentId = 1))
        val asks = listOf(ExpandedSearchHit(SearchSource.AskMessage, 3, at(21, 5, today.minusDays(2)), "", "小林上次说的预算是多少？"))
        Column(Modifier.fillMaxSize().background(c.background).padding(horizontal = 16.dp).padding(top = 44.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HomeHeader(dark)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.elevated).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("搜索记录", style = RsType.title, color = c.text)
                SheetInput("小林", "搜原话、事件、待办、记忆，空格分开多个词") {}
                KindCapsules(null, mapOf(SearchKind.Line to 36, SearchKind.Event to 9, SearchKind.Todo to 2, SearchKind.Memory to 1, SearchKind.Other to 1)) {}
                RangeCapsules(SearchRange.All) {}
                SectionHeader("待办", 2) {}
                HitCard(todos, words, zone, showDay = true) {}
                SectionHeader("记忆", 1) {}
                HitCard(memory, words, zone, showDay = true) {}
                SectionHeader("对话", 36) {}
                HitCard(lines, words, zone, showDay = true) {}
                SectionHeader("其他", 1) {}
                HitCard(asks, words, zone, showDay = true) {}
            }
        }
    }
    @Test fun searchLight() = search(false)
    @Test fun searchDark() = search(true)

    /** 说完一句话后的待办确认卡（真实的确认面板） */
    @Test fun draft() = capture("draft-light", false, allWindows = true) {
        val c = Rs.colors
        Box(Modifier.fillMaxSize().background(c.background))
        val due = today.plusDays(1)
        DraftSheet(TodoDrafts(listOf(TodoDraft("待办", "我答应别人", "把发布会演示稿发给小林", "$due", at(0, 0, due), "小林", false)), true),
            "明天记得把发布会演示稿发给小林", onDismiss = {}, onConfirm = {})
    }

    /** 语音加待办面板（照 QuickTodoFlow 的样子，正在听） */
    @Test fun voice() = capture("voice-light", false) {
        val c = Rs.colors
        Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(c.elevated)
                .padding(start = 20.dp, end = 20.dp, bottom = 36.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 10.dp).size(width = 36.dp, height = 5.dp).clip(CircleShape).background(c.separator))
                Text("说一下要做的事", style = RsType.title, color = c.text)
                Text("几件事一起说也行，说完点“说完了”", style = RsType.subhead, color = c.textSecondary)
                Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
                    BreathingWave(0.7f, active = true, recording = true, modifier = Modifier.fillMaxWidth().height(40.dp), tint = c.accent)
                }
                ActionButton("说完了", ActionKind.Primary) {}
                ActionButton("改为打字", ActionKind.Secondary) {}
            }
        }
    }

    /** 同步文件夹里的“复制给智能体”（照 PushPage 的样子） */
    @Test fun agent() = capture("agent-light", false) {
        val c = Rs.colors
        Column(Modifier.fillMaxSize().background(Color(0x66000000)).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Bottom) {
            Spacer(Modifier.height(150.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(c.elevated)
                .padding(start = 20.dp, end = 20.dp, bottom = 36.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 10.dp).size(width = 36.dp, height = 5.dp).clip(CircleShape).background(c.separator))
                Text("同步文件夹", style = RsType.title, color = c.text)
                Text("存到哪里", style = RsType.caption, color = c.textSecondary)
                Text("用数据备份里的 坚果云（me@example.com），不用再填账号密码", style = RsType.caption, color = c.textSecondary)
                Text("文件夹", style = RsType.caption, color = c.textSecondary)
                SheetInput("Resound-Memory", "") {}
                Text("上次同步：今天 21:30 · 已同步 186 个文件", style = RsType.caption, color = c.good)
                ActionButton("现在推一次", ActionKind.Secondary) {}
                ActionButton("复制给智能体", ActionKind.Primary) {}
                Text("粘贴给 Claude Code、Codex 等能运行命令的智能体，它就能读你的记忆。这段话含应用密码；不想让它读了，到网盘设置里删掉这个应用密码就行。",
                    style = RsType.caption, color = c.textSecondary)
                ActionButton("测试一下", ActionKind.Secondary) {}
            }
        }
    }

    /** 桌面小组件：用真实布局 widget_capture.xml 画出来 */
    @Test fun widget() {
        val dir = File(System.getenv("SCREEN_SHOTS") ?: return).also { it.mkdirs() }
        val ctx = compose.activity
        for ((name, status, sub) in listOf(Triple("widget-listen", "正在聆听", "今天说话 3 小时 42 分"), Triple("widget-topic", "会议 · 18:32", "已标 2 个重点"))) {
            val v = android.view.LayoutInflater.from(ctx).inflate(app.resound.R.layout.widget_capture, null)
            v.findViewById<android.widget.TextView>(app.resound.R.id.widget_status).text = status
            v.findViewById<android.widget.TextView>(app.resound.R.id.widget_sub).text = sub
            if (name == "widget-topic") v.findViewById<android.widget.ImageView>(app.resound.R.id.widget_topic).setImageResource(app.resound.R.drawable.ic_qa_mark)
            val w = (330 * ctx.resources.displayMetrics.density).toInt(); val h = (76 * ctx.resources.displayMetrics.density).toInt()
            v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, w, h)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); v.draw(Canvas(bmp))
            File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
