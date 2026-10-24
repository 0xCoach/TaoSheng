package app.resound.ask

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.*
import app.resound.digest.LlmStreamReply
import app.resound.digest.LlmToolCall
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class AskPersistenceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val source = AskSource("line", 7, 1000, "原话", "旧原话")

    @Test fun migrationsFromOneAndFivePreserveDataAndPersistCitedExchange() = runBlocking {
        for (version in listOf(1, 5)) {
            val name = "ask-migration-$version.db"
            context.deleteDatabase(name)
            val schema = JSONObject(File("schemas/app.resound.data.CaptureDatabase/$version.json").readText()).getJSONObject("database")
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    fun sql(value: String) = value.replace("\u0024{TABLE_NAME}", entity.getString("tableName"))
                    old.execSQL(sql(entity.getString("createSql")))
                    val indices = entity.optJSONArray("indices") ?: JSONArray()
                    for (j in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
                }
                val queries = schema.getJSONArray("setupQueries")
                for (i in 0 until queries.length()) old.execSQL(queries.getString(i))
                old.execSQL("INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status) VALUES(1,1000,2000,'mic','/old',10,?)", arrayOf<Any>(if (version == 1) "待转写" else "Done"))
                if (version == 5) {
                    old.execSQL("INSERT INTO transcript_lines(id,segmentId,startAt,endAt,text,speakerName) VALUES(7,1,1000,2000,'旧原话','我')")
                    old.execSQL("INSERT INTO llm_usage VALUES('2026-10-23',11,5,2)")
                }
                old.version = version
            }
            fun open() = Room.databaseBuilder(context, CaptureDatabase::class.java, name).allowMainThreadQueries()
                .addMigrations(*CaptureDatabase.MIGRATIONS).build()
            var db = open()
            try {
                assertEquals(CaptureDatabase.VERSION, db.openHelper.writableDatabase.version) // Room 验证全部表、列、索引和外键。
                assertEquals(if (version == 1) "Pending" else "Done", db.transcribe().segment(1).status)
                if (version == 5) {
                    assertEquals("旧原话", db.voiceprint().line(7)!!.text)
                    assertEquals(11L, db.memory().observeUsage().first().single().promptTokens)
                }
                val conversation = db.ask().insertConversation(AskConversationEntity(createdAt=3000,title="旧事"))
                val user = AskMessageEntity(conversationId=conversation,role="User",text="说了什么",sourcesJson="[]",at=3000,error=null)
                val answer = user.copy(role="Assistant",text="旧原话[^1]",sourcesJson=AskCitations.encode(listOf(source)),error="可恢复错误")
                db.ask().saveExchange(user, answer)
                db.close(); db = open()
                val saved = db.ask().messages(conversation)
                assertEquals(listOf("User","Assistant"), saved.map { it.role })
                assertEquals(listOf(source), AskCitations.decode(saved.last().sourcesJson))
                assertEquals("可恢复错误", saved.last().error)
            } finally { db.close(); context.deleteDatabase(name) }
        }
    }

    @Test fun stopPersistsOnlyFinalPartialAndRestartAndNewConversationStayIsolated() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).build()
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val key = AtomicBoolean(true); val calls = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val prefs = context.getSharedPreferences("ask-repo-test", Context.MODE_PRIVATE).apply { edit().clear().commit() }
        fun repository(): RoomAskRepository {
            val recommended = AskSuggestions(prefs, {key.get()}, {""}, { emptyList() })
            return RoomAskRepository(db.ask(), scope, {key.get()}, engine={
                AskEngine(AskStream { _,_,delta,usage ->
                    usage(4,2)
                    if (calls.incrementAndGet() == 1) LlmStreamReply("",listOf(LlmToolCall("a","search_lines","{\"keywords\":[]}")))
                    else { delta("半句[^1]"); started.complete(Unit); awaitCancellation() }
                }, JSONArray().put(JSONObject()), { _,_,sources -> sources += source; JSONObject().put("ref",1) },
                    { p,c -> db.memory().addUsage("2026-10-23",p,c) })
            }, system={"提示词"}, recommended=recommended)
        }
        try {
            val repo = repository(); repo.ask("问一问")
            withTimeout(5000) { started.await() }
            val conversation = db.ask().latest()!!.id
            assertTrue(db.ask().messages(conversation).isEmpty()) // 中间态完全在内存。
            repo.stop()
            val final = withTimeout(5000) { repo.messages.first { it.size==2 && !it.last().streaming } }
            assertEquals("半句[^1]", final.last().text); assertNull(final.last().progress)
            assertEquals(listOf(source), final.last().sources)
            assertEquals(2, db.ask().messages(conversation).size)
            assertEquals(2, db.memory().observeUsage().first().single().calls)
            scope.cancel(); scope.coroutineContext[Job]!!.join()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val restored = repository()
            val loaded = withTimeout(5000) { restored.messages.first { it.size==2 } }
            assertEquals(final, loaded)
            key.set(false); restored.ask("缺密钥")
            val missingKey = withTimeout(5000) { restored.messages.first { it.size==4 && !it.last().streaming } }
            assertEquals("还没填写大模型密钥", missingKey.last().error); assertEquals(2,calls.get())
            restored.newConversation()
            withTimeout(5000) { restored.messages.first { it.isEmpty() } }
            assertNull(restored.currentConversation.value)
            assertEquals(4, db.ask().messages(conversation).size)
        } finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); db.close() }
    }

    @Test fun historySearchPagingSelectionRenameAndDelete() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).build()
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = context.getSharedPreferences("history-test", Context.MODE_PRIVATE)
        var selection: Long? = null
        fun repository() = RoomAskRepository(db.ask(), scope, { false },
            engine = { error("No network expected") }, system = { "" },
            recommended = AskSuggestions(prefs, { false }, { "" }, { emptyList() }),
            readSelected = { selection }, saveSelected = { selection = it })
        try {
            val ids = (1..32).map { n ->
                val id = db.ask().insertConversation(AskConversationEntity(createdAt = n.toLong(), title = "标题$n"))
                db.ask().insertMessage(AskMessageEntity(conversationId = id, role = "User", text = "内容$n", sourcesJson = "[]", at = n.toLong(), error = null))
                id
            }
            db.ask().insertConversation(AskConversationEntity(createdAt = 99, title = ""))
            assertEquals(30, db.ask().history("", 30).first().size)
            assertEquals(32, db.ask().history("", 60).first().size)
            assertEquals(ids.last(), db.ask().history("", 30).first().first().id)
            assertEquals(ids[16], db.ask().history("内容17", 30).first().single().id)
            val repo = repository()
            repo.openConversation(ids.first())
            withTimeout(5000) { repo.currentConversation.first { it == ids.first() } }
            assertEquals("内容1", repo.messages.value.single().text)
            repo.renameConversation(ids.first(), "我的计划")
            withTimeout(5000) { db.ask().history("我的计划", 30).first { it.size == 1 } }
            scope.cancel(); scope.coroutineContext[Job]!!.join()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val restored = repository()
            withTimeout(5000) { restored.messages.first { it.isNotEmpty() } }
            assertEquals(ids.first(), restored.currentConversation.value)
            restored.deleteConversation(ids.first())
            withTimeout(5000) { restored.messages.first { it.isEmpty() } }
            assertTrue(db.ask().messages(ids.first()).isEmpty())
            assertNull(db.ask().conversation(ids.first()))
            assertEquals(31, db.ask().history("", 60).first().size)
            restored.newConversation()
            restored.ask("新问题")
            withTimeout(5000) { restored.messages.first { it.size == 2 && !it.last().streaming } }
            assertEquals("新问题", db.ask().conversation(restored.currentConversation.value!!)!!.title)
            assertEquals(1, db.ask().messages(ids[1]).size)
        } finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); db.close() }
    }

    @Test fun suggestionsCacheBoundsAndDailyReservationsSurviveRestartAndFailure() = runBlocking {
        val prefs = context.getSharedPreferences("ask-cache-test", Context.MODE_PRIVATE).apply { edit().clear().commit() }
        val key = AtomicBoolean(false); var count = 0
        val clock = AtomicLong(AskTools.timestamp("2026-10-23",false,ZoneId.of("Asia/Shanghai")))
        fun cache() = AskSuggestions(prefs, {key.get()}, {"近期事件"}, {
            count++
            if (count == 2) throw java.io.IOException("服务端原文不应显示")
            listOf("今天开会了吗？", "今天开会了吗？", "最近的想法", "这个问题实在太长所以不允许展示在胶囊上", "最近的待办", "会议定了什么", "第五条")
        }, clock::get, ZoneId.of("Asia/Shanghai"))
        var cache = cache()
        cache.refresh(); assertEquals(AskSuggestions.EXAMPLES,cache.questions.value); assertEquals(0,count)
        key.set(true); cache.refresh()
        assertEquals(1,count); assertEquals(4,cache.questions.value.size)
        assertTrue(cache.questions.value.all { it.length<=12 })
        val first = cache.questions.value
        cache = cache(); cache.refresh()
        assertEquals(first,cache.questions.value); assertEquals(1,count)
        clock.addAndGet(6*60*60_000L); cache.refresh()
        assertEquals(2,count); assertEquals(first,cache.questions.value) // 失败保留旧缓存，也占一天一次。
        clock.addAndGet(6*60*60_000L); cache.refresh(); assertEquals(2,count)
        clock.set(AskTools.timestamp("2026-10-24",false,ZoneId.of("Asia/Shanghai")))
        cache.refresh(); assertEquals(3,count); assertEquals(1,prefs.getInt("count",0))
        key.set(false); cache.refresh(); assertEquals(AskSuggestions.EXAMPLES,cache.questions.value)
    }
}
