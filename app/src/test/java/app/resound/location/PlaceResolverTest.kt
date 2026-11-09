package app.resound.location

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.CaptureDatabase
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class PlaceResolverTest {
    private val amapOk = """{"status":"1","info":"OK","regeocode":{"formatted_address":"上海市黄浦区南京东路街道人民大道200号",
        "addressComponent":{"township":"南京东路街道","streetNumber":{"street":"人民大道","number":"200号"}},
        "aois":[{"name":"人民广场"}],"pois":[{"name":"上海市人民政府"}]}}"""

    @Test fun parsesAmapPreferringAreaName() {
        val p = Places.parseAmap(amapOk)!!
        assertEquals("人民广场", p.name)
        assertEquals("上海市黄浦区南京东路街道人民大道200号", p.address)
        // 没有区域、没有地点时退到街道门牌；高德空数组字段会给 [] 而不是字符串
        val bare = Places.parseAmap("""{"status":"1","regeocode":{"formatted_address":[],"addressComponent":{"streetNumber":{"street":"中山路","number":"8号"}},"aois":[],"pois":[]}}""")!!
        assertEquals("中山路8号", bare.name)
        assertThrows(IllegalStateException::class.java) { Places.parseAmap("""{"status":"0","info":"INVALID_USER_KEY"}""") }
    }

    @Test fun wgsToGcjShiftsInChinaOnly() {
        val (lat, lng) = Places.wgsToGcj(31.2304, 121.4737)
        assertEquals(31.2284, lat, 0.0005)      // 上海偏移约 -0.002 / +0.0045
        assertEquals(121.4782, lng, 0.0005)
        assertEquals(48.8566 to 2.3522, Places.wgsToGcj(48.8566, 2.3522))
    }

    @Test fun secondLookupOfSamePlaceUsesCache() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        val server = MockWebServer().apply { enqueue(MockResponse().setBody(amapOk)); start() }
        try {
            val settings = LocationSettings(context)
            val resolver = PlaceResolver(context, db.places(), settings, amapBase = server.url("/v3/geocode/regeo").toString(), amapKey = { "test-key" })
            assertEquals("人民广场", resolver.resolve(31.2304, 121.4737)?.name)
            val req = server.takeRequest()
            assertEquals("test-key", req.requestUrl?.queryParameter("key"))
            assertTrue(req.requestUrl!!.queryParameter("location")!!.startsWith("121.478"))   // 已换成高德坐标，经度在前
            assertEquals("人民广场", resolver.resolve(31.23045, 121.47372)?.name)            // 同一格
            assertEquals(1, server.requestCount)
        } finally { server.shutdown(); db.close() }
    }

    @Test fun manualNameWinsNearbyAndSurvivesCacheClear() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        try {
            val resolver = PlaceResolver(context, db.places(), LocationSettings(context), amapKey = { "" })
            db.places().put(PlaceRow(Places.cell(30.2409, 119.9199), "自动名", "自动地址", "系统", 1))
            resolver.setManual(30.24085, 119.91991, "家", "", home = true)
            assertEquals("家", resolver.cached(30.2409, 119.9199)?.name)          // 约 10 米外也用手动名
            assertTrue(resolver.cached(30.2409, 119.9199)!!.home)
            assertNull(Places.nearestManual(db.places().manualNow(), 30.2500, 119.9199))   // 1 公里外不受影响
            resolver.clearCache()
            assertEquals(1, db.places().manualNow().size)
            val stays = listOf(Stay(13 * 3_600_000L, 15 * 3_600_000L, 30.2409, 119.9199, 20, 10f),     // 白天在家
                Stay(0, 6 * 3_600_000L, 30.2600, 119.9500, 70, 10f))                                     // 夜里在别处
            val manual = db.places().manualNow()
            val groups = Timeline.groups(stays, 0) { la, ln -> Places.nearestManual(manual, la, ln)?.home == true }
            assertEquals(30.2409, groups.single { it.home }.lat, 0.0001)
        } finally { db.close() }
    }
}
