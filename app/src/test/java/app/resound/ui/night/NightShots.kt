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

    private fun install() {
        AppGraph.night = object : NightRepository {
            override val settings: StateFlow<NightSettings> = MutableStateFlow(NightSettings(enabled = true))
            override fun update(settings: NightSettings) { (this.settings as MutableStateFlow).value = settings }
            override val quietConflict: StateFlow<QuietRule?> = MutableStateFlow(QuietRule("sleep", "睡眠", 23 * 60, 7 * 60))
            override val live: StateFlow<NightLive> = MutableStateFlow(NightLive(NightState.Ready, null, null))
            override fun nights(from: kotlinx.datetime.LocalDate, to: kotlinx.datetime.LocalDate): Flow<List<NightSummary>> = flowOf(listOf(summary))
            override fun night(date: kotlinx.datetime.LocalDate): Flow<NightDetail?> = flowOf(detail)
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
            override fun trend(from: kotlinx.datetime.LocalDate, to: kotlinx.datetime.LocalDate): Flow<List<NightPoint>> = flowOf((0 until 30).map { i ->
                val d = wakeDay.minusDays(29L - i); val r = Random(i)
                val on = d.minusDays(1).atTime(23, 0).plusMinutes(r.nextLong(0, 110)).atZone(zone).toInstant().toEpochMilli()
                val wk = d.atTime(6, 30).plusMinutes(r.nextLong(0, 80)).atZone(zone).toInstant().toEpochMilli()
                NightPoint(kotlinx.datetime.LocalDate(d.year, d.monthValue, d.dayOfMonth), on - 1_200_000, on, wk, wk - on - 600_000,
                    r.nextLong(0, 90) * 60_000, r.nextInt(3), r.nextInt(3), 0, if (i % 9 == 4) NightConfidence.Rough else NightConfidence.Good, emptyList())
            })
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

    @Test fun home() = capture("night-home") { NightHomeCard(wakeDay) }
    @Test fun homeDark() = capture("night-home-dark", dark = true) { NightHomeCard(wakeDay) }
    @Test fun detail() = capture("night-detail") { RsCard { NightBody(detail, sharedRoom = false) {} } }
    @Test fun trend() = capture("night-trend") { RsCard { TrendBody() } }
    @Test fun settings() = capture("night-settings", scroll = false) { NightSettingsPage(0.dp) }
}
