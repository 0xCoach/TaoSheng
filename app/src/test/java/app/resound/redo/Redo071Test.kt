package app.resound.redo

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.*
import app.resound.transcribe.*
import app.resound.digest.*
import app.resound.importer.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class Redo071Test {
    private lateinit var db: CaptureDatabase
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var scope: CoroutineScope
    private val chooser = object : AsrChooser {
        override val options = MutableStateFlow(TranscribeSource.entries.map { AsrOption(it, it.title, true) })
        override val lastChoice = MutableStateFlow(TranscribeSource.Local)
        override fun remember(source: TranscribeSource) { lastChoice.value = source }
        override fun estimate(durationMs: Long, source: TranscribeSource) = AsrEstimate(durationMs / 2, 0.0)
    }
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        listOf("redo-jobs", "redo-choices", "imports", "transcribe", "digest").forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        root = File(context.cacheDir, "redo071-${System.nanoTime()}").apply { mkdirs() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
    @After fun finish() { scope.cancel(); db.close(); root.deleteRecursively() }
    private fun event(id: Long = 1, start: Long = 1000, end: Long = 3000, starred: Boolean = true) = MemoryEventEntity(id,
        start, end, "工作", "旧事件", "旧摘要", "[]", "[]", null, null, null, false, createdAt = 1, model = "old", starred = starred)
    private suspend fun seed(expired: Boolean = false, id: Long = 1) {
        val start = id * 1000
        db.memory().insertEvent(event(id, start, start + 2000))
        val file = File(root, "$id.wav"); if (!expired) file.writeText("audio")
        db.capture().segment(SegmentEntity(id, start, start + 2000, "mic", file.path, 5, "Done", completedAt = 1))
        db.transcribe().insertLines(listOf(TranscriptLineEntity(id, id, start, start + 2000, "旧原话", null, eventId = id, digestedAt = 1)))
    }
    private fun engine(onDigest: suspend () -> Unit = {}, onRecognize: (TranscribeSource) -> Unit = {}) = RedoEngine(db, chooser,
        recognize = { _, source -> onRecognize(source); AsrResult(listOf(AsrSentence(0, 2000, "新原话", null)), 2000, null) },
        digest = { lines, preset ->
            onDigest()
            val e = event(0, lines.minOf { it.startAt }, lines.maxOf { it.endAt }, false).copy(title = "新事件", model = preset ?: "default")
            RedoDigest(listOf(e to lines.map { it.id })) { ids -> listOf(MemoryItemEntity(eventId = ids[0], category = "待办", kind = null,
                content = "交报告", dueText = null, dueAt = null, who = null, lineId = lines.first().id, status = "Pending", createdAt = 10)) }
        }, now = { 100 })
    private suspend fun run(e: RedoEngine, id: String = "one", request: RedoRequest = RedoRequest(eventIds = listOf(1), scope = RedoScope.DigestOnly)) = e.run(id, request, {}, { _, _ -> })

    @Test fun digestReplacesAndInheritsStarAndPreservesCompletedTodo() = runBlocking {
        seed()
        db.memory().insertItems(listOf(MemoryItemEntity(id = 7, eventId = 1, category = "待办", kind = null, content = "交报告",
            dueText = null, dueAt = null, who = null, lineId = 1, status = "Done", createdAt = 1, doneAt = 9)))
        run(engine())
        val events = db.redoData().events(0, 10000)
        assertEquals(listOf("新事件"), events.map { it.title }); assertTrue(events.single().starred)
        val items = db.redoData().items(events.map { it.id })
        assertEquals(1, items.size); assertEquals(7, items.single().id); assertEquals("Done", items.single().status)
        assertEquals("旧原话", db.transcribe().exportSegments(listOf(1)).single().lines.single().text)
    }
    @Test fun retranscribeSkipsExpiredAndEstimates() = runBlocking {
        seed(); seed(expired = true, id = 2)
        val e = engine(); val request = RedoRequest(listOf(1, 2), scope = RedoScope.TranscribeAndDigest, asr = TranscribeSource.Local)
        val estimate = e.estimate(request)
        assertEquals(2, estimate.segments); assertEquals(1, estimate.expiredSegments); assertEquals(2000, estimate.audioMs)
        run(e, request = request)
        assertEquals(listOf("新原话", "旧原话"), db.transcribe().exportSegments(listOf(1, 2)).flatMap { it.lines }.map { it.text })
        val eventsBeforeRetry = db.redoData().events(0, 10000)
        val failed = RedoEngine(db, chooser, recognize = { _, _ -> throw AsrException(AsrError.Server) }, digest = { _, _ -> error("unexpected") }, now = { 100 })
        try { run(failed, "failed", RedoRequest(segmentIds = listOf(1), scope = RedoScope.TranscribeOnly, asr = TranscribeSource.Custom)); fail() }
        catch (_: AsrException) { }
        assertTrue(db.transcribe().segment(1).redoPending)
        val provider = object : AsrProvider {
            override suspend fun recognize(audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit): AsrResult {
                assertEquals("Custom", key)
                return AsrResult(listOf(AsrSentence(0, 2000, "重试后的原话", null)), 2000, null)
            }
        }
        TranscribeRunner(db.transcribe(), provider, { "default" }, { _, _ -> }, now = { 100000 },
            beforeTranscribe = { error("重做重试不能再跑环境音") }, sourceKey = { it.name }).run()
        assertFalse(db.transcribe().segment(1).redoPending)
        assertEquals(eventsBeforeRetry, db.redoData().events(0, 10000))
        assertTrue(db.memory().undigestedLines().isEmpty())

    }
    @Test fun transcribeOnlyAndUndoRestoreOriginalLinesWithoutChangingEvents() = runBlocking {
        seed(); val e = engine(); val old = db.memory().event(1)
        run(e, request = RedoRequest(segmentIds = listOf(1), scope = RedoScope.TranscribeOnly))
        assertEquals(old, db.memory().event(1)); assertEquals("新原话", db.transcribe().exportSegments(listOf(1)).single().lines.single().text)
        assertTrue(e.undo("one").isSuccess)
        assertEquals("旧原话", db.transcribe().exportSegments(listOf(1)).single().lines.single().text)
    }
    @Test fun undoRestoresEventsItemsAndSecondRunInvalidatesFirst() = runBlocking {
        seed(); val e = engine(); run(e)
        val newId = db.redoData().events(0, 10000).single().id
        run(e, "two", RedoRequest(listOf(newId), scope = RedoScope.DigestOnly))
        assertTrue(e.undo("one").isFailure); assertTrue(e.undo("two").isSuccess)
        assertEquals(newId, db.redoData().events(0, 10000).single().id)
        assertEquals(1, db.redoData().items(listOf(newId)).size)
    }
    @Test fun cancellationDuringDigestKeepsEventsAndNewTranscript() = runBlocking {
        seed(); val e = engine(onDigest = { throw CancellationException("cancel") })
        try { run(e, request = RedoRequest(listOf(1), scope = RedoScope.TranscribeAndDigest)); fail() } catch (_: CancellationException) { }
        assertEquals("旧事件", db.memory().event(1)!!.title)
        assertEquals("新原话", db.transcribe().exportSegments(listOf(1)).single().lines.single().text)
        assertTrue(e.undo("one").isSuccess)
    }
    @Test fun llmFailureKeepsEventsAndLaterEditsBlockUnsafeUndo() = runBlocking {
        seed(); val bad = engine(onDigest = { throw LlmException(LlmError.Format) })
        try { run(bad); fail() } catch (_: LlmException) { }
        assertNotNull(db.memory().event(1))
        val e = engine(); run(e, "two")
        val id = db.redoData().events(0, 10000).single().id
        db.memory().editEvent(id, "人工修改", "", "[]", 200)
        assertTrue(e.undo("two").isFailure); assertEquals("人工修改", db.memory().event(id)!!.title)
    }
    @Test fun explicitChoicesDoNotChangeSettingsAndReadyRejectsMissingConfiguration() = runBlocking {
        seed(); val settings = AndroidTranscribeSettings(context)
        val llm = AndroidDigestSettings(context, LlmClient())
        val before = settings.source.value; val model = llm.presetId.value
        var used: TranscribeSource? = null
        run(engine(onRecognize = { used = it }), request = RedoRequest(listOf(1), scope = RedoScope.TranscribeAndDigest, asr = TranscribeSource.Custom, llmPresetId = "custom"))
        assertEquals(TranscribeSource.Custom, used); assertEquals(before, settings.source.value); assertEquals(model, llm.presetId.value)
        val options = AndroidAsrChooser(context, settings, scope) { 1.0 }
        assertTrue(options.options.value.none { it.ready }); assertTrue(llmOptions(llm).none { it.ready })
        options.record(TranscribeSource.Local, 1000, 200)
        assertEquals(400L, options.estimate(2000, TranscribeSource.Local).etaMillis)
        assertEquals(0.0, options.estimate(2000, TranscribeSource.Local).costYuan!!, 0.0)
    }
    @Test fun importChoicePersistsAcrossRestartAndIsStoredOnlyOnItsSegments() = runBlocking {
        val pending = "00000000-0000-0000-0000-000000000071"
        context.getSharedPreferences("imports", 0).edit().putString("entries", """[{"id":"$pending","name":"test","duration":2000,"suggested":null,"start":null}]""").commit()
        val importer = AndroidAudioImporter(context, scope, enqueue = {})
        importer.start(pending, 1000, TranscribeSource.Custom)
        withTimeout(3000) { while (importer.next() == null) delay(10) }
        assertEquals(TranscribeSource.Custom, importer.next()!!.asr)
        val restored = AndroidAudioImporter(context, scope, enqueue = {})
        assertEquals(TranscribeSource.Custom, restored.next()!!.asr)
        db.capture().segment(SegmentEntity(1, 1, 2, "import", "a", 0, asrSource = restored.next()!!.asr!!.name))
        db.capture().segment(SegmentEntity(2, 1, 2, "mic", "b", 0))
        assertEquals("Custom", db.transcribe().segment(1).asrSource); assertNull(db.transcribe().segment(2).asrSource)
        val keys = mutableListOf<String>()
        listOf("a", "b").forEach { File(root, it).writeText("audio") }
        for (id in listOf(1L, 2L)) db.transcribe().update(db.transcribe().segment(id).let { it.copy(path = File(root, it.path).path) })
        val provider = object : AsrProvider {
            override suspend fun recognize(audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit): AsrResult {
                keys += key; return AsrResult(emptyList(), 1, null)
            }
        }
        TranscribeRunner(db.transcribe(), provider, { "global-key" }, { _, _ -> }, sourceKey = { it.name }).run()
        assertEquals(listOf("Custom", "global-key"), keys)

    }
    @Test fun queuedJobsAreSerialAndRestartUsesPersistedRequests() = runBlocking {
        seed()
        val service = AndroidRedoService(context, db, chooser, engine(), scope, { listOf(LlmOption("x", "x", true)) }, { "x" }, enqueue = {})
        val a = service.start(RedoRequest(segmentIds = listOf(1), scope = RedoScope.TranscribeOnly))
        val b = service.start(RedoRequest(segmentIds = listOf(1), scope = RedoScope.TranscribeOnly))
        val restored = AndroidRedoService(context, db, chooser, engine(), scope, { listOf(LlmOption("x", "x", true)) }, { "x" }, enqueue = {})
        assertEquals(listOf(a, b), restored.jobs.value.map { it.id })
        restored.drain { }
        assertTrue(restored.jobs.value.all { it.state is RedoJobState.Done })
        assertFalse((restored.job(a)!!.state as RedoJobState.Done).undoable)
        assertTrue(restored.undo(b).isSuccess)
    }
    @Test fun adjacentRangesMergeButGapsRemainAndExpiredEditedCountsAreHonest() = runBlocking {
        assertEquals(listOf(RedoRange(1, 6), RedoRange(8, 9)), mergedRanges(listOf(RedoRange(4, 6), RedoRange(1, 4), RedoRange(8, 9))))
        seed(expired = true); db.memory().editEvent(1, "改过", "", "[]", 5)
        val e = engine().estimate(RedoRequest(listOf(1), scope = RedoScope.TranscribeAndDigest))
        assertEquals(1, e.expiredEvents); assertEquals(1, e.editedEvents); assertEquals(0, e.audioMs)
    }
    @Test fun migration23PreservesDataAndAddsScopedOverrideAndSnapshot() = runBlocking {
        val name = "redo071-migration.db"; context.deleteDatabase(name)
        val schema = org.json.JSONObject(File("schemas/app.resound.data.CaptureDatabase/23.json").readText()).getJSONObject("database")
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
            old.execSQL("INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status) VALUES(1,1,2,'mic','old',0,'Done')")
            old.version = 23
        }
        val migrated = Room.databaseBuilder(context, CaptureDatabase::class.java, name).allowMainThreadQueries()
            .addMigrations(*CaptureDatabase.MIGRATIONS).build()
        try {
            assertEquals(CaptureDatabase.VERSION, migrated.openHelper.writableDatabase.version)
            assertEquals("old", migrated.transcribe().segment(1).path)
            assertNull(migrated.transcribe().segment(1).asrSource); assertFalse(migrated.transcribe().segment(1).redoPending)
            assertNull(migrated.redoData().snapshot())
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
    @Test fun skillReplacementProtectsManualAndUndoRestoresCards() = runBlocking {
        seed()
        fun card(id: Long, manual: Boolean) = app.resound.skills.SkillCardRow(id, "meeting", 1, "会议", "", 0,
            "entity$id", 1000, 3000, "旧卡$id", "", "[]", "{}", "[]", 1, manual)
        db.skills().save(card(1, false)); db.skills().save(card(2, true).copy(skillId = "manual"))
        val e = RedoEngine(db, chooser, recognize = { _, _ -> error("unexpected") },
            digest = { lines, _ -> RedoDigest(listOf(event(0).copy(title = "新事件") to lines.map { it.id })) { emptyList() } },
            regenerate = { old, _, _ -> old.map { it.copy(id = 0, title = "新卡") } }, now = { 100 })
        run(e)
        assertEquals(setOf("新卡", "旧卡2"), db.redoData().cards().map { it.title }.toSet())
        assertTrue(e.undo("one").isSuccess)
        assertEquals(setOf("旧卡1", "旧卡2"), db.redoData().cards().map { it.title }.toSet())
    }

}
