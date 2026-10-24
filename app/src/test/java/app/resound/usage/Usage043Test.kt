package app.resound.usage

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.AppGraph
import app.resound.ask.createAskRepository
import app.resound.backup.BackupSettings
import app.resound.data.*
import app.resound.digest.*
import app.resound.push.*
import app.resound.review.RoomReviewRepository
import app.resound.skills.*
import app.resound.transcribe.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Usage043Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val day = LocalDate.of(2026, 10, 24)
    private val zone = ZoneId.of("Asia/Shanghai")
    private val at = day.atStartOfDay(zone).toInstant().toEpochMilli()
    private lateinit var db: CaptureDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var root: File
    private fun repo() = RoomUsageRepository(db.memory(), db.transcribe(), context.getSharedPreferences("usage", 0))
    private val notifier = object : Notifier {
        override suspend fun send(title: String, body: String, deepLink: String?, topic: PushTopic?) = Unit
    }
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        root = File(context.cacheDir, "usage043").apply { deleteRecursively(); mkdirs() }
        (BackupSettings.prefs + BackupSettings.stores.keys + setOf("ask-suggestions", "digest-state"))
            .forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
    }
    @After fun cleanup() = runBlocking {
        scope.coroutineContext[Job]!!.cancelAndJoin(); db.close(); root.deleteRecursively(); Unit
    }

    @Test fun migrates14To15PreservingTotalsAsOther() = runBlocking {
        val name = "usage-migration.db"
        context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/app.resound.data.CaptureDatabase/14.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, 0, null).use { old ->
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { i ->
                val entity = entities.getJSONObject(i)
                fun sql(s: String) = s.replace("\u0024{TABLE_NAME}", entity.getString("tableName"))
                old.execSQL(sql(entity.getString("createSql")))
                entity.optJSONArray("indices")?.let { indices -> repeat(indices.length()) { old.execSQL(sql(indices.getJSONObject(it).getString("createSql"))) } }
            }
            val setup = schema.getJSONArray("setupQueries")
            repeat(setup.length()) { old.execSQL(setup.getString(it)) }
            old.execSQL("INSERT INTO llm_usage VALUES ('2026-10-23',12,3,2),('2026-10-24',20,5,1)")
            old.version = 14
        }
        val migrated = CaptureDatabase.open(context, name)
        try {
            assertEquals(CaptureDatabase.VERSION, migrated.openHelper.writableDatabase.version)
            val rows = migrated.memory().usageByPurpose("2026-10-23", "2026-10-24").first()
            assertEquals(listOf("Other", "Other"), rows.map { it.purpose })
            assertEquals(32L, rows.sumOf { it.promptTokens }); assertEquals(8L, rows.sumOf { it.completionTokens })
            assertEquals(3, rows.sumOf { it.calls })
            migrated.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE name='llm_usage'").use { assertFalse(it.moveToFirst()) }
        } finally { migrated.close(); context.deleteDatabase(name) }
    }

    @Test fun llmGroupsPurposesIncludesBothBoundsAndEmitsUpdates() = runBlocking {
        val memory = db.memory(); val repo = repo()
        memory.addUsage(day.minusDays(1).toString(), 999, 999)
        memory.addUsage(day.plusDays(2).toString(), 999, 999)
        val updates = Channel<List<LlmUsageRow>>(Channel.UNLIMITED)
        val watcher = launch { repo.llm(day, day.plusDays(1)).collect { updates.send(it) } }
        try {
            assertTrue(withTimeout(5000) { updates.receive() }.isEmpty())
            memory.addUsage(day.toString(), 10, 2, UsagePurpose.Digest)
            val changed = withTimeout(5000) { updates.receive() }.single()
            assertEquals(UsagePurpose.Digest, changed.purpose); assertEquals(10L, changed.promptTokens)
            memory.addUsage(day.toString(), 20, 3, UsagePurpose.Digest)
            memory.addUsage(day.toString(), 7, 1, UsagePurpose.Ask)
            memory.addUsage(day.plusDays(1).toString(), 4, 2)
            val rows = repo.llm(day, day.plusDays(1)).first()
            assertEquals(3, rows.size)
            assertEquals(LlmUsageRow(day, UsagePurpose.Digest, 30, 5, 2), rows.single { it.purpose == UsagePurpose.Digest })
            assertEquals(UsagePurpose.Other, rows.last().purpose)
            val total = memory.observeUsage().first().single { it.day == day.toString() }
            assertEquals(37L, total.promptTokens); assertEquals(6L, total.completionTokens); assertEquals(3, total.calls)
        } finally { watcher.cancelAndJoin(); updates.close() }
    }

    @Test fun asrCountsOnlyUploadCallbacksAndFiltersDates() = runBlocking {
        val audio = AsrAudio(File(root, "fake.ogg"), "ogg", 3000)
        fun runner(upload: Boolean) = TranscribeRunner(db.transcribe(), object : AsrProvider {
            override suspend fun recognize(audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit): AsrResult {
                if (upload) beforeAudio()
                return AsrResult(emptyList(), audio.durationMillis, null)
            }
        }, { "test" }, { _, _ -> }, today = { day.toString() })
        assertTrue(runner(false).test(audio, "test").isSuccess)
        assertTrue(repo().asr(day, day).first().isEmpty())
        assertTrue(runner(true).test(audio, "test").isSuccess)
        db.transcribe().reserveAudio(day.plusDays(1).toString(), 4000, null)
        db.transcribe().reserveAudio(day.minusDays(1).toString(), 999, null)
        assertEquals(listOf(AsrUsageRow(day, 3000), AsrUsageRow(day.plusDays(1), 4000)), repo().asr(day, day.plusDays(1)).first())
    }

    @Test fun pricesPersistValidateAndRoundTripThroughBackupIncludingNull() {
        val repo = repo(); assertEquals(RoomUsageRepository.DEFAULT, repo.price.value)
        val price = UsagePrice(1.23456789, 0.0, 6.0)
        repo.setPrice(price); assertEquals(price, repo().price.value)
        listOf(UsagePrice(-1.0), UsagePrice(completionPerMillion = Double.NaN), UsagePrice(asrPerHour = Double.POSITIVE_INFINITY)).forEach {
            assertThrows(IllegalArgumentException::class.java) { repo.setPrice(it) }
            assertEquals(price, repo.price.value)
        }
        BackupSettings.capture(context, root)
        repo.setPrice(UsagePrice())
        BackupSettings.apply(context, root)
        assertEquals(price, repo().price.value)
        repo.setPrice(UsagePrice()); BackupSettings.capture(context, root)
        repo.setPrice(price); BackupSettings.apply(context, root)
        assertEquals(UsagePrice(), repo().price.value)
    }

    @Test fun digestReviewSkillAndDraftUseTheirOwnPurpose() = runBlocking {
        db.capture().segment(SegmentEntity(1, at + 1000, at + 2000, "test", "/synthetic", 0, "Done"))
        db.transcribe().insertLines(listOf(TranscriptLineEntity(1, 1, at + 1000, at + 2000, "讨论项目", null)))
        db.memory().insertEvent(MemoryEventEntity(1, at + 1000, at + 2000, "会议", "项目", "讨论项目", "[]", "[]", null, null, null, false, false, at, "test"))
        val client = LlmClient(); val settings = AndroidDigestSettings(context, client)
        val runner = DigestRunner(context, db.memory(), client, settings, { "测试" }, { true }, now = { at + 900000 }, readKey = { "test" },
            request = { _, _, _, _ -> LlmReply("""{"events":[],"items":[]}""", 10, 1) })
        runner.forceOnce = true; assertFalse(runner.run())
        val preset = LlmPreset("test", "test", "https://example.invalid", "test")
        val review = RoomReviewRepository(db.review(), db.memory(), context.getSharedPreferences("review", 0), scope,
            { preset to "test" }, { _, _, _, _ -> LlmReply("""{"brief":"今天讨论项目","sections":[]}""", 20, 2) }, { "测试" }, "回响", notifier, {},
            now = { at + 23 * 3600000 }, zone = { zone })
        review.run(day, false)
        val files = SkillFiles(File(root, "skills")) {
            File("src/main/assets/skills").listFiles()!!.map { dir ->
                SkillJson.parse(File(dir, "skill.json").readText(), SkillOrigin.Builtin) to File(dir, "prompt.md").readText()
            }
        }
        var draft = false
        val skills = LocalSkillRepository(files, db.skills(), db.memory(), scope, { preset to "test" }, { _, _, _, _ ->
            if (draft) LlmReply("""{"ask":"希望几点提醒？","options":["早上","晚上"]}""", 40, 4)
            else LlmReply("""{"cards":[],"nothing_reason":"没有会议"}""", 30, 3)
        }, { emptyList() }, notifier, { _, _ -> }, { at + 23 * 3600000 })
        skills.trial("meeting", day, null)
        draft = true; skills.draft(listOf(DraftTurn(true, "帮我记录会议")))
        val rows = repo().llm(day.minusDays(1), LocalDate.now().plusDays(1).coerceAtLeast(day)).first().associateBy { it.purpose }
        assertEquals(setOf(UsagePurpose.Digest, UsagePurpose.Review, UsagePurpose.Skill, UsagePurpose.Draft), rows.keys)
        listOf(UsagePurpose.Digest, UsagePurpose.Review, UsagePurpose.Skill, UsagePurpose.Draft).forEachIndexed { index, purpose ->
            assertEquals((index + 1) * 10L, rows.getValue(purpose).promptTokens)
            assertEquals(1, rows.getValue(purpose).calls)
        }
    }

    @Test fun askConversationAndSuggestionsBothRecordAsk() = runBlocking {
        val today = LocalDate.now(); val start = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        db.memory().insertEvent(MemoryEventEntity(1, start + 1000, start + 2000, "会议", "项目", "讨论项目", "[]", "[]", null, null, null, false, false, start, "test"))
        context.getSharedPreferences("digest-secrets", 0).edit().putString("key-deepseek", "test-presence-only").commit()
        val client = LlmClient(OkHttpClient.Builder().addInterceptor { chain ->
            val requestBody = okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            val stream = JSONObject(requestBody).optBoolean("stream")
            val body = if (stream) "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"没找到\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":2}}\n\ndata: [DONE]\n\n"
                else """{"choices":[{"message":{"content":"{\"questions\":[\"今天做了什么？\"]}"}}],"usage":{"prompt_tokens":10,"completion_tokens":1}}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build())
        AppGraph.prompts = app.resound.prompts.LocalPromptTemplates(context)
        val ask = createAskRepository(context, db, client, AndroidDigestSettings(context, client), scope, readKey = { "test" })
        withTimeout(5000) { repo().llm(today, today).first { it.sumOf { r -> r.calls } == 1 } }
        ask.ask("今天做了什么？")
        val row = withTimeout(5000) { repo().llm(today, today).first { it.sumOf { r -> r.calls } == 2 } }.single()
        assertEquals(UsagePurpose.Ask, row.purpose); assertEquals(30L, row.promptTokens); assertEquals(3L, row.completionTokens)
    }
}
