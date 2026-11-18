package app.resound.ui.night

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.resound.AppGraph
import app.resound.capture.QuietRule
import app.resound.night.*
import app.resound.platform.FileRef
import app.resound.ui.theme.ResoundTheme
import app.resound.ui.theme.Rs
import app.resound.ui.theme.ThemeMode
import app.resound.ui.verify.RsCard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.ZoneId
import kotlin.math.sin
import kotlin.random.Random

/** 夜里界面的真实渲染（假数据），给 Claude 自查排版。SCREEN_SHOTS=目录 时才画 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class NightShots {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val zone = ZoneId.systemDefault()
    private val wakeDay = java.time.LocalDate.now()
    private fun at(dayOffset: Long, h: Int, m: Int) = wakeDay.plusDays(dayOffset).atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private val bed = at(-1, 23, 40); private val onset = at(0, 0, 5); private val wake = at(0, 7, 5)

    private val events = listOf(
        NightEvent(1, NightEventKind.Snore, at(0, 0, 50), at(0, 1, 30), 18, clipId = 11, audioAvailable = true),
        NightEvent(2, NightEventKind.Cough, at(0, 1, 5), at(0, 1, 5) + 6_000, 21, clipId = 12, asked = true, audioAvailable = true),
        NightEvent(3, NightEventKind.Snore, at(0, 2, 10), at(0, 2, 48), 25, clipId = 13, asked = true, audioAvailable = true),
        NightEvent(4, NightEventKind.SnoreBreak, at(0, 2, 15), at(0, 2, 15) + 16_000),
        NightEvent(5, NightEventKind.Awake, at(0, 3, 10), at(0, 3, 16), text = "看手机 6 分"),
        NightEvent(6, NightEventKind.Cough, at(0, 4, 20), at(0, 4, 20) + 5_000, 15, clipId = 14, feedback = Feedback.Yes, audioAvailable = true),
        NightEvent(7, NightEventKind.SleepTalk, at(0, 5, 12), at(0, 5, 12) + 9_000, segmentId = 99, text = "……明天别忘了带钥匙……", mine = true),
    )
    private val envelope = run {
        val n = ((wake - bed) / 5_000).toInt(); val r = Random(7)
        val bg = ByteArray(n) { (-62 + (sin(it / 400.0) * 2)).toInt().toByte() }
        val peak = ByteArray(n) { i ->
            val t = bed + i * 5_000L
            val snore = events.any { it.kind == NightEventKind.Snore && t in it.start..it.end }
            val loud = events.any { it.kind != NightEventKind.Snore && it.kind != NightEventKind.Awake && t in (it.start - 5_000)..(it.end + 5_000) }
            (bg[i] + 3 + r.nextInt(4) + (if (snore) 14 + r.nextInt(10) else 0) + (if (loud) 20 else 0)).coerceAtMost(-10).toByte()
        }
        NightEnvelope(bed, 5_000, peak, ByteArray(n) { (bg[it] + 2).toByte() }, bg)
    }
    private val tagList = listOf(NightTag(1, "侧睡", true), NightTag(2, "喝了酒", true), NightTag(3, "很累", true), NightTag(4, "感冒/鼻塞", true), NightTag(5, "开了加湿器", false))
    private val summary = NightSummary(1, kotlinx.datetime.LocalDate(wakeDay.year, wakeDay.monthValue, wakeDay.dayOfMonth), NightState.Ready,
        NightTimes(bed, onset, wake, onsetEstimated = true), sleepMillis = 6 * 3_600_000L + 40 * 60_000L, awakeCount = 2, awakeMillis = 15 * 60_000L,
        snoreMillis = 78 * 60_000L, coughCount = 2, talkCount = 1, confidence = NightConfidence.Fair, reasons = listOf("背景较吵"),
        sentence = "睡了约 6 小时 40 分，比平时少 40 分钟；2 点后鼾声比较多。", modes = listOf(NightModeSpan(bed, wake, NightMode.Full)), snoreBreaks = 3,
        tags = listOf(tagList[0]))
    private val detail = NightDetail(summary, events, envelope, questions = listOf(3, 2), suggestedTags = listOf(tagList[2]),
        dreams = listOf(Dream(1, 1, at(0, 7, 12), "梦见在一艘很大的船上找不到出口", emptyList(), false)),
        breakNotice = "最近几晚鼾声中断比较多，如果白天也常犯困，可以找医生做一次睡眠检查。")

    private var enabledFake = true
    private var hasNight = true
    private var eveningLive = false
    private fun install() {
        AppGraph.night = object : NightRepository {
            override val settings: StateFlow<NightSettings> = MutableStateFlow(NightSettings(enabled = enabledFake))
            override fun update(settings: NightSettings) { (this.settings as MutableStateFlow).value = settings }
            override val quietConflict: StateFlow<QuietRule?> = MutableStateFlow(QuietRule("sleep", "睡眠", 23 * 60, 7 * 60).takeIf { hasNight })
            override val live: StateFlow<NightLive> = MutableStateFlow(if (eveningLive) NightLive(NightState.Active, NightMode.Full, at(0, 23, 30)) else NightLive(NightState.Ready, null, null))
            override fun nights(from: kotlinx.datetime.LocalDate, to: kotlinx.datetime.LocalDate): Flow<List<NightSummary>> = flowOf(listOf(summary))
            override fun night(date: kotlinx.datetime.LocalDate): Flow<NightDetail?> {
                val d = java.time.LocalDate.of(date.year, date.monthNumber, date.dayOfMonth)
                if (d == wakeDay) return flowOf(detail.takeIf { hasNight })
                if (d == wakeDay.plusDays(1)) return flowOf(if (eveningLive) detail.copy(summary = summary.copy(id = 2, state = NightState.Ready, times = NightTimes(at(0, 23, 20), at(0, 23, 30), at(1, 7, 0), true), sentence = null),
                    events = emptyList(), questions = emptyList()) else null)
                val r = Random(d.toEpochDay()); val base = d.atStartOfDay(zone).toInstant().toEpochMilli()
                val evs = buildList {
                    repeat(r.nextInt(0, 3)) { k -> val s = base + r.nextLong(30, 300) * 60_000; add(NightEvent(100L + k, NightEventKind.Snore, s, s + r.nextLong(15, 50) * 60_000)) }
                    if (r.nextInt(3) == 0) { val s = base + r.nextLong(120, 280) * 60_000; add(NightEvent(200, NightEventKind.Awake, s, s + 12 * 60_000)) }
                }
                return flowOf(detail.copy(events = evs))
            }
            override suspend fun feedback(eventId: Long, value: Feedback) {}
            override suspend fun editTimes(nightId: Long, bedtime: Long?, onset: Long?, wake: Long?) {}
            override suspend fun deleteNight(nightId: Long) {}
            override suspend fun audio(clipId: Long): FileRef? = null
        }
        AppGraph.nightInsights = object : NightInsights {
            override val tags: Flow<List<NightTag>> = flowOf(tagList)
            override suspend fun createTag(name: String) = Result.success(NightTag(9, name, false))
            override suspend fun deleteTag(tagId: Long) {}
            override suspend fun setTag(nightId: Long, tagId: Long, on: Boolean) {}
            override suspend fun dismissSuggestion(nightId: Long, tagId: Long) {}
            override fun findings(): Flow<List<TagFinding>> = flowOf(listOf(TagFinding(tagList[0], CompareMetric.Snore, 5, 9, 30 * 60_000L, 68 * 60_000L,
                "标了“侧睡”的 5 晚，鼾声中位数比其他晚少 38 分钟（可能有关，样本还少）")))
            override fun weeklyReports(): Flow<List<WeeklyReport>> = flowOf(listOf(WeeklyReport(kotlinx.datetime.LocalDate(2026, 11, 9), 6, "## 上周睡眠\n平均睡眠 6 小时 52 分",
                "喝酒的两晚鼾声都翻倍了", "下周试试睡前不喝酒", false, 0)))
            override val personal: Flow<List<PersonalModelStatus>> = flowOf(listOf(PersonalModelStatus(PersonalKind.Snore, 12, 4, false, null, null, null)))
            override suspend fun startDream(nightId: Long) = Dream(2, nightId, 0, null, emptyList(), true)
            override suspend fun writeDream(nightId: Long, text: String) = Dream(3, nightId, 0, text, emptyList(), false)
            override suspend fun deleteDream(dreamId: Long) {}
            override suspend fun loudestClip(nightId: Long): Pair<FileRef, Boolean>? = null
            override suspend fun muteBreakNotice() {}
            override fun trend(from: kotlinx.datetime.LocalDate, to: kotlinx.datetime.LocalDate): Flow<List<NightPoint>> = flowOf((0 until 120).map { i ->
                val d = wakeDay.minusDays(119L - i); val r = Random(i)
                val on = d.minusDays(1).atTime(23, 0).plusMinutes(r.nextLong(0, 110)).atZone(zone).toInstant().toEpochMilli()
                val wk = d.atTime(6, 30).plusMinutes(r.nextLong(0, 80)).atZone(zone).toInstant().toEpochMilli()
                NightPoint(kotlinx.datetime.LocalDate(d.year, d.monthValue, d.dayOfMonth), on - 1_200_000, on, wk, wk - on - 600_000,
                    r.nextLong(0, 90) * 60_000, r.nextInt(3), r.nextInt(3), 0, if (i % 9 == 4) NightConfidence.Rough else NightConfidence.Good,
                    listOfNotNull("侧睡".takeIf { i % 3 == 0 }, "喝了酒".takeIf { i % 5 == 1 }))
            }.filter { it.date >= from && it.date <= to })
            override suspend fun doctorReport(from: kotlinx.datetime.LocalDate, to: kotlinx.datetime.LocalDate) = DoctorReport(from, to, "", emptyList())
        }
    }

    private fun capture(name: String, dark: Boolean = false, scroll: Boolean = true, content: @Composable () -> Unit) {
        val dir = File(System.getenv("SCREEN_SHOTS") ?: return).also { it.mkdirs() }
        install()
        compose.mainClock.autoAdvance = false
        compose.setContent { ResoundTheme(if (dark) ThemeMode.Dark else ThemeMode.Light) {
            Column(Modifier.fillMaxSize().background(Rs.colors.background).let { if (scroll) it.verticalScroll(rememberScrollState()).padding(16.dp) else it }.padding(top = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
        } }
        repeat(8) { compose.mainClock.advanceTimeBy(400); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
        val bitmap = compose.runOnUiThread {
            val root = compose.activity.window.decorView
            Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
        }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }


    /** 首页仿真：日期、录音卡、夜里卡、两件事 */
    @Composable private fun Home() {
        val c = Rs.colors
        val d = wakeDay
        val week = listOf("一", "二", "三", "四", "五", "六", "日")[d.dayOfWeek.value - 1]
        fun at(h: Int, m: Int) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
        val evs = listOf(
            app.resound.digest.MemoryEvent(1, at(9, 12), at(9, 15), "独白", "安排今天要做的事", "先改发布会演示稿，下午和小林过一遍。", emptyList(), listOf("小林"), null, "平静", 2, false, emptyList(), false),
            app.resound.digest.MemoryEvent(2, at(10, 30), at(11, 12), "会议", "发布会彩排安排", "确定周三下午彩排，预算按十二万报。", emptyList(), listOf("小林", "老王"), "公司", "平静", 2, false, emptyList(), true))
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                androidx.compose.material3.Text("今天 · 星期$week", style = app.resound.ui.theme.RsType.subhead, color = c.textSecondary)
                androidx.compose.material3.Text("${d.monthValue}月${d.dayOfMonth}日", style = app.resound.ui.theme.RsType.largeTitle, color = c.text)
            }
            app.resound.ui.verify.ThemeModePill(ThemeMode.Light) {}
        }
        app.resound.ui.today.OverviewCard(app.resound.ui.preview.FakeCaptureController(), app.resound.ui.preview.FakeMemoryRepository(),
            app.resound.ui.preview.FakeTranscriptRepository(), true, 2 * 3600_000L + 24 * 60_000L, 12, 0, null, {}, {}, screenMinutes = 194)
        val talk = mapOf(8 to 4, 9 to 6, 10 to 34, 11 to 12, 12 to 20, 13 to 4, 14 to 6, 15 to 14, 16 to 8, 18 to 10, 19 to 16, 20 to 9, 21 to 5, 22 to 3)
        val lines = talk.flatMap { (h, mins) -> (0 until mins).map { i -> app.resound.transcribe.TranscriptLine(h * 100L + i, at(h, i), at(h, i) + 50_000L, "…", null) } }
        val phone = listOf(0, 0, 0, 6, 0, 0, 0, 9, 12, 9, 6, 8, 28, 14, 10, 22, 9, 7, 6, 11, 13, 26, 18, if (eveningLive) 4 else 9)
        app.resound.ui.today.RhythmCard(evs, lines, d, 0, {}, {}, {}, screenByHour = phone, night = rememberNightRhythm(d))
        app.resound.ui.today.TimelineGroup("上午", evs.map { app.resound.ui.today.TimelineEntry.Event(it) }) {}
    }
    @Test fun homeBefore() { enabledFake = false; capture("home-before", scroll = false) { Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) { Home() } } }
    @Test fun homeMorning() = capture("home-morning", scroll = false) { Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) { Home() } }
    @Test fun homeAfter() { hasNight = false; capture("home-after", scroll = false) { Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) { Home() } } }
    @Test fun homeLate() { eveningLive = true; capture("home-late", scroll = false) { Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) { Home() } } }
    @Test fun homeDark() = capture("home-dark", dark = true, scroll = false) { Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) { Home() } }

    @Test fun detail() = capture("night-detail") { RsCard { NightBody(detail, sharedRoom = false) {} } }
    @Test fun trend() = capture("night-trend") { RsCard { TrendBody() } }
    @Test fun week() = capture("night-week") {
        WeekCard(WeeklyReport(kotlinx.datetime.LocalDate(wakeDay.minusDays(8).year, wakeDay.minusDays(8).monthValue, wakeDay.minusDays(8).dayOfMonth), 6,
            "## 上周睡眠\n平均睡眠 6 小时 52 分", "喝酒的两晚鼾声都翻倍了", "下周试试睡前不喝酒", false, 0))
        FindingCard(TagFinding(tagList[1], CompareMetric.Sleep, 4, 12, 6 * 3_600_000L, 7 * 3_600_000L + 20 * 60_000L, ""))
    }
    @Test fun trend7() = capture("night-trend-7") { RsCard { TrendBody(7) } }
    @Test fun trend90() = capture("night-trend-90") { RsCard { TrendBody(90) } }
    @Test fun trendDark() = capture("night-trend-dark", dark = true) { RsCard { TrendBody(7) } }
    /** 给医生的一页：30 晚假数据，两页各画一张 */
    @Test fun doctorPdf() {
        val dir = System.getenv("SCREEN_SHOTS") ?: return
        val r = Random(3); val m = 60_000L
        val nights = (0 until 30).filter { it != 11 && it != 23 }.map { i ->
            val d = wakeDay.minusDays((29 - i).toLong())
            fun t(off: Long, h: Int, min: Int) = d.plusDays(off).atTime(h, min).atZone(zone).toInstant().toEpochMilli()
            val bed = t(-1, 22 + r.nextInt(2), r.nextInt(60)); val on = bed + (10 + r.nextInt(40)) * m; val wk = t(0, 6 + r.nextInt(2), r.nextInt(60))
            val drink = i % 6 == 2
            val snore = (0 until (if (drink) 6 else r.nextInt(4))).map { k -> val s = on + (60 + k * 50 + r.nextInt(30)) * m; NightSpan(s, s + (8 + r.nextInt(if (drink) 25 else 10)) * m) }.filter { it.end < wk }
            val awake = if (r.nextInt(3) == 0) listOf(on + 200 * m, on + 208 * m).let { listOf(NightSpan(it[0], it[1])) } else emptyList()
            val sleep = wk - on - awake.sumOf { it.millis }
            val s = NightSummary(i.toLong(), kotlinx.datetime.LocalDate(d.year, d.monthValue, d.dayOfMonth), NightState.Ready, NightTimes(bed, on, wk, true), sleep, awake.size, awake.sumOf { it.millis },
                snore.sumOf { it.millis }, r.nextInt(3), r.nextInt(2), if (i == 5) NightConfidence.Rough else NightConfidence.Good, emptyList(), null, emptyList(), snoreBreaks = if (drink) 3 + r.nextInt(5) else r.nextInt(2))
            DoctorNight(s, snore, awake)
        }
        val k = { d: java.time.LocalDate -> kotlinx.datetime.LocalDate(d.year, d.monthValue, d.dayOfMonth) }
        val report = DoctorReport(k(wakeDay.minusDays(29)), k(wakeDay), "", emptyList(), nights,
            listOf(TagFinding(tagList[1], CompareMetric.Snore, 5, 20, 40 * m, 12 * m, "标了‘喝酒’的 5 晚，鼾声中位数比其他 20 晚多 28 分钟（可能有关，样本还少）")),
            listOf(DoctorClip(nights[2].snore.first().start, NightEventKind.Snore), DoctorClip(nights[8].summary.times!!.onset + 90 * m, NightEventKind.Cough)))
        for (page in 1..2) {
            val b = Bitmap.createBitmap(1190, 1684, Bitmap.Config.ARGB_8888)
            Canvas(b).apply { scale(2f, 2f); DoctorPdf.draw(this, report, page) }
            File(dir, "doctor-$page.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun settings() = capture("night-settings", scroll = false) { NightSettingsPage(0.dp) }
}
