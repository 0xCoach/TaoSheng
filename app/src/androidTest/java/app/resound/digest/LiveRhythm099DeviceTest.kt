package app.resound.digest

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.resound.ResoundApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import kotlin.system.measureTimeMillis

/** 明确启用才读取手机统计投影；不读取原话正文、不执行删除或网络请求。 */
@RunWith(AndroidJUnit4::class)
class LiveRhythm099DeviceTest {
    @Test fun currentPhoneHistoryUsesOnlyLightweightStatistics() = runBlocking<Unit> {
        check(InstrumentationRegistry.getArguments().getString("allowLiveRead") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.resound")
        val db = (context.applicationContext as ResoundApp).database
        val zone = ZoneId.systemDefault()
        val stats = RoomRhythmStats(db, zone)
        for (offset in listOf(1L, 0L)) {
            val date = LocalDate.now(zone).minusDays(offset)
            val start = date.minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val elapsed = measureTimeMillis {
                stats.calculate(db.memory().rhythmSnapshot(start, end))
            }
            Log.i("LiveRhythm099", "days_ago=$offset snapshot_and_calculate_ms=$elapsed read_only=true")
            assertTrue("Historical statistics should finish within three seconds", elapsed < 3000)
        }
    }
}
