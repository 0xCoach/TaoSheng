package app.resound.digest

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import app.resound.data.*
import app.resound.privacy.RoomPrivacyActions
import app.resound.search.*
import app.resound.skills.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
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
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class IdeasMood040Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: CaptureDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var memory: RoomMemoryRepository
    private lateinit var originalZone: TimeZone
    private val day = LocalDate.parse("2026-10-24")
    private fun at(date: LocalDate = day) = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before fun setup() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries()
            .addCallback(SearchIndex.callback).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val llm = LlmClient()
        val runner = DigestRunner(context, db.memory(), llm, AndroidDigestSettings(context, llm), { "" }, { false })
        memory = RoomMemoryRepository(db.memory(), runner, { error("禁止请求云端") }, scope)
    }
    @After fun close() = runBlocking {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        db.close()
        TimeZone.setDefault(originalZone)
    }
    private suspend fun event(id: Long, start: Long = at() + id * 1000, duration: Long = 60_000,
        scene: String = "独白", emotion: String? = null, intensity: Int? = null,
        important: Boolean = false, deleted: Boolean = false) {
        db.memory().insertEvent(MemoryEventEntity(id, start, start + duration, scene, "事件$id", "摘要", "[]", "[]",
            null, emotion, intensity, important, deleted, start, "test"))
    }
    private suspend fun idea(id: Long, created: Long = at() + id * 1000, category: String = "灵感") {
        event(id, created)
        db.memory().insertItems(listOf(MemoryItemEntity(id, id, category, null, "想法$id", null, null, null,
            id * 10, "Pending", created)))
    }
    private fun vector(id: Long, cosine: Float = 1f, model: String = "test") {
        EmbeddingStore(db).save(EmbeddingStore.Document("item", id, "灵感\n想法$id"), model,
            floatArrayOf(cosine, sqrt(1f - cosine * cosine)))
    }
    private suspend fun merge() = IdeaMerger(db).merge("test")
    private suspend fun roots() = memory.items(setOf("灵感"), null).first()

    @Test fun highSimilarityChoosesClosestEarlierRootAndKeepsOriginal() = runBlocking {
        idea(9, at() + 1000); idea(2, at() + 2000); idea(3, at() + 3000)
        vector(9); vector(2, .8f); vector(3, .86f)
        merge()
        assertEquals(listOf(2L, 9L), roots().map { it.id })
        // 第三条与第二条更接近，不能无条件并进最老的第一条。
        val child = memory.mergedItems(2).first().single()
        assertEquals(3L, child.id); assertEquals(30L, child.sourceLineId)
        assertEquals("想法2", roots().first().content); assertEquals(2, roots().first().repeats)
    }

    @Test fun similarityPointEightAndOtherCategoriesStayIndependent() = runBlocking {
        idea(1); idea(2); idea(3, category = "关键信息")
        vector(1); vector(2, .8f)
        merge()
        assertEquals(2, roots().size)
        assertEquals(1, memory.items(setOf("关键信息"), null).first().size)
    }

    @Test fun absentOrOldModelVectorsWaitAndLateEarlierVectorReparentsGroup() = runBlocking {
        idea(1); idea(2); idea(3)
        vector(1, model = "old")
        merge(); assertEquals(3, roots().size)
        vector(2); vector(3); merge(); assertEquals(2, roots().size)
        vector(1); merge()
        assertEquals(1L, roots().single().id); assertEquals(3, roots().single().repeats)
        assertEquals(listOf(2L, 3L), memory.mergedItems(1).first().map { it.id })
    }

    @Test fun userUnmergePersistsAndDoesNotBecomeAutomaticTarget() = runBlocking {
        idea(1); idea(2); vector(1); vector(2); merge()
        memory.unmerge(2)
        withTimeout(5000) { memory.mergedItems(1).first { it.isEmpty() } }
        idea(3); vector(3); merge()
        assertEquals(setOf(1L, 2L), roots().map { it.id }.toSet())
        assertEquals(1, roots().first { it.id == 2L }.repeats)
        assertEquals(listOf(3L), memory.mergedItems(1).first().map { it.id })
    }

    @Test fun deletingMainEventPromotesEarliestSurvivor() = runBlocking {
        for (id in 1L..3L) { idea(id); vector(id) }
        merge(); db.memory().deleteEvent(1)
        assertEquals(2L, roots().single().id); assertEquals(2, roots().single().repeats)
        assertEquals(2L, memory.mergedItems(2).first().single().mergedInto)
    }

    @Test fun privacyDeletionPromotesSurvivingIdeaWithoutDanglingParent() = runBlocking {
        for (id in 1L..3L) { idea(id); vector(id) }
        merge()
        val result = RoomPrivacyActions(db).forgetEvent(1)
        assertEquals(1, result.items)
        assertEquals(2L, roots().single().id); assertEquals(2, roots().single().repeats)
        assertEquals(listOf(3L), memory.mergedItems(2).first().map { it.id })
    }

    @Test fun listsOnlyExposeRootsWithCrossDayRepeatsAndChildSources() = runBlocking {
        idea(1, at(day.minusDays(1))); idea(2); idea(3)
        for (id in 1L..3L) vector(id)
        merge()
        assertTrue(memory.dayItems(day).first().notes.isEmpty())
        assertEquals(3, memory.dayItems(day.minusDays(1)).first().notes.single().repeats)
        assertTrue(memory.events(day).first().all { it.items.isEmpty() })
        assertEquals(3, memory.events(day.minusDays(1)).first().single().items.single().repeats)
        assertEquals(listOf(20L, 30L), memory.mergedItems(1).first().map { it.sourceLineId })
        assertEquals(listOf(at() + 2000, at() + 3000), memory.mergedItems(1).first().map { it.at })
    }

    @Test fun dayMoodsWeightRoundExcludeAndReactWithEmptyDates() = runBlocking {
        event(1, duration = 60_001, emotion = "愉快", intensity = 3)
        event(2, duration = 0, emotion = "低落", intensity = 1)
        event(3, scene = "媒体", emotion = "低落")
        event(4, scene = "旁人", emotion = "低落")
        event(5, emotion = "低落", deleted = true)
        event(6, emotion = "")
        val updates = Channel<List<DayMood>>(Channel.UNLIMITED)
        val job = launch { memory.dayMoods(day, day.plusDays(1)).collect { updates.send(it) } }
        val first = withTimeout(5000) { updates.receive() }
        assertEquals(mapOf("愉快" to 2, "低落" to 1), first[0].minutesByEmotion)
        assertEquals("愉快", first[0].mainEmotion); assertEquals(86, first[0].energy)
        assertEquals(2, first[0].events)
        assertNull(first[1].energy); assertTrue(first[1].minutesByEmotion.isEmpty())
        assertEquals(50, DayMoodCalculator.energy(0.0, 2.0)); assertNull(DayMoodCalculator.energy(0.0, 0.0))
        db.memory().deleteEvent(1)
        val next = withTimeout(5000) { var x = updates.receive(); while (x[0].energy != 0) x = updates.receive(); x }
        assertEquals("低落", next[0].mainEmotion)
        job.cancelAndJoin()
    }

    @Test fun importantEventsAreCrossDayNewestFirstWithItemsAndLimit() = runBlocking {
        idea(1, at(day.minusDays(1))); idea(2)
        db.memory().setImportant(1, true); db.memory().setImportant(2, true)
        event(3); event(4, important = true, deleted = true)
        var events = memory.importantEvents().first()
        assertEquals(listOf(2L, 1L), events.map { it.id })
        assertEquals(listOf(2L, 1L), events.map { it.items.single().id })
        for (id in 5L..505L) event(id, important = true)
        events = memory.importantEvents().first()
        assertEquals(500, events.size); assertEquals(505L, events.first().id)
        assertFalse(events.any { it.id == 1L || it.id == 3L || it.id == 4L })
    }

    @Test fun migrationTwelveToThirteenPreservesRowsAndRoomValidatesSchema() = runBlocking {
        val name = "migration-040.db"; context.deleteDatabase(name)
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = JSONObject(File("schemas/app.resound.data.CaptureDatabase/12.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                fun sql(raw: String) = raw.replace("\u0024{TABLE_NAME}", entity.getString("tableName"))
                old.execSQL(sql(entity.getString("createSql")))
                val indices = entity.optJSONArray("indices")
                for (j in 0 until (indices?.length() ?: 0)) old.execSQL(sql(indices!!.getJSONObject(j).getString("createSql")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO memory_events(id,startAt,endAt,scene,title,summary,pointsJson,peopleJson,important,createdAt,model) VALUES(1,1,2,'独白','旧事件','摘要','[]','[]',1,1,'test')")
            old.execSQL("INSERT INTO memory_items(id,eventId,category,content,status,createdAt) VALUES(1,1,'灵感','旧灵感','Pending',1)")
            old.version = 12
        }
        val migrated = CaptureDatabase.open(context, name)
        try {
            assertEquals(13, migrated.openHelper.writableDatabase.version)
            val item = migrated.memory().itemsByCategory(listOf("灵感")).first().single()
            assertEquals("旧灵感", item.content); assertNull(item.mergedInto); assertFalse(item.noMerge)
        } finally { migrated.close() }
        context.deleteDatabase(name)
        Unit
    }

    @Test fun moodDiaryAssetSchedulesAndUsesOnlyOwnEvidenceWithThreeQuoteCap() = runBlocking {
        val skill = SkillJson.parse(File("src/main/assets/skills/mood_diary/skill.json").readText(), SkillOrigin.Builtin).skill
        assertEquals("heart", skill.icon); assertFalse(skill.push); assertEquals(1, skill.maxCardsPerDay)
        assertEquals(SkillTrigger.Daily(1335, emptySet()), skill.trigger)
        assertEquals(SkillScope(SkillRange.Day, SkillSpeakers.MeOnly, true), skill.scope)
        val lines = (1L..5L).map { TranscriptLineEntity(id = it, segmentId = 1, startAt = at(), endAt = at() + 1000,
            text = "今天好开心", speakerTag = null, speakerName = if (it == 5L) "别人" else "我") }
        val own = SkillInputs.boundedLines(lines, skill.scope.speakers)
        val input = SkillInput("", own.associateBy { "L${it.id}" })
        val output = SkillOutput.parse("""{"cards":[{"title":"心情","values":{"one_line":"今天很开心","quotes":["L1","L2","L3","L4","L5"]},"evidence":["L1","L5"]}]}""",
            skill, day, input, at() + 23 * 3600_000L, false).rows.single().card()
        assertEquals(3, output.values.getValue("quotes").quotes.size)
        assertEquals(listOf(1L, 2L, 3L), output.values.getValue("quotes").quotes.map { it.lineId })
        assertFalse(output.evidenceLineIds.contains(5L))
        val prompt = File("src/main/assets/skills/mood_diary/prompt.md").readText()
        assertTrue(prompt.contains("今天没有明显的情绪表达")); assertTrue(prompt.contains("不诊断"))
        assertTrue(SkillOutput.parse("""{"cards":[],"nothing_reason":"今天没有明显的情绪表达"}""",
            skill, day, input, at(), false).rows.isEmpty())
    }
}
