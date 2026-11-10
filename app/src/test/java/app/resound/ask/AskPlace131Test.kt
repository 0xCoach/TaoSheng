package app.resound.ask

import android.app.Application
import androidx.room.Room
import app.resound.data.CaptureDatabase
import app.resound.location.*
import app.resound.search.Embedder
import app.resound.search.EmbeddingStore
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class AskPlace131Test {
    private lateinit var db: CaptureDatabase
    private lateinit var location: LocationRepository
    private lateinit var places: CachedAskPlaces
    private lateinit var scope: CoroutineScope
    private val zone = ZoneId.systemDefault()
    private val sources = mutableListOf<AskSource>()
    private fun at(time: String) = AskTools.timestamp("2026-11-09 $time", false, zone)

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication() as Application
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
            .addCallback(app.resound.search.SearchIndex.callback).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        location = LocationRepository.create(context, db, scope)
        location.settings.setEnabled(true)
        location.settings.setLookup(false)
        places = CachedAskPlaces(location, db, zone)
        db.openHelper.writableDatabase.execSQL("INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status) VALUES(1,?,?, 'mic','/test',1,'Done')", arrayOf(at("00:00"), at("23:59")))
    }

    @After fun tearDown() { scope.cancel(); db.close() }

    private fun tools(embedder: Embedder? = null) = AskTools(db,
        JSONObject(File("src/main/assets/prompts/ask-sql.json").readText()), zone, { embedder })
    private fun wrapper(tools: AskTools = tools()) = AskPlaceTools(tools, places::enabled, places::staysAt, places::placesDuring, zone)
    private suspend fun find(name: String, args: String, tools: AskPlaceTools = wrapper()) = tools.execute(name, JSONObject(args), sources)
    private fun ids(result: JSONObject): List<Long> = result.getJSONArray("results").let { rows ->
        (0 until rows.length()).map { rows.getJSONObject(it).getLong("id") }
    }
    private suspend fun stay(index: Int, name: String, start: String, end: String, home: Boolean = false) {
        val lat = 30.0 + index * 0.01
        db.location().insert(LocationPoint(at = at(start), lat = lat, lng = 120.0, accuracy = 10f, provider = "test"))
        db.location().insert(LocationPoint(at = at(end), lat = lat, lng = 120.0, accuracy = 10f, provider = "test"))
        db.places().put(PlaceRow(Places.cell(lat, 120.0), name, "地址$index", "系统", 1))
        if (home) location.places.setManual(lat, 120.0, name, "", true)
    }
    private fun event(id: Int, start: String, end: String, title: String = "项目会议", starred: Int = 1, deleted: Int = 0) {
        db.openHelper.writableDatabase.execSQL("""INSERT INTO memory_events(id,startAt,endAt,scene,title,summary,pointsJson,peopleJson,important,deleted,createdAt,model,starred)
            VALUES(?,?,?,'会议',?,'项目安排','[]','[]',0,?,?,'fake',?)""", arrayOf(id, at(start), at(end), title, deleted, at(start), starred))
    }
    private fun line(id: Int, start: String, end: String, text: String = "明天提交项目方案", speaker: String = "我", event: Int? = null) {
        db.openHelper.writableDatabase.execSQL("""INSERT INTO transcript_lines(id,segmentId,startAt,endAt,text,speakerName,eventId)
            VALUES(?,1,?,?,?,?,?)""", arrayOf(id, at(start), at(end), text, speaker, event))
    }

    @Test fun eventsOverlapStaysAndKeepTimeKeywordStarAndDeletionFilters() = runBlocking {
        stay(1, "公司", "09:00", "10:00")
        stay(2, "小区", "11:00", "12:00")
        stay(1, "公司", "14:00", "15:00")
        event(1, "08:59", "09:01") // 开始在停留前，但实际重叠。
        event(2, "09:20", "09:25")
        event(3, "10:00", "10:05") // 半开区间右端，不算重叠。
        event(4, "08:30", "09:00") // 半开区间左端，不算重叠。
        event(5, "11:20", "11:25")
        event(6, "14:20", "14:25")
        event(7, "09:20", "09:25", deleted = 1)
        event(8, "09:20", "09:25", title = "买菜", starred = 0)
        assertEquals(setOf(1L, 2L, 6L, 8L), ids(find("search_events", """{"place":"公司"}""")).toSet())
        assertEquals(listOf(2L), ids(find("search_events", """{"place":"公司","keywords":["会议"],"starred_only":true,"from":"2026-11-09 09:15","to":"2026-11-09 09:30"}""")))
    }

    @Test fun linesKeepSpeakerKeywordsAndReferencesWithOverlap() = runBlocking {
        stay(1, "市医院", "09:00", "10:00")
        line(1, "08:59", "09:01", "医生说下周复查", "医生")
        line(2, "09:20", "09:21", "医生说下周复查", "医生")
        line(3, "10:00", "10:01", "医生说下周复查", "医生")
        line(4, "09:20", "09:21", "医生说下周复查", "我")
        line(5, "09:20", "09:21", "今天吃饭", "医生")
        event(9, "09:20", "09:21", deleted = 1)
        line(6, "09:20", "09:21", "医生说下周复查", "医生", 9)
        val result = find("search_lines", """{"place":"医院","keywords":["复查"],"speaker":"医生","from":"2026-11-09","to":"2026-11-09"}""")
        assertEquals(listOf(2L, 1L), ids(result))
        assertEquals(listOf(2L, 1L), sources.map { it.id })
        assertEquals(1, result.getJSONArray("results").getJSONObject(0).getInt("ref"))
        assertEquals("医生说下周复查", sources.first().quote)
        assertEquals(1, find("search_lines", """{"place":"医院","keywords":[],"speaker":"医生","to":"2026-11-09 09:00"}""")
            .getJSONArray("results").getJSONObject(0).getLong("id").toInt())
    }

    @Test fun homeUsesHomeFlagAndManualNameInsteadOfTextContainingHome() = runBlocking {
        stay(1, "幸福小区", "09:00", "10:00", home = true)
        stay(2, "家居公司", "11:00", "12:00")
        db.places().put(PlaceRow(Places.cell(30.01, 120.0), "旧的自动地名", "", "系统", 2))
        event(1, "09:20", "09:25")
        event(2, "11:20", "11:25")
        assertEquals(listOf(1L), ids(find("search_events", """{"place":"家"}""")))
        assertEquals(listOf("幸福小区"), places.staysAt("家", Long.MIN_VALUE, Long.MAX_VALUE).map { it.name })
        assertEquals(listOf(1L), ids(find("search_events", """{"place":"幸福"}""")))
    }

    @Test fun missingPlaceReturnsAtMostTenDistinctNamesInRequestedRange() = runBlocking {
        for (i in 0..11) stay(i, "地点$i", "%02d:00".format(i), "%02d:20".format(i))
        stay(12, "地点0", "12:00", "12:20")
        stay(13, "范围外", "20:00", "20:20")
        val result = find("search_events", """{"place":"火星","from":"2026-11-09 00:00","to":"2026-11-09 12:30"}""")
        assertTrue(result.getString("notice").contains("足迹里没有叫 火星 的地方"))
        assertEquals((0..9).map { "地点$it" }, result.getJSONArray("places").let { a -> (0 until a.length()).map { a.getString(it) } })
        assertTrue(ids(result).isEmpty())
        assertTrue(sources.isEmpty())
    }

    @Test fun disabledFootprintKeepsOriginalResultsAndOnlyExplainsExplicitPlace() = runBlocking {
        event(1, "09:20", "09:25")
        line(1, "09:20", "09:21")
        location.settings.setEnabled(false)
        for (name in listOf("search_events", "search_lines")) {
            val args = JSONObject("""{"keywords":["项目"]}""")
            val expectedSources = mutableListOf<AskSource>()
            val expected = tools().execute(name, args, expectedSources)
            sources.clear()
            val original = wrapper().execute(name, args, sources)
            assertEquals(expected.toString(), original.toString())
            assertEquals(expectedSources, sources)
            val result = wrapper().execute(name, JSONObject(args.toString()).put("place", "公司"), sources)
            assertTrue(result.getString("notice").contains("没有开启足迹"))
            assertFalse(result.has("error"))
            result.remove("notice")
            assertEquals(expected.toString(), result.toString())
        }
    }

    @Test fun omittedPlaceDoesNotReadFootprintOrChangeToolSchemaRequirements() = runBlocking {
        event(1, "09:20", "09:25")
        val original = tools()
        val tool = AskPlaceTools(original, { error("不应读足迹开关") }, { _, _, _ -> error("不应查足迹") }, { _, _ -> error("不应查地点") })
        assertEquals(listOf(1L), ids(find("search_events", "{}", tool)))
        val definitions = org.json.JSONArray(File("src/main/assets/prompts/ask-tools.json").readText())
        for (i in 0 until definitions.length()) {
            val f = definitions.getJSONObject(i).getJSONObject("function")
            if (f.getString("name") in setOf("search_events", "search_lines")) {
                val parameters = f.getJSONObject("parameters")
                assertEquals("string", parameters.getJSONObject("properties").getJSONObject("place").getString("type"))
                assertFalse(parameters.getJSONArray("required").toString().contains("place"))
                assertTrue(f.getString("description").contains("在某地"))
            }
        }
    }

    @Test fun semanticCandidatesAreFilteredBeforeRankingAndStayFilteredOnReadback() = runBlocking {
        stay(1, "公司", "09:00", "10:00")
        stay(2, "小区", "11:00", "12:00")
        for (i in 1..25) event(i, "11:20", "11:25") // 超过语义 top20 的异地候选。
        event(30, "09:20", "09:25")
        event(31, "09:20", "09:25", starred = 0)
        event(32, "09:50", "09:55")
        val store = EmbeddingStore(db)
        store.pending("test").forEach { store.save(it, "test", floatArrayOf(1f, 0f)) }
        val embedder = object : Embedder {
            override val model = "test"
            override fun ready() = true
            override fun embed(text: String, query: Boolean) = floatArrayOf(1f, 0f)
        }
        val result = find("search_events", """{"place":"公司","keywords":["汇报"],"starred_only":true,"from":"2026-11-09 09:15","to":"2026-11-09 09:30"}""", wrapper(tools(embedder)))
        assertEquals(listOf(30L), ids(result)) // 仅由语义命中，不能因关键词没命中而漏掉。
        assertEquals(listOf(30L), sources.map { it.id })
    }

    @Test fun narrowWindowUsesFullStayAndCachedNamesWithoutGeocoding() = runBlocking {
        stay(1, "ACME公司", "09:00", "10:00")
        stay(2, "", "11:00", "12:00")
        stay(3, "将被清掉", "14:00", "15:00")
        db.places().delete(Places.cell(30.03, 120.0))
        event(1, "09:20", "09:21")
        assertEquals(listOf(1L), ids(find("search_events", """{"place":"acme","from":"2026-11-09 09:20","to":"2026-11-09 09:20"}""")))
        assertEquals(listOf("ACME公司", "地址2", "某地"), places.placesDuring(at("00:00"), at("23:59")).map { it.name })
        assertNull(db.places().get(Places.cell(30.03, 120.0))) // 未触发反查或写缓存。
    }
}
