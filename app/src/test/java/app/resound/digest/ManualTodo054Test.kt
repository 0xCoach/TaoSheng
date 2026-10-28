package app.resound.digest

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.ask.*
import app.resound.backup.*
import app.resound.capture.*
import app.resound.data.*
import app.resound.push.*
import app.resound.review.*
import app.resound.search.SearchIndex
import app.resound.transcribe.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.time.*
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ManualTodo054Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.systemDefault()
    private val date = LocalDate.of(2026, 10, 28)
    private val at = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private lateinit var db: CaptureDatabase
    private lateinit var root: File
    private val dao get() = db.memory()
    private val preset = LlmPreset("test", "test", "https://example.invalid", "test")
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries()
            .addCallback(SearchIndex.callback).build()
        root = File(context.cacheDir, "manual054-${UUID.randomUUID()}").apply { mkdirs() }
    }
    @After fun cleanup() { db.close(); root.deleteRecursively() }
    private fun item(id: Long = 1, event: Long? = null) = MemoryItemEntity(id, event, "待办", "我要做", "给客户发方案", null, null,
        "客户", null, "Accepted", at, noMerge = true, origin = "typed", sourceText = "提醒我给客户发方案")
    private fun event() = MemoryEventEntity(1, at, at + 1000, "独白", "工作", "工作讨论", "[]", "[]", null, null, null, false, createdAt = at, model = "test")
    private fun service(scope: CoroutineScope, key: String = "test", timeout: Long = 20_000,
        usage: suspend (LlmReply) -> Unit = {}, chat: suspend (LlmPreset, String, String, String) -> LlmReply = { _, _, _, _ -> error("不应调用") }) =
        RoomManualTodos(dao, scope, { preset to key }, chat, usage, { date }, { at }, zone, timeout)

    @Test fun migration17PreservesRowsAndNullableForeignKeyAndSearchTriggers() = runBlocking {
        val name = File(root, "old.db").path
        val schema = JSONObject(File("schemas/app.resound.data.CaptureDatabase/17.json").readText()).getJSONObject("database")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(name, null).use { old ->
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { i ->
                val e = entities.getJSONObject(i)
                fun sql(s: String) = s.replace("\u0024{TABLE_NAME}", e.getString("tableName"))
                old.execSQL(sql(e.getString("createSql")))
                val indices = e.optJSONArray("indices") ?: JSONArray()
                repeat(indices.length()) { old.execSQL(sql(indices.getJSONObject(it).getString("createSql"))) }
            }
            val setup = schema.getJSONArray("setupQueries")
            repeat(setup.length()) { old.execSQL(setup.getString(it)) }
            old.execSQL("INSERT INTO memory_events(id,startAt,endAt,scene,title,summary,pointsJson,peopleJson,important,createdAt,model) VALUES(1,1,2,'独白','旧事件','摘要','[]','[]',0,1,'test')")
            old.execSQL("INSERT INTO memory_items(id,eventId,category,kind,content,status,createdAt,doneAt,statusChangedAt,sortOrder,mergedInto,noMerge,deleted,editedAt,doneHintLineId,doneHintAt,dismissedDoneLinesJson,reminderVersion) VALUES(1,1,'待办','我要做','旧内容','Accepted',1,2,3,-4,NULL,1,0,5,6,7,'[8]',9)")
            old.version = 17
        }
        val migrated = Room.databaseBuilder(context, CaptureDatabase::class.java, name).allowMainThreadQueries()
            .addMigrations(*CaptureDatabase.MIGRATIONS).build()
        try {
            assertEquals(CaptureDatabase.VERSION, migrated.openHelper.writableDatabase.version)
            val old = migrated.memory().itemIncludingDeleted(1)!!
            assertEquals("digest", old.origin); assertNull(old.sourceText)
            assertEquals(1L, old.eventId); assertEquals("旧内容", old.content)
            assertEquals(listOf(2L, 3L, -4L, 5L, 6L, 7L, 9L), listOf(old.doneAt, old.statusChangedAt, old.sortOrder, old.editedAt, old.doneHintLineId, old.doneHintAt, old.reminderVersion))
            assertEquals("[8]", old.dismissedDoneLinesJson); assertTrue(old.noMerge)
            migrated.memory().insertItems(listOf(item(2)))
            migrated.memory().updateItem(2, "修改方案", null, false, null)
            migrated.openHelper.writableDatabase.query("SELECT content FROM items_fts WHERE rowid=2").use { c ->
                assertTrue(c.moveToFirst()); assertTrue(c.getString(0).contains("修改"))
            }
            migrated.openHelper.writableDatabase.execSQL("DELETE FROM memory_events WHERE id=1")
            assertNull(migrated.memory().itemIncludingDeleted(1)); assertNotNull(migrated.memory().itemIncludingDeleted(2))
            migrated.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { migrated.close() }
    }

    @Test fun addAcceptedAtFrontPreservesOrderAndSourceAcrossLists() = runBlocking {
        dao.insertItems(listOf(item(10).copy(sortOrder = -5)))
        coroutineScope {
            service(this).add(listOf(TodoDraft("待办", "我要做", "第一件", null, null, null),
                TodoDraft("约定", null, "第二件", null, null, "小李")), ItemOrigin.Voice, "原始两件事")
        }
        val rows = dao.ongoing().first()
        assertEquals(listOf("第一件", "第二件", "给客户发方案"), rows.map { it.content })
        assertTrue(rows.take(2).all { it.eventId == null && it.status == "Accepted" && it.createdAt == at && it.noMerge && it.origin == "voice" && it.sourceText == "原始两件事" })
        assertEquals(3, dao.itemsByCategory(listOf("待办", "约定")).first().size)
        assertTrue(dao.pendingItems().first().isEmpty())
        dao.setItemStatus(rows.first().id, "Done", at)
        assertEquals(rows.first().id, dao.doneItems(at - 1).first().single().id)
    }

    @Test fun dayItemsAndCalendarUseStandaloneCreatedAt() = runBlocking {
        dao.insertItems(listOf(item()))
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(1L, dao.dayItems(start, end).first().single().id)
        assertEquals(listOf(at), dao.activeItemTimes(start, end).first())
        assertTrue(dao.dayItems(end, end + 86400000).first().isEmpty())
    }

    @Test fun reviewIncludesStandaloneWithSafeNullEventReferenceAndWeeklyInput() = runBlocking {
        dao.insertItems(listOf(item()))
        val source = db.review().input(at - 1000, at + 1000)
        assertTrue(source.events.isEmpty()); assertEquals(1L, source.items.single().id)
        assertEquals("给客户发方案", JSONObject(ReviewPrompt.input(date, source, zone)).getJSONArray("standalone_items").getJSONObject(0).getString("content"))
        val result = ReviewPrompt.parse("""{"brief":"记录了一个待办","sections":[{"title":"一句话","lines":[{"text":"记录了发方案的待办","eventId":null,"itemId":1}]},{"title":"发生了什么","lines":[{"text":"记录待办","eventId":null,"itemId":1}]},{"title":"待办","lines":[{"text":"给客户发方案","eventId":null,"itemId":1},{"text":"伪造","eventId":null,"itemId":999}]}]}""", source, zone)
        assertEquals(listOf("给客户发方案"), result.second.single { it.title == "待办" }.lines)
        assertEquals(listOf<Long?>(null), result.second.single { it.title == "待办" }.eventIds)
        assertEquals(1L, db.skills().standaloneItems(at - 1000, at + 1000).single().id)
        val weekly = app.resound.skills.SkillJson.parse(context.assets.open("skills/weekly/skill.json").bufferedReader().use { it.readText() }, app.resound.skills.SkillOrigin.Builtin).skill
        val input = app.resound.skills.SkillInputs.load(db.skills(), weekly, date.plusDays(1), null, at + 1000)
        assertTrue(input.data.contains("I1 / 待办 / Accepted / 谁：客户 / 给客户发方案"))
    }

    @Test fun dayVolumeExportRebuildRetainsStandaloneAndAllNewFields() = runBlocking {
        val source = CaptureDatabase.open(context, File(root, "source.db").path)
        val snapshot = File(root, "snapshot.db")
        try {
            source.memory().insertItems(listOf(item().copy(origin = "voice")))
            BackupArchive.snapshot(source, snapshot)
        } finally { source.close() }
        val (version, rows) = DayVolumeRows.write(snapshot, File(root, "rows"))
        assertTrue(rows.getValue(DayVolumeRows.day(at)).readText().contains("sourceText"))
        val target = File(root, "restored.db")
        DayVolumeRows.rebuild(context, target, version, rows.getValue("state"), rows.filterKeys { it != "state" }.values.toList())
        val restored = CaptureDatabase.open(context, target.path)
        try { assertEquals(item().copy(origin = "voice"), restored.memory().ongoing().first().single()) }
        finally { restored.close() }
    }

    @Test fun deletingEventKeepsStandaloneAndDoneHintCanBeWritten() = runBlocking {
        dao.insertEvent(event()); dao.insertItems(listOf(item(), item(2, 1).copy(noMerge = false)))
        dao.deleteEvent(1)
        assertEquals(1L, dao.ongoing().first().single().id)
        assertEquals(1L, dao.unfinishedForDigest().single().id)
        db.capture().segment(SegmentEntity(1, at + 1000, at + 3000, "mic", "synthetic.ogg", 100, "Done"))
        db.transcribe().insertLines(listOf(TranscriptLineEntity(1, 1, at + 1000, at + 2000, "方案已经发了", null, "我")))
        dao.saveDigest(emptyList(), { emptyList() }, listOf(1), at + 5000, listOf(1L to 1L), setOf(1))
        assertEquals(1L, dao.itemIncludingDeleted(1)!!.doneHintLineId)
    }

    @Test fun draftSplitsTwoCardsUsesCalendarAndAccountsUsage() = runBlocking {
        var calls = 0
        val s = service(this, usage = { calls++; assertEquals(7L, it.promptTokens) }) { p, key, system, user ->
            assertEquals(preset, p); assertEquals("test", key); assertTrue(system.contains("2026-10-28"))
            assertEquals("明天下午两点发方案，再约小李周五见", JSONObject(user).getString("sourceText"))
            LlmReply("""{"items":[{"category":"待办","kind":"我要做","content":"发方案","dueText":"2026-10-29 14:00","who":null},{"category":"约定","kind":null,"content":"见小李","dueText":"2026-10-30","who":"小李"}]}""", 7, 9)
        }
        val result = s.draft("明天下午两点发方案，再约小李周五见")
        assertTrue(result.parsed); assertEquals(2, result.items.size); assertEquals(1, calls)
        assertEquals(date.plusDays(1).atTime(14, 0).atZone(zone).toInstant().toEpochMilli(), result.items[0].dueAt)
        assertEquals(date.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli(), result.items[1].dueAt)
    }

    @Test fun malformedAndTimeoutFallBackToOriginalText() = runBlocking {
        val malformed = service(this) { _, _, _, _ -> LlmReply("乱码", 1, 1) }.draft("提醒我买菜")
        val timed = service(this, timeout = 20) { _, _, _, _ -> delay(2000); error("不应到达") }.draft("提醒我买菜")
        for (result in listOf(malformed, timed)) {
            assertFalse(result.parsed)
            assertEquals(listOf(TodoDraft("待办", "我要做", "提醒我买菜", null, null, null)), result.items)
        }
    }

    @Test fun noKeyDoesNotRequestModel() = runBlocking {
        assertFalse(service(this, key = "").draft("买牛奶").parsed)
    }

    private class Controller : CaptureController {
        override val status = MutableStateFlow(CaptureStatus(CaptureState.Listening, 0))
        var resumes = 0
        override fun start() {}
        override fun stop() { status.value = CaptureStatus(CaptureState.Stopped, 0) }
        override fun pause(minutes: Int) { status.value = CaptureStatus(CaptureState.Paused, 0) }
        override fun resume() { resumes++; status.value = CaptureStatus(CaptureState.Listening, 0) }
    }
    private class Microphone : TodoMicrophone {
        val enough = CompletableDeferred<Unit>()
        var count = 0
        var closed = false
        override fun open() {}
        override fun read(target: ShortArray, offset: Int, count: Int): Int {
            target.fill(2000, offset, offset + count)
            this.count += count
            if (this.count >= 16000) enough.complete(Unit)
            return count
        }
        override fun close() { closed = true }
    }

    @Test fun voiceFailureRestoresCaptureAndDeletesWavWithoutDiarization() = runBlocking {
        val controller = Controller(); val mic = Microphone()
        val provider = object : AsrProvider {
            override suspend fun recognize(audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit): AsrResult {
                assertFalse(audio.separateSpeakers); assertEquals("wav", audio.format)
                assertEquals("RIFF", audio.file.readBytes().take(4).toByteArray().toString(Charsets.US_ASCII))
                assertTrue(mic.closed); assertEquals(1, controller.resumes)
                throw AsrException(AsrError.Network)
            }
        }
        val voice = AndroidTodoVoiceInput(controller, this, root, provider, { "key" }, { mic }, 0)
        assertTrue(voice.start()); withTimeout(2000) { mic.enough.await() }
        assertEquals(VoiceNoteResult.Failed("网络不通，没能识别"), voice.finish())
        assertEquals(1, controller.resumes); assertEquals(0f, voice.level.value)
        assertTrue(root.listFiles()!!.isEmpty()); assertTrue(dao.ongoing().first().isEmpty())
    }

    @Test fun cancelDuringPreparationRestoresCaptureAndDoesNotRecognize() = runBlocking {
        val controller = Controller(); val mic = Microphone()
        val voice = AndroidTodoVoiceInput(controller, this, root, object : AsrProvider {
            override suspend fun recognize(audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit): AsrResult = error("不应识别")
        }, { "key" }, { mic }, 200)
        val starting = async { voice.start() }
        withTimeout(2000) { while (controller.status.value.state != CaptureState.Paused) delay(5) }
        voice.cancel(); assertFalse(starting.await())
        assertEquals(1, controller.resumes); assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun remindersPushFolderAndAskIncludeStandalone() = runBlocking {
        dao.insertItems(listOf(item().copy(dueAt = at + 60000, dueText = date.toString())))
        val snapshot = db.push().snapshot(Long.MIN_VALUE, false)
        val selected = snapshot.select(PushSubscription.ForAgents, null, date, zone)
        assertEquals(1L, selected.items.single().id)
        val reminders = SubscriptionReminders.candidates(snapshot, PushSubscription.PhoneNotice, JSONObject(), AppointmentReminders(), zone, at)
        assertTrue(reminders.isNotEmpty())
        assertTrue(MemoryFolder(date, zone).generate(selected, PushSubscription.ForAgents).values.any { it.contains("给客户发方案") && it.contains("手动添加") })
        val sql = JSONObject(context.assets.open("prompts/ask-sql.json").bufferedReader().use { it.readText() }).getJSONObject("list_items")
        db.openHelper.writableDatabase.query("SELECT ${sql.getString("select")} FROM ${sql.getString("table")} WHERE ${sql.getString("where")}").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(1L, c.getLong(0)); assertEquals(at, c.getLong(1)); assertEquals("提醒我给客户发方案", c.getString(c.getColumnIndexOrThrow("quote")))
        }
        assertTrue(AskContextReader(db).read(AskSource("item", 1, at, "待办", "")).text.contains("提醒我给客户发方案"))
    }
}
