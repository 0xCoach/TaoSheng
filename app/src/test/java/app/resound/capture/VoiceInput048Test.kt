package app.resound.capture

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import app.resound.ask.AskTools
import app.resound.backup.BackupSettings
import app.resound.data.*
import app.resound.digest.DigestPrompt
import app.resound.push.*
import app.resound.search.SearchIndex
import app.resound.transcribe.*
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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class VoiceInput048Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: CaptureDatabase
    private val settings = VoiceInputSettings(enabled = true)
    private val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 100000
    private val meta = VoiceInputMeta("合成聊天", "synthetic.chat", "老王", "报告明天能交吗", null)
    private fun screen(text: String = "草稿", lines: List<String> = listOf("报告明天能交吗")) =
        InputScreen("synthetic.chat", "合成聊天", "chat:老王", "老王", "editor", text, lines = lines)
    private fun record(token: String = UUID.randomUUID().toString(), at: Long = start, text: String = "好的明天下午给你", until: Long? = null) =
        InputRecord(token, at, text, meta, until)
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().addCallback(SearchIndex.callback).build()
    }
    @After fun cleanup() { db.close() }

    @Test fun windowWaitsThreeSecondsAndSeparatesConsecutiveDictations() {
        // 负责人定：以发送为界。同一输入框里停顿后接着说 = 同一条；发送（一下清空）才定稿
        val out = mutableListOf<InputChange>(); val e = VoiceInputEngine(out::add)
        e.recording(true, 0, screen(), settings)
        e.tick(100, screen("草稿第一句"), settings)
        e.recording(false, 200, screen("草稿第一句"), settings)
        e.tick(3200, screen("草稿第一句"), settings); assertTrue(out.isEmpty())
        e.recording(true, 3300, screen("草稿第一句"), settings)
        e.tick(3400, screen("草稿第一句第二句"), settings)
        e.recording(false, 3500, screen("草稿第一句第二句"), settings)
        e.tick(6500, screen("草稿第一句第二句"), settings); assertTrue(out.isEmpty())
        e.tick(7000, screen(""), settings)
        assertEquals(listOf("第一句第二句"), out.filterIsInstance<InputChange.Save>().map { it.record.text })
        // 发完再说一段，是新的一条
        e.recording(true, 8000, screen(""), settings); e.tick(8100, screen("第三句"), settings)
        e.recording(false, 8200, screen("第三句"), settings); e.tick(9000, screen(""), settings)
        assertEquals(listOf("第一句第二句", "第三句"), out.filterIsInstance<InputChange.Save>().map { it.record.text })
        // 不发送也不离开：停 60 秒算说完（备忘录）
        val memo = mutableListOf<InputChange>(); val m = VoiceInputEngine(memo::add)
        m.recording(true, 0, screen(""), settings); m.tick(100, screen("备忘一句"), settings)
        m.recording(false, 200, screen("备忘一句"), settings); m.tick(59000, screen("备忘一句"), settings); assertTrue(memo.isEmpty())
        m.tick(60300, screen("备忘一句"), settings)
        assertEquals("备忘一句", memo.filterIsInstance<InputChange.Save>().single().record.text)
    }
    @Test fun insertedTextSupportsMiddleAndAppendButNotDeletionOrReplacement() {
        assertEquals("明天", VoiceInputText.inserted("我交报告", "我明天交报告"))
        assertEquals("下午", VoiceInputText.inserted("明天", "明天下午"))
        assertEquals("", VoiceInputText.inserted("删掉的草稿", "草稿"))
        assertEquals("", VoiceInputText.inserted("旧内容", "新内容"))
        assertEquals("", VoiceInputText.inserted("草稿", ""))
    }
    @Test fun sentClearUsesLastTextButDeletedDraftIsNotSaved() {
        // 一下子清空 = 发送（ChatGPT/Gemini 的发送键是图标）；一点点删光 = 删掉
        fun scenario(sent: Boolean): List<InputChange> {
            val out = mutableListOf<InputChange>(); val e = VoiceInputEngine(out::add)
            e.recording(true, 0, screen(""), settings)
            e.tick(10, screen("合成一句话"), settings)
            if (!sent) { e.tick(15, screen("合成"), settings); e.tick(18, screen("合"), settings) }
            e.tick(20, screen("", listOf("报告明天能交吗")), settings)
            e.recording(false, 30, screen(""), settings); e.tick(3030, screen(""), settings)
            return out
        }
        assertEquals("合成一句话", scenario(true).filterIsInstance<InputChange.Save>().single().record.text)
        assertTrue(scenario(false).isEmpty())
        val later = mutableListOf<InputChange>(); val engine = VoiceInputEngine(later::add)
        engine.recording(true, 0, screen(""), settings); engine.tick(10, screen("稍后发送"), settings)
        engine.recording(false, 100, screen("稍后发送"), settings)
        engine.tick(3100, screen("稍后发送"), settings)
        engine.tick(10000, screen("", listOf("稍后发送", "合成回复")), settings)
        assertTrue(later.none { it is InputChange.Sent })
        engine.tick(130000, screen(""), settings)
        assertTrue(later.none { it is InputChange.Finish })
    }
    @Test fun privacyGatesAndPauseDiscardWindow() {
        for (state in listOf(CaptureState.Paused, CaptureState.Stopped, CaptureState.NoPermission))
            assertFalse(VoiceInputText.allowed(settings, state, "chat", "own", false))
        assertFalse(VoiceInputText.allowed(settings, CaptureState.Listening, "chat", "own", true))
        assertFalse(VoiceInputText.allowed(settings.copy(excludedPackages = setOf("chat")), CaptureState.MicBusy, "chat", "own", false))
        assertFalse(VoiceInputText.allowed(settings, CaptureState.Listening, "own", "own", false))
        assertTrue(VoiceInputText.allowed(settings, CaptureState.MicBusy, "chat", "own", false))
        val out = mutableListOf<InputChange>(); val e = VoiceInputEngine(out::add)
        e.recording(true, 0, screen(""), settings); e.tick(1, screen("未完成"), settings)
        e.reset(); e.tick(5000, screen("未完成"), settings); assertTrue(out.isEmpty())
    }
    @Test fun repliesAreNeverAppendedAndContextIsBounded() {
        val out = mutableListOf<InputChange>(); val e = VoiceInputEngine(out::add)
        e.recording(true, 0, screen(""), settings); e.tick(1, screen("好的"), settings)
        e.tick(10, screen("", listOf("报告明天能交吗", "好的", "收到")), settings)
        e.tick(50, screen("", listOf("好的", "收到", "谢谢")), settings)
        e.tick(120010, screen("", listOf("收到", "谢谢", "过期内容")), settings)
        e.tick(120020, screen(""), settings)
        assertTrue(out.none { it is InputChange.Finish })
        assertNull(out.filterIsInstance<InputChange.Save>().single().record.meta.after)
        assertEquals(20, VoiceInputText.context((1..40).map { "第${it}行" })!!.lines().size)
        assertEquals(1500, VoiceInputText.context(List(20) { "字".repeat(100) })!!.length)
        val other = mutableListOf<InputChange>(); val second = VoiceInputEngine(other::add)
        second.recording(true, 0, screen(""), settings); second.tick(1, screen("你好"), settings)
        second.tick(2, screen("", listOf("你好", "回复")), settings)
        second.tick(3, screen("").copy(page = "another"), settings)
        second.tick(4, screen(""), settings)
        assertTrue(other.none { it is InputChange.Finish })
    }
    @Test fun disabledContextOrRepliesNeverCollectsReplies() {
        for (s in listOf(settings.copy(context = false), settings.copy(replies = false))) {
            val out = mutableListOf<InputChange>(); val e = VoiceInputEngine(out::add)
            e.recording(true, 0, screen(""), s); e.tick(1, screen("好的"), s)
            e.tick(2, screen("", listOf("好的", "回复")), s)
            e.tick(130000, screen(""), s)
            val row = out.filterIsInstance<InputChange.Save>().single().record
            assertNull(row.replyUntil)
            if (!s.context) assertNull(row.meta.before)
            assertTrue(out.none { it is InputChange.Finish })
        }
    }
    @Test fun persistenceIsIdempotentAndDeduplicatesNearbyAudio() = runBlocking {
        val store = VoiceInputStore(db)
        val original = record()
        val id = store.save(original)!!; assertEquals(id, store.save(original))
        assertEquals(1, db.voiceInput().observed().first().size)
        db.capture().segment(SegmentEntity(id = 100, startAt = start, endAt = start + 2000, sourceId = "mic", path = "test.ogg", sizeBytes = 1, status = "Done"))
        db.transcribe().insertLines(listOf(TranscriptLineEntity(segmentId = 100, startAt = start, endAt = start + 1000, text = "请明天下午把合成报告交给我。", speakerTag = null)))
        assertNull(store.save(record(text = "请明天下午把合成报告交给我！")))
        assertNotNull(store.save(record(at = start + 31000, text = "请明天下午把合成报告交给我")))
    }
    @Test fun finalizationSurvivesMappingAndNeverEntersVoiceprint() = runBlocking {
        // 负责人定不补记回复：存下即可整理，不等定稿
        val store = VoiceInputStore(db); val r = record(until = System.currentTimeMillis() + 120000)
        val id = store.save(r)!!
        val line = db.voiceprint().line(id)!!
        assertNull(line.voiceInputPendingUntil); assertNull(line.voiceInputMeta()!!.after)
        val segment = db.transcribe().segment(line.segmentId)
        assertEquals("voice-input", segment.sourceId); assertEquals("Done", segment.status)
        assertFalse(File(segment.path).isFile)
        assertTrue(line.speakerManual); assertEquals("我", line.speakerName)
        assertEquals(1, db.memory().undigestedLines().size)
        db.voiceprint().invalidate(); db.voiceprint().clearResults()
        db.voiceprint().identity(listOf(id), 99, "别人", .99f, true)
        assertEquals("我", db.voiceprint().line(id)!!.speakerName)
        assertTrue(db.voiceprint().pending().isEmpty()); assertTrue(db.voiceprint().allSamples().isEmpty())
    }
    @Test fun migration16PreservesOldLinesAndDefaultsToNoMetadata() {
        val path = File(context.cacheDir, "048-${UUID.randomUUID()}.db")
        val schema = JSONObject(File("schemas/app.resound.data.CaptureDatabase/16.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { i ->
                val entity = entities.getJSONObject(i)
                fun sql(s: String) = s.replace("\u0024{TABLE_NAME}", entity.getString("tableName"))
                old.execSQL(sql(entity.getString("createSql")))
                entity.optJSONArray("indices")?.let { a -> repeat(a.length()) { old.execSQL(sql(a.getJSONObject(it).getString("createSql"))) } }
            }
            val setup = schema.getJSONArray("setupQueries"); repeat(setup.length()) { old.execSQL(setup.getString(it)) }
            old.execSQL("INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status) VALUES(1,0,1,'mic','old.ogg',1,'Done')")
            old.execSQL("INSERT INTO transcript_lines(id,segmentId,startAt,endAt,text) VALUES(1,1,0,1,'合成旧句')")
            old.version = 16
        }
        val migrated = CaptureDatabase.open(context, path.path)
        try {
            assertEquals(CaptureDatabase.VERSION, migrated.openHelper.writableDatabase.version)
            migrated.openHelper.writableDatabase.query("SELECT text,voiceInputJson,voiceInputPendingUntil FROM transcript_lines").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("合成旧句", c.getString(0)); assertTrue(c.isNull(1)); assertTrue(c.isNull(2))
            }
        } finally { migrated.close(); path.delete() }
    }
    @Test fun digestAndAskCarryContextAsSourceMaterial() = runBlocking {
        val id = VoiceInputStore(db).save(record())!!
        val line = db.voiceprint().line(id)!!
        val prompt = DigestPrompt.transcript(listOf(line)).first
        assertTrue(prompt.contains("上下文资料")); assertTrue(prompt.contains("报告明天能交吗"))
        assertTrue(DigestPrompt.system("自定义模板", LocalDate.now(), "回响").contains("别人提出要求但我没答应"))
        val spec = JSONObject(File("src/main/assets/prompts/ask-sql.json").readText())
        val result = AskTools(db, spec).execute("search_lines", JSONObject("""{"keywords":["明天"]}"""), mutableListOf())
        assertTrue(result.toString().contains("voice_input")); assertTrue(result.toString().contains("报告明天能交吗"))
    }
    @Test fun folderWebhookAndSubscriptionHonorContextSwitch() = runBlocking {
        val id = VoiceInputStore(db).save(record())!!
        val row = db.voiceprint().line(id)!!
        val s = PushSubscription(lines = LinesMode.All)
        val data = PushSnapshot(lines = listOf(row))
        val full = MemoryFolder(LocalDate.now()).generate(data, s).getValue(MemoryFolder.transcriptPath(LocalDate.now()))
        assertTrue(full.contains("⌨ 语音输入 · 合成聊天 · 老王")); assertTrue(full.contains("> 报告明天能交吗"))
        val off = s.copy(lineContext = false)
        assertFalse(Subscriptions.read(Subscriptions.json(off)).lineContext)
        assertTrue(Subscriptions.read(Subscriptions.json(s).apply { remove("lineContext") }).lineContext)
        assertFalse(MemoryFolder(LocalDate.now()).generate(data, off).values.joinToString().contains("报告明天能交吗"))
        assertEquals("报告明天能交吗", RecordDelta.records(data, s).values.single().getJSONObject("voice_input").getString("before"))
        assertFalse(RecordDelta.records(data, off).values.single().getJSONObject("voice_input").has("before"))
    }
    @Test fun settingsBackupAndTodayCountAndRecentApps() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val directory = File(context.cacheDir, "048-settings-${UUID.randomUUID()}").apply { mkdirs() }
        val capture = AndroidVoiceInputCapture(context, db, scope)
        try {
            capture.update(settings.copy(context = false, excludedPackages = setOf("excluded")))
            BackupSettings.capture(context, directory) { _, _ -> "" }
            capture.update(VoiceInputSettings())
            BackupSettings.apply(context, directory) { _, _, _ -> }
            val prefs = context.getSharedPreferences("voice-input", 0)
            assertTrue(prefs.getBoolean("enabled", false)); assertFalse(prefs.getBoolean("context", true))
            assertEquals(setOf("excluded"), prefs.getStringSet("excluded", emptySet()))
            VoiceInputStore(db).save(record())
            withTimeout(5000) { capture.todayCount.first { it == 1 } }
            assertEquals("synthetic.chat", withTimeout(5000) { capture.seenApps.first { it.isNotEmpty() } }.first().packageName)
        } finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); directory.deleteRecursively() }
    }
}
