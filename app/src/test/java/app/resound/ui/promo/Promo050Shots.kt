package app.resound.ui.promo

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** 0.5.0 宣传图用的真实界面渲染（假数据）。SCREEN_SHOTS=目录 时才画 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class Promo050Shots {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val zone = ZoneId.systemDefault()
    /** 用昨天的数据：节奏图上不画“现在”竖线，整天都有柱子；页头照样写“今天” */
    private val today = LocalDate.now().minusDays(1)
    private fun at(h: Int, m: Int) = today.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun item(id: Long, cat: String, text: String) =
        app.resound.digest.MemoryItem(id, null, cat, null, text, null, null, null, app.resound.digest.ItemStatus.Pending, null)
    private fun ev(id: Long, h: Int, m: Int, mins: Int, scene: String, title: String, summary: String, people: List<String>, place: String? = null,
                   starred: Boolean = false, items: List<app.resound.digest.MemoryItem> = emptyList()) =
        MemoryEvent(id, at(h, m), at(h, m) + mins * 60_000L, scene, title, summary, emptyList(), people, place, "平静", 2, false, items, starred)
    private val meeting = app.resound.skills.SkillCard(1, "meeting", "会议纪要", "📋", kotlinx.datetime.LocalDate(today.year, today.monthValue, today.dayOfMonth), at(10, 30), at(11, 12), "发布会彩排安排", "",
        emptyList(), emptyMap(), emptyList(), at(11, 20), false)

    private val morning = listOf(
        ev(1, 9, 12, 3, "独白", "安排今天要做的事", "先改发布会演示稿，下午和小林过一遍，记得把体检报告发给李医生。", listOf("小林", "李医生"),
            items = listOf(item(11, "待办", "改发布会演示稿"), item(12, "待办", "把体检报告发给李医生"))),
        ev(2, 10, 30, 42, "会议", "发布会彩排安排", "确定周三下午彩排，我负责周一前把演示稿发给小林，预算按十二万报。", listOf("小林", "老王"), "公司", starred = true,
            items = listOf(item(21, "约定", "周一前把演示稿发给小林"), item(22, "灵感", "彩排加一段现场演示"))))
    private val afternoon = listOf(
        ev(3, 12, 5, 25, "闲聊", "午饭聊新开的酸菜鱼", "这家比上次那家好吃，下次带爸妈来，周末要提前订位。", listOf("爸妈"), items = listOf(item(31, "灵感", "周末带爸妈来吃"))),
        ev(4, 15, 40, 8, "电话", "和老王改饭局时间", "饭局改到下周四晚上七点，老王订位，我负责通知其他人。", listOf("老王"), items = listOf(item(41, "待办", "通知大家饭局改到周四"))))
    private val talk = mapOf(9 to 6, 10 to 34, 11 to 12, 12 to 20, 13 to 4, 14 to 6, 15 to 14, 16 to 8, 18 to 10, 19 to 16, 20 to 9, 21 to 5)
    private val lines = talk.flatMap { (h, mins) -> (0 until mins).map { i -> TranscriptLine(h * 100L + i, at(h, i), at(h, i) + 50_000L, "…", null) } }
    private val phone = listOf(0, 0, 0, 0, 0, 0, 0, 4, 12, 9, 6, 8, 28, 14, 10, 22, 9, 7, 6, 11, 13, 26, 7, 0)

    private fun capture(name: String, content: @Composable () -> Unit) {
        val dir = File(System.getenv("SCREEN_SHOTS") ?: return).also { it.mkdirs() }
        compose.mainClock.autoAdvance = false
        compose.setContent { ResoundTheme(ThemeMode.Light) { content() } }
        repeat(8) { compose.mainClock.advanceTimeBy(400); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
        val bitmap = compose.runOnUiThread {
            val root = compose.activity.window.decorView
            Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
        }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** 封面：首页（人声·屏幕·事件、说话柱+手机色带、时间线） */
    @Test fun home() = capture("home-050") {
        val c = Rs.colors
        val week = listOf("一", "二", "三", "四", "五", "六", "日")[today.dayOfWeek.value - 1]
        Column(Modifier.fillMaxSize().background(c.background).padding(horizontal = 16.dp).padding(top = 44.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("今天 · 星期$week", style = RsType.subhead, color = c.textSecondary)
                    Text("${today.monthValue}月${today.dayOfMonth}日", style = RsType.largeTitle, color = c.text)
                }
                SearchButton {}; Spacer(Modifier.width(8.dp)); app.resound.ui.verify.ThemeModePill(ThemeMode.Light) {}
            }
            OverviewCard(FakeCaptureController(), FakeMemoryRepository(), FakeTranscriptRepository(), true, 2 * 3600_000L + 24 * 60_000L, 12, 0, null, {}, {},
                screenMinutes = 194)
            RhythmCard(morning + afternoon, lines, today, 0, {}, {}, {}, meetings = listOf(meeting), screenByHour = phone)
            TimelineGroup("上午", morning.map { TimelineEntry.Event(it) }) {}
            TimelineGroup("下午", afternoon.map { TimelineEntry.Event(it) }) {}
        }
    }

    // ---------- 屏幕时间详情（长图，排版时裁两段） ----------
    private fun s(pkg: String, name: String, h: Int, m: Int, mins: Int) = app.resound.screentime.NamedAppSession("app.$pkg", name, at(h, m), at(h, m) + mins * 60_000L)
    private val sessions = listOf(
        s("wechat", "微信", 7, 40, 6), s("map", "高德地图", 8, 30, 12), s("wechat", "微信", 9, 20, 9), s("feishu", "飞书", 10, 5, 6),
        s("feishu", "飞书", 11, 10, 8), s("xhs", "小红书", 12, 10, 18), s("wechat", "微信", 12, 32, 10), s("feishu", "飞书", 13, 15, 14),
        s("xhs", "小红书", 14, 20, 10), s("wechat", "微信", 15, 5, 12), s("bili", "哔哩哔哩", 15, 20, 10), s("feishu", "飞书", 16, 10, 9),
        s("wechat", "微信", 17, 30, 7), s("map", "高德地图", 18, 10, 6), s("bili", "哔哩哔哩", 19, 5, 11), s("wechat", "微信", 20, 10, 13),
        s("xyz", "小宇宙", 21, 5, 4), s("bili", "哔哩哔哩", 21, 20, 22), s("wechat", "微信", 22, 15, 7))
    private val playback = listOf(
        app.resound.screentime.DevicePlayback("app.xyz", "小宇宙", "第 87 期：AI 怎么记住你的一天", "夜航电台", at(21, 5), at(21, 52)),
        app.resound.screentime.DevicePlayback("app.bili", "哔哩哔哩", "手把手做一个桌面小机器人", "阿木实验室", at(19, 5), at(19, 16)))

    @Config(qualifiers = "w393dp-h1800dp-xxhdpi")
    @Test fun screen() = capture("screen-050") {
        val c = Rs.colors
        val result = app.resound.screentime.ScreenTimeResult(true, sessions, at(23, 0), emptyList(), playback)
        val kdate = kotlinx.datetime.LocalDate(today.year, today.monthValue, today.dayOfMonth)
        val report = app.resound.screentime.ScreenTimeReports.build(kdate, kotlinx.datetime.TimeZone.currentSystemDefault(), result)!!
        val bounds = app.resound.ui.screentime.dayBounds(today)
        val day = app.resound.ui.screentime.ScreenDay(today, report, app.resound.screentime.ScreenTimeView.minutesByHour(sessions, bounds))
        Column(Modifier.fillMaxSize().background(c.elevated).padding(horizontal = 20.dp).padding(top = 52.dp)) {
            Text("屏幕时间", style = RsType.title, color = c.text)
            Text("数据只存在手机里", style = RsType.subhead, color = c.textSecondary)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { app.resound.ui.skills.Segments(listOf("日", "周"), 0) {} }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", style = RsType.title, color = c.textSecondary, modifier = Modifier.padding(horizontal = 12.dp))
                Text("今天", style = RsType.subhead, color = c.text, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f))
                Text("›", style = RsType.title, color = c.separator, modifier = Modifier.padding(horizontal = 12.dp))
            }
            app.resound.ui.screentime.DayContent(today, day, 205, morning + afternoon) {}
        }
    }


    // ---------- 问问 ----------
    @Test fun ask() = capture("ask-050") {
        val c = Rs.colors
        val answer = app.resound.ask.AskMessage(2, app.resound.ask.AskRole.Assistant,
            "下午 2 点到 4 点手机用了 32 分钟，主要是：\n\n- **微信** 12 分钟\n- **小红书** 10 分钟\n- **哔哩哔哩** 10 分钟\n\n中间 15:40 和老王通了电话，把饭局改到了下周四晚上七点。",
            emptyList(), at(21, 30), false)
        Column(Modifier.fillMaxSize().background(c.background).padding(horizontal = 16.dp).padding(top = 60.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            app.resound.ui.ask.UserBubble("下午我在刷什么？")
            app.resound.ui.ask.AnswerCard(answer, {}, {})
        }
    }
}
