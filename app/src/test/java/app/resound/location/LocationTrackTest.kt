package app.resound.location

import app.resound.data.testSql
import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.CaptureDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class LocationTrackTest {
    private val min = 60_000L
    private fun p(t: Long, lat: Double, lng: Double, acc: Float = 20f) = LocationPoint(at = t * min, lat = lat, lng = lng, accuracy = acc, provider = "fused")

    @Test fun staysSplitByMoveAndIgnoreShortStops() {
        val home = (0..6).map { p(it * 5L, 31.2304, 121.4737 + it * 0.00001) }        // 30 分钟原地
        val walk = listOf(p(35, 31.2350, 121.4800))                                        // 路上一个点，不算停留
        val office = (0..3).map { p(40 + it * 5L, 31.2400, 121.4900) }                    // 15 分钟
        val stays = Track.stays(home + walk + office)
        assertEquals(2, stays.size)
        assertEquals(30L, stays[0].minutes)
        assertEquals(15L, stays[1].minutes)
    }

    @Test fun inaccuratePointsDroppedAndJitterNotCountedAsDistance() {
        val track = Track.day(listOf(p(0, 31.2304, 121.4737), p(5, 31.2305, 121.4737), p(10, 31.30, 121.60, acc = 1500f), p(15, 31.2304, 121.4837)))
        assertEquals(3, track.points.size)
        assertTrue(track.meters in 900.0..1000.0)   // 只算约 950 米那一段，11 米抖动和飘点都不算
    }

    @Test fun duplicateFromSecondProviderSkipped() {
        val a = p(10, 31.2304, 121.4737, acc = 15f)
        assertFalse(LocationTracker.keep(a, a.copy(at = a.at + 20_000, accuracy = 40f)))
        assertTrue(LocationTracker.keep(a, a.copy(at = a.at + 20_000, accuracy = 8f)))
        assertTrue(LocationTracker.keep(a, a.copy(at = a.at + 5 * min)))
        assertFalse(LocationTracker.keep(null, a.copy(accuracy = 500f)))
    }

    @Test fun migratesFrom32AndStoresPointsAndPlaces() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val name = "location-migration.db"
        context.deleteDatabase(name)
        val schema = JSONObject(File("../shared/schemas/app.resound.data.CaptureDatabase/29.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                fun sql(value: String) = value.replace("\u0024{TABLE_NAME}", entity.getString("tableName"))
                old.execSQL(sql(entity.getString("createSql")))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
            }
            val queries = schema.getJSONArray("setupQueries")
            for (i in 0 until queries.length()) old.execSQL(queries.getString(i))
            old.version = 29
        }
        val db = Room.databaseBuilder(context, CaptureDatabase::class.java, name).setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).allowMainThreadQueries()
            .addMigrations(*CaptureDatabase.MIGRATIONS).build()
        try {
            assertEquals(CaptureDatabase.VERSION, db.testSql.version)
            db.location().insert(p(10, 31.23, 121.47))
            assertEquals(1, db.location().between(0, 20 * min).first().size)
            assertEquals(1, db.location().deleteBetween(0, 20 * min))
            db.places().put(PlaceRow("31.230,121.470", "人民广场", "人民大道200号", "高德", 1))
            assertEquals("人民广场", db.places().get("31.230,121.470")?.name)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}

class DayTimelineTest {
    private val min = 60_000L
    private fun p(m: Long, lat: Double, lng: Double) = LocationPoint(at = m * min, lat = lat, lng = lng, accuracy = 15f, provider = "fused")

    @org.junit.Test fun homeIsWhereTheNightIsAndGapsShowWhenNoRecords() {
        val home = 31.2165 to 121.4365; val work = 31.2304 to 121.4737
        val pts = (0L..480L step 5).map { p(it, home.first, home.second) } +          // 0:00–8:00 在家
            listOf(p(490, 31.222, 121.450), p(500, 31.226, 121.462)) +                    // 路上
            (510L..600L step 5).map { p(it, work.first, work.second) } +                   // 8:30–10:00 公司
            (660L..1200L step 5).map { p(it, home.first, home.second) }                    // 10:00–11:00 没记录；11:00–20:00 回家
        val track = Track.day(pts)
        val tl = Timeline.build(track.points, track.stays, 0, 24 * 60 * min)
        val homeGroup = tl.groups.single { it.home }
        assertEquals(home.first, homeGroup.lat, 0.001)
        assertEquals(2, homeGroup.stays.size)                                                // 早上和晚上合成同一个“家”
        assertEquals((480 + 540) * min, tl.homeMillis)
        assertTrue(tl.spans.any { it.kind == SpanKind.Move && it.start == 480 * min })
        assertTrue(tl.spans.any { it.kind == SpanKind.Gap && it.start == 600 * min && it.end == 660 * min })
        assertEquals(SpanKind.Gap, tl.spans.last().kind)                                     // 20 点以后没记录
        assertEquals(24 * 60 * min, tl.spans.last().end)
    }
}
