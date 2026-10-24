package app.resound.starred

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.ask.*
import app.resound.data.*
import app.resound.digest.*
import app.resound.push.*
import app.resound.review.*
import app.resound.search.*
import app.resound.skills.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Starred041Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.parse("2026-10-24")
    private val at = day.atStartOfDay(zone).toInstant().toEpochMilli()
    private val days = 86_400_000L
    private lateinit var db: CaptureDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var memory: RoomMemoryRepository
    private lateinit var oldZone: TimeZone

    @Before fun setup() {
        oldZone = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone(zone))
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries()
            .addCallback(SearchIndex.callback).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val llm = LlmClient()
        memory = RoomMemoryRepository(db.memory(), DigestRunner(context, db.memory(), llm,
            AndroidDigestSettings(context, llm), { "" }, { false }), { error("禁止云端请求") }, scope)
    }
    @After fun close() = runBlocking {
        scope.coroutineContext[Job]!!.cancelAndJoin(); db.close(); TimeZone.setDefault(oldZone)
    }
    private suspend fun event(id: Long, start: Long = at + id, star: Long? = null,
        important: Boolean = false, deleted: Boolean = false) = db.memory().insertEvent(
        MemoryEventEntity(id, start, start + 1, "独白", "事件$id", "方案进展", "[]", "[]", null, null, null,
            important, deleted, start, "test", star != null, star))
    private suspend fun item(id: Long, event: Long, category: String = "待办", status: String = "Pending", done: Long? = null) {
        db.memory().insertItems(listOf(MemoryItemEntity(id, event, category, null, "条目$id", null, null, null,
            null, status, at - 5 * days, doneAt = done)))
    }
    private fun vector(id: Long, value: FloatArray, model: String = "test") {
        EmbeddingStore(db).save(EmbeddingStore.Document("event", id, "事件$id\n方案进展\n[]"), model, value)
    }
    private val quiet = object : Notifier {
        override suspend fun send(title: String, body: String, deepLink: String?, topic: PushTopic?) {}
    }
    private fun reviews() = RoomReviewRepository(db.review(), db.memory(), context.getSharedPreferences("041-review", 0), scope,
        { error("禁止云端请求") }, { _, _, _, _ -> error("禁止云端请求") }, { "" }, "回响", quiet, {},
        { at + 22 * 3_600_000 }, { zone })

    @Test fun migrationThirteenToFourteenPreservesImportantAndClearsUserStars() = runBlocking {
        val name = "041-migration.db"; context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/app.resound.data.CaptureDatabase/13.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                fun sql(raw: String) = raw.replace("\u0024{TABLE_NAME}", e.getString("tableName"))
                old.execSQL(sql(e.getString("createSql")))
                val indices = e.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            for (id in 1..2) old.execSQL("""INSERT INTO memory_events
                (id,startAt,endAt,scene,title,summary,pointsJson,peopleJson,important,createdAt,model)
                VALUES(?,1,2,'独白','旧事件','摘要','[]','[]',?,1,'test')""", arrayOf(id, id - 1))
            old.version = 13
        }
        val migrated = CaptureDatabase.open(context, name)
        try {
            assertEquals(app.resound.data.CaptureDatabase.VERSION, migrated.openHelper.writableDatabase.version)
            val rows = migrated.memory().events(0, 10).first().sortedBy { it.id }
            assertEquals(listOf(false, true), rows.map { it.important })
            assertTrue(rows.all { !it.starred && it.starredAt == null })
        } finally { migrated.close(); context.deleteDatabase(name) }
    }

    @Test fun starAcceptsPendingTasksAtFrontAndUnstarPreservesItemsAndRepeats() = runBlocking {
        event(1, star = at, important = true); event(2); event(3, star = at + 30, deleted = true); event(4, important = true)
        item(11, 1, status = "Accepted"); db.memory().setSortOrder(11, 0)
        item(21, 2); item(22, 2, category = "备忘"); item(23, 2, category = "约定")
        item(24, 2, category = "灵感"); item(25, 4, category = "灵感"); db.memory().mergeInto(25, 24)
        db.memory().setStarred(2, true, at + 20)
        assertEquals(listOf(22L, 21L, 11L), db.memory().inProgress().first().map { it.id })
        val starred = memory.starredEvents().first()
        assertEquals(listOf(2L, 1L), starred.map { it.id })
        assertEquals(at + 20, starred.first().starredAt)
        assertFalse(starred.first().important)
        assertEquals(ItemStatus.Pending, starred.first().items.single { it.id == 23L }.status)
        assertEquals(2, starred.first().items.single { it.id == 24L }.repeats)
        assertEquals(setOf(1L, 4L), memory.importantEvents().first().map { it.id }.toSet())
        val before = db.memory().inProgress().first()
        db.memory().setStarred(2, false, at + 40)
        assertEquals(before, db.memory().inProgress().first())
        assertEquals(listOf(1L), memory.starredEvents().first().map { it.id })
        val unstarred = memory.events(day).first().single { it.id == 2L }
        assertFalse(unstarred.starred); assertNull(unstarred.starredAt)
        // 中途条目 UPDATE 失败时，星标和先接受的条目一起回滚。
        item(41, 4); item(42, 4, category = "备忘")
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_star BEFORE UPDATE OF status ON memory_items
            WHEN NEW.id = 42 BEGIN SELECT RAISE(ABORT, 'test rollback'); END""")
        assertTrue(runCatching { db.memory().setStarred(4, true, at) }.isFailure)
        assertFalse(memory.events(day).first().single { it.id == 4L }.starred)
        assertEquals("Pending", db.memory().itemsBetween(at, at + days).first().single { it.id == 41L }.status)
    }

    @Test fun askStarFilterAppliesToKeywordSemanticAndDayResults() = runBlocking {
        event(1, star = at + 20); event(2, star = at + 10); event(3, important = true); event(4, star = at + 30, deleted = true)
        for (id in 1L..4L) vector(id, floatArrayOf(1f, 0f))
        val tools = AskTools(db, JSONObject(File("src/main/assets/prompts/ask-sql.json").readText()), zone,
            { object : Embedder {
                override val model = "test"
                override fun ready() = true
                override fun embed(text: String, query: Boolean) = floatArrayOf(1f, 0f)
            } })
        val sources = mutableListOf<AskSource>()
        val result = tools.execute("search_events", JSONObject("""{"starred_only":true}"""), sources).getJSONArray("results")
        assertEquals(listOf(1L, 2L), (0 until result.length()).map { result.getJSONObject(it).getLong("id") })
        assertEquals(true, result.getJSONObject(0).get("starred")); assertEquals(at + 20, result.getJSONObject(0).getLong("starred_at"))
        val semantic = tools.execute("search_events", JSONObject("""{"keywords":["措辞不同"],"starred_only":true}"""), sources).getJSONArray("results")
        assertEquals(setOf(1L, 2L), (0 until semantic.length()).map { semantic.getJSONObject(it).getLong("id") }.toSet())
        val dayResults = tools.execute("get_day", JSONObject().put("date", "$day"), sources).getJSONArray("results")
        assertEquals(3, dayResults.length()); assertTrue((0 until dayResults.length()).all { dayResults.getJSONObject(it).get("starred") is Boolean })
        assertTrue(runCatching { tools.query("search_events", JSONObject("""{"starred_only":"true"}""")) }.isFailure)
    }

    @Test fun askEventStarWaitsForConfirmationThenUsesTransactionalStar() = runBlocking {
        event(1, important = true); item(11, 1)
        val changes = AskChanges(db, { at + 100 })
        val sources = listOf(AskSource("event", 1, at, "事件1", ""))
        val proposal = changes.propose(JSONObject("""{"operation":"event_star","id":1,"starred":true}"""), sources)
        assertTrue(memory.starredEvents().first().isEmpty())
        val conversation = db.ask().insertConversation(AskConversationEntity(createdAt = at, title = "标重点"))
        suspend fun message(p: ProposedChange) = db.ask().insertMessage(AskMessageEntity(conversationId = conversation,
            role = AskRole.Assistant.name, text = "", sourcesJson = "[]", at = at, error = null, actionJson = p.encode()))
        val id = message(proposal); changes.resolve(id, true)
        assertEquals(at + 100, memory.starredEvents().first().single().starredAt)
        assertEquals("Accepted", db.memory().inProgress().first().single().status)
        assertTrue(memory.starredEvents().first().single().important)
        assertEquals(AskActionState.Done, ProposedChange.decode(db.ask().message(id)!!.actionJson)!!.action.state)
        val cancel = changes.propose(JSONObject("""{"operation":"event_star","id":1,"starred":false}"""), sources)
        changes.resolve(message(cancel), false)
        assertTrue(memory.starredEvents().first().single().starred)
        assertTrue(runCatching { changes.propose(JSONObject("""{"operation":"event_important","id":1,"important":false}"""), sources) }.isFailure)
    }

    @Test fun staleRequiresThreeDaysUnfinishedAndNoDoneOrSimilarLaterEvent() = runBlocking {
        // 1 恰满三天无向量；2 未满；3 已有完成；4 有后续高相似事件；5 只有旧相似事件；6 无待办。
        for (id in 1L..6L) event(id, at - 6 * days, at - 3 * days + if (id == 2L) 1 else 0)
        for (id in 1L..5L) item(id, id, category = if (id == 5L) "约定" else "待办")
        item(30, 3, status = "Done", done = at - days)
        event(10, at - days); vector(4, floatArrayOf(1f, 0f)); vector(10, floatArrayOf(.81f, .58643f))
        event(11, at - 4 * days); vector(5, floatArrayOf(0f, 1f)); vector(11, floatArrayOf(0f, 1f))
        event(12, at - days, deleted = true); vector(12, floatArrayOf(0f, 1f))
        val stale = db.review().reviewInput(at - days, at).starredStale
        assertEquals(listOf(1L, 5L), stale.map { it.id })
        // 向量不同模型不互比；最多三件，优先最早标星。
        event(7, at - 6 * days, at - 3 * days); item(7, 7); vector(7, floatArrayOf(1f, 0f), "other")
        event(8, at - 6 * days, at - 3 * days); item(8, 8)
        assertEquals(listOf(1L, 5L, 7L), db.review().stale(at).map { it.id })
    }

    @Test fun reviewInputAndOutputKeepFourStarsAndHistoricalReminder() = runBlocking {
        for (id in 1L..5L) event(id, star = if (id <= 4) at + id else null)
        // 用户主动标星的媒体事件也必须进入回顾。
        db.openHelper.writableDatabase.execSQL("UPDATE memory_events SET scene='媒体' WHERE id=4")
        event(9, at - 6 * days, at - 4 * days); item(9, 9)
        val source = db.review().reviewInput(at, at + 1000)
        val input = JSONObject(ReviewPrompt.input(day, source, zone))
        assertTrue(input.getJSONArray("events").getJSONObject(0).getBoolean("starred"))
        assertEquals(9L, input.getJSONArray("starred_stale").getJSONObject(0).getLong("id"))
        val reply = """{"brief":"当天事件","sections":[{"title":"一句话","lines":[{"text":"概括","eventId":1}]},
            {"title":"发生了什么","lines":[{"text":"事件","eventId":1}]},
            {"title":"要紧的","lines":[{"text":"普通事件","eventId":5}]}]}"""
        val sections = ReviewPrompt.parse(reply, source, zone).second
        assertEquals(listOf(4L, 3L, 2L, 1L, 5L), sections.single { it.title == "要紧的" }.eventIds)
        val reminder = sections.single { it.title == "值得留意" }
        assertEquals(listOf(9L), reminder.eventIds); assertEquals("你 10 月 20 日标过重点的「事件9」，还没进展", reminder.lines.single())
    }

    @Test fun weeklyVersionTwoIncludesOldEventsStarredThisWeekAndItemProgress() = runBlocking {
        val skill = SkillJson.parse(File("src/main/assets/skills/weekly/skill.json").readText(), SkillOrigin.Builtin).skill
        assertEquals(2, skill.version)
        assertEquals(FieldType.Bullets, skill.fields.single { it.key == "starred" }.type)
        event(1, at - 20 * days, at - days); item(11, 1, status = "Done", done = at)
        event(2, at - 20 * days, at - 10 * days); event(3, at - 20 * days, at - days, deleted = true)
        val input = SkillInputs.load(db.skills(), skill, day, null, at + days).data
        assertTrue(input.contains("事件#1 / starred=true")); assertTrue(input.contains("Done / 条目11 / 完成：2026-10-24"))
        assertFalse(input.contains("事件#2")); assertFalse(input.contains("事件#3"))
    }

    @Test fun dailyMemoryCarriesBooleanAndTimelineStarWithoutMarkingImportantOnly() = runBlocking {
        event(1, star = at, important = false); event(2, important = true)
        db.openHelper.writableDatabase.execSQL("UPDATE memory_events SET scene='媒体' WHERE id=1")
        val message = DailyMemorySource(context, memory, reviews(), db.memory(), db.review(), db.skills()).load(day, false)!!
        val json = message.memory!!
        val events = json.getJSONObject("data").getJSONArray("events")
        assertEquals(true, events.getJSONObject(0).get("starred")); assertEquals(false, events.getJSONObject(1).get("starred"))
        val markdown = json.getString("markdown")
        assertTrue(markdown.contains("★ 事件1")); assertFalse(markdown.contains("★ 事件2"))
    }

    @Test fun emptyDayStillFollowsStaleStarsWithoutCallingCloud() = runBlocking {
        event(1, at - 7 * days, at - 4 * days); item(11, 1)
        val repo = reviews()
        assertFalse(repo.run(day, false))
        val review = repo.daily(day).first()!!
        assertEquals(listOf("值得留意"), review.sections.map { it.title })
        assertEquals(listOf(1L), review.sections.single().eventIds)
        assertTrue(db.memory().observeUsage().first().isEmpty())
    }
}
