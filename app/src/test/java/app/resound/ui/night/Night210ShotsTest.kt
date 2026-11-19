package app.resound.ui.night

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.resound.night.*
import app.resound.ui.theme.*
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.File
import java.time.*

/** 卡 210：声音详情图（整夜/放大）与按类别折叠的列表截图，只供人工查看 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
class Night210ShotsTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun zoomAndGroupedList() {
        val bed = LocalDate.of(2026, 11, 18).atTime(23, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val wake = bed + 8 * 3_600_000L
        val n = ((wake - bed) / 5_000).toInt()
        val rnd = java.util.Random(7)
        val peaks = ByteArray(n); val medians = ByteArray(n); val bg = ByteArray(n)
        fun snoring(i: Int) = (i in 600..1500 || i in 2400..2900) && (i / 2) % 2 == 0
        for (i in 0 until n) {
            bg[i] = -58
            val loud = when { i == 3100 -> -12; snoring(i) -> -38 + rnd.nextInt(8); else -> -56 + rnd.nextInt(5) }
            peaks[i] = loud.toByte(); medians[i] = (loud - 4).toByte()
        }
        val env = NightEnvelope(bed, 5_000, peaks, medians, bg)
        fun at(i: Int) = bed + i * 5_000L
        val events = listOf(
            NightEvent(1, NightEventKind.Snore, at(600), at(1500), 22, clipId = 11, audioAvailable = true, feedback = Feedback.Yes),
            NightEvent(2, NightEventKind.Snore, at(2400), at(2900), 18, clipId = 12, audioAvailable = true),
            NightEvent(3, NightEventKind.Cough, at(2000), at(2002), 25, clipId = 13, audioAvailable = true),
            NightEvent(4, NightEventKind.SnoreBreak, at(900), at(903)),
            NightEvent(5, NightEventKind.SnoreBreak, at(1100), at(1104)),
            NightEvent(6, NightEventKind.SnoreBreak, at(1300), at(1302)),
            NightEvent(7, NightEventKind.Loud, at(3100), at(3100) + 2_000, 46, clipId = 14, audioAvailable = true),
            NightEvent(8, NightEventKind.SleepTalk, at(4000), at(4003), text = "明天几点开会", mine = true, clipId = null),
            NightEvent(9, NightEventKind.Awake, at(4600), at(4700), text = "看手机或拿起手机"),
        )
        val summary = NightSummary(1, kotlinx.datetime.LocalDate(2026, 11, 19), NightState.Ready, NightTimes(bed, bed + 20 * 60_000, wake, true),
            7 * 3_600_000L, 1, 500_000, 5_700_000, 1, 1, NightConfidence.Good, emptyList(), null, emptyList(), snoreBreaks = 3)
        val detail = NightDetail(summary, events, env, emptyList())
        val dir = File(System.getenv("NIGHT_SHOTS") ?: File(compose.activity.cacheDir, "night210").path).apply { mkdirs() }
        var theme by mutableStateOf(ThemeMode.Light)
        lateinit var zoom: NightZoomState
        var selected by mutableStateOf<Long?>(null)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ResoundTheme(theme) {
                zoom = rememberNightZoom(1, bed, wake)
                Column(Modifier.fillMaxSize().background(Rs.colors.elevated).verticalScroll(rememberScrollState()).padding(20.dp)) {
                    Spacer(Modifier.height(24.dp))
                    NightSoundDetail(1, zoom, env, events, selected, onPlay = { selected = it.id })
                    Spacer(Modifier.height(6.dp))
                    MapLegend(events, false)
                    Spacer(Modifier.height(18.dp))
                    Events(detail, false, selected, { selected = it.id }) { e -> selected = e.id; zoom.focus(e) }
                }
            }
        }
        fun settle() = repeat(6) { compose.mainClock.advanceTimeBy(300); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
        fun shot(name: String) {
            settle()
            val bitmap = compose.runOnUiThread {
                val view = compose.activity.window.decorView
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(android.graphics.Canvas(it)) }
            }
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        listOf(ThemeMode.Light, ThemeMode.Dark).forEach { mode ->
            theme = mode
            compose.runOnUiThread { zoom.whole() }
            shot("${mode.name}-1整夜")
            compose.onNodeWithText("鼾声中断", substring = true).performClick()
            shot("${mode.name}-2展开中断")
            compose.onNodeWithText("最响 ", substring = true).performClick()
            shot("${mode.name}-3最响放大")
            compose.onNodeWithText("鼾声中断", substring = true).performClick()
        }
    }
}
