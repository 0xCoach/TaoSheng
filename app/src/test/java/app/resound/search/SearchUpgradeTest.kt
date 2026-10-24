package app.resound.search

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import app.resound.ask.*
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class SearchUpgradeTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val at = AskTools.timestamp("2026-10-23 10:00", false, ZoneId.systemDefault())
    private fun database() = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
        .addCallback(SearchIndex.callback).allowMainThreadQueries().build()
    private inline fun <T> CaptureDatabase.use(block: (CaptureDatabase) -> T): T = try { block(this) } finally { close() }
    private fun tools(db: CaptureDatabase, embedder: Embedder? = null) = AskTools(db,
        JSONObject(File("src/main/assets/prompts/ask-sql.json").readText()), embedder = { embedder })
    private fun seed(db: CaptureDatabase, id: Int = 1, title: String = "交通票据", deleted: Int = 0) {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("INSERT OR IGNORE INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status) VALUES(1,?,?,'mic','test',1,'Done')", arrayOf(at, at+1000))
        sql.execSQL("""INSERT INTO memory_events(id,startAt,endAt,scene,title,summary,pointsJson,peopleJson,important,deleted,createdAt,model)
            VALUES(?,?,?,'其他',?,'明天把车费发票交给财务','["整理票据"]','[]',0,?,?,'test')""", arrayOf(id,at,at+1000,title,deleted,at))
        sql.execSQL("INSERT INTO memory_items(id,eventId,category,content,status,createdAt) VALUES(?,?,'待办','交通票据交给财务','Pending',?)",arrayOf(id,id,at))
        sql.execSQL("INSERT INTO transcript_lines(id,segmentId,startAt,endAt,text,eventId,speakerName) VALUES(?,1,?,?,'明天把车费发票交给财务',?,'我')",arrayOf(id,at,at+1000,id))
    }
    private fun scalar(db: CaptureDatabase, sql: String): Long = db.query(sql, null).use { it.moveToFirst(); it.getLong(0) }
    private fun found(db: CaptureDatabase, name: String, args: String, embedder: Embedder? = null) =
        tools(db, embedder).execute(name, JSONObject(args), mutableListOf()).getJSONArray("results")
    private fun source(kind: String = "item", id: Long = 1) = AskSource(kind, id, at, "记录", "原话")
    private suspend fun save(db: CaptureDatabase, proposal: ProposedChange): Long {
        val conversation = db.ask().insertConversation(AskConversationEntity(createdAt=at,title="改动"))
        return db.ask().insertMessage(AskMessageEntity(conversationId=conversation,role="Assistant",text="请确认",sourcesJson="[]",at=at,error=null,actionJson=proposal.encode()))
    }

    @Test fun bigramsMatchSqlTokenizerAndNeverInjectMatchSyntax() {
        database().use { db ->
            val text = "中文检索，A12 foo-bar 单!"
            val tokens = listOf("中文", "文检", "检索", "a12", "foo", "bar", "单")
            assertEquals(tokens, SearchTokens.split(text))
            db.query(SimpleSQLiteQuery("SELECT ${SearchTokens.sql("?")}", arrayOf(text))).use { c ->
                c.moveToFirst(); assertEquals(tokens.joinToString(" "), c.getString(0))
            }
            assertEquals("(\"中文\" AND \"文检\" AND \"检索\") OR (\"hello\")", SearchTokens.match(listOf("中文检索", "Hello")))
            assertEquals("", SearchTokens.match(listOf("' %_ \"")))
        }
    }

    @Test fun tenToElevenAndOneToElevenBackfillAndPreserveData() = runBlocking {
        for (version in listOf(10, 1)) {
            val name = "search-migration-$version.db"; context.deleteDatabase(name)
            val schema = JSONObject(File("schemas/app.resound.data.CaptureDatabase/$version.json").readText()).getJSONObject("database")
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val e = entities.getJSONObject(i)
                    fun sql(s: String) = s.replace("\u0024{TABLE_NAME}", e.getString("tableName"))
                    old.execSQL(sql(e.getString("createSql")))
                    val indices = e.optJSONArray("indices") ?: JSONArray()
                    for (j in 0 until indices.length()) old.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
                old.execSQL("INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status) VALUES(1,1000,2000,'mic','old',1,'Done')")
                if (version == 10) {
                    old.execSQL("INSERT INTO transcript_lines(id,segmentId,startAt,endAt,text) VALUES(1,1,1000,2000,'历史发票')")
                    old.execSQL("INSERT INTO memory_events(id,startAt,endAt,scene,title,summary,pointsJson,peopleJson,important,deleted,createdAt,model) VALUES(1,1000,2000,'其他','历史发票','摘要','[]','[]',0,0,1000,'test')")
                    old.execSQL("INSERT INTO memory_items(id,eventId,category,content,status,createdAt) VALUES(1,1,'待办','历史发票','Done',1000)")
                    old.execSQL("INSERT INTO ask_conversations VALUES(1,1000,'旧对话')")
                    old.execSQL("INSERT INTO ask_messages VALUES(1,1,'Assistant','旧回答','[]',1000,NULL)")
                }
                old.version = version
            }
            val db = Room.databaseBuilder(context,CaptureDatabase::class.java,name).allowMainThreadQueries().addCallback(SearchIndex.callback)
                .addMigrations(CaptureDatabase.MIGRATION_1_2,CaptureDatabase.MIGRATION_2_3,CaptureDatabase.MIGRATION_3_4,
                    CaptureDatabase.MIGRATION_4_5,CaptureDatabase.MIGRATION_5_6,CaptureDatabase.MIGRATION_6_7,
                    CaptureDatabase.MIGRATION_7_8,CaptureDatabase.MIGRATION_8_9,CaptureDatabase.MIGRATION_9_10,CaptureDatabase.MIGRATION_10_11).build()
            try {
                assertEquals(CaptureDatabase.VERSION, db.openHelper.writableDatabase.version) // Room 验证 schema。
                assertEquals("old", db.transcribe().segment(1).path)
                if (version == 10) {
                    for (tool in listOf("search_lines","search_events","list_items")) assertEquals(1, found(db,tool,"""{"keywords":["发票"]}""").length())
                    assertNull(db.ask().message(1)!!.actionJson)
                    assertEquals(1,scalar(db,"SELECT count(*) FROM memory_items WHERE status='Done'"))
                }
            } finally { db.close(); context.deleteDatabase(name) }
        }
    }

    @Test fun ftsTracksInsertUpdateDeleteAndRetainsSpeakerTimeAndHiddenFilters() {
        database().use { db ->
            seed(db); seed(db,2,deleted=1)
            assertEquals(1,found(db,"search_lines","""{"keywords":["发票"],"speaker":"我","from":"2026-10-23","to":"2026-10-23"}""").length())
            assertEquals(0,found(db,"search_lines","""{"keywords":["发票"],"speaker":"他"}""").length())
            assertEquals(0,found(db,"search_lines","""{"keywords":["发票"],"from":"2026-10-24"}""").length())
            db.openHelper.writableDatabase.execSQL("UPDATE transcript_lines SET text='明天吃饭' WHERE id=1")
            assertEquals(0,found(db,"search_lines","""{"keywords":["发票"]}""").length())
            assertEquals(1,found(db,"search_lines","""{"keywords":["吃饭"]}""").length())
            db.openHelper.writableDatabase.execSQL("DELETE FROM transcript_lines WHERE id=1")
            assertEquals(0,scalar(db,"SELECT count(*) FROM lines_fts WHERE rowid=1"))
        }
    }

    @Test fun reciprocalRankingDeduplicatesAndCapsAtTen() {
        assertEquals(listOf(2,1,3),Vectors.merge(listOf(1,2),listOf(2,3)))
        assertEquals(10,Vectors.merge((1..50).toList(),(50..80).toList()).size)
        assertEquals(listOf(1,2),Vectors.merge(listOf(1,1,2),emptyList()))
    }

    @Test fun mixedSearchUsesSemanticFiltersAndFallsBackWhenModelUnavailable() {
        database().use { db ->
            seed(db); seed(db,2,deleted=1)
            val store = EmbeddingStore(db)
            store.pending("test").forEach { store.save(it,"test",floatArrayOf(1f,0f)) }
            var ready = false
            val embedder = object : Embedder {
                override val model = "test"
                override fun ready() = ready
                override fun embed(text: String, query: Boolean) = floatArrayOf(1f,0f)
            }
            assertEquals(1,found(db,"search_events","""{"keywords":["发票"]}""",embedder).length())
            assertEquals(0,found(db,"search_events","""{"keywords":["报销"]}""",embedder).length())
            ready = true
            assertEquals(1,found(db,"search_events","""{"keywords":["报销"]}""",embedder).length())
            assertEquals(1,found(db,"list_items","""{"keywords":["报销"],"status":"Pending"}""",embedder).length())
            assertEquals(0,found(db,"list_items","""{"keywords":["报销"],"status":"Done"}""",embedder).length())
            assertEquals(0,found(db,"semantic_search","""{"query":"报销","from":"2026-10-24"}""",embedder).length())
            assertEquals(2,found(db,"semantic_search","""{"query":"报销"}""",embedder).length())
        }
    }

    @Test fun proposalsPersistOnePerAnswerAndRepositoryConfirmsOrCancelsExactlyOnce() = runBlocking {
        val db = database(); seed(db)
        var scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val changes = AskChanges(db, { at + 5000 })
        val query = JSONObject("""{"operation":"item_status","id":1,"status":"Done"}""")
        val prefs = context.getSharedPreferences("search-actions",0)
        var round = 0
        fun repo() = RoomAskRepository(db.ask(),scope,{true},engine={
            AskEngine(AskStream { messages,_,_,_ ->
                when (++round) {
                    1 -> LlmStreamReply("",listOf(LlmToolCall("search","list_items","{}")))
                    2 -> LlmStreamReply("",listOf(LlmToolCall("a","propose_change",query.toString()),LlmToolCall("b","propose_change",query.toString())))
                    else -> {
                        assertTrue(messages.toString().contains("pending")); assertTrue(messages.toString().contains("error"))
                        LlmStreamReply("请在卡片上确认",emptyList())
                    }
                }
            },JSONArray(),tools(db)::execute,{_,_->},changes::propose)
        },system={"test"},recommended=AskSuggestions(prefs,{false},{""},{emptyList()}),changes=changes)
        try {
            var repo = repo(); repo.ask("完成交通票据")
            val result = withTimeout(5000) { repo.messages.first { it.size==2 && !it.last().streaming } }.last()
            assertEquals(AskActionState.Pending,result.action!!.state)
            assertEquals(0,scalar(db,"SELECT count(*) FROM memory_items WHERE status='Done'"))
            scope.cancel(); scope.coroutineContext[Job]!!.join()
            scope = CoroutineScope(SupervisorJob()+Dispatchers.IO); repo = repo()
            withTimeout(5000) { repo.messages.first { it.lastOrNull()?.action?.state==AskActionState.Pending } }
            repo.confirmAction(result.id); repo.confirmAction(result.id)
            withTimeout(5000) { repo.messages.first { it.lastOrNull()?.action?.state==AskActionState.Done } }
            assertEquals(at+5000,scalar(db,"SELECT doneAt FROM memory_items WHERE id=1"))
            val cancelled = save(db,changes.propose(JSONObject("""{"operation":"item_status","id":1,"status":"Ignored"}"""),listOf(source())))
            repo.cancelAction(cancelled); repo.confirmAction(cancelled)
            withTimeout(5000) { while (ProposedChange.decode(db.ask().message(cancelled)?.actionJson)?.action?.state != AskActionState.Cancelled) delay(10) }
            assertEquals(1,scalar(db,"SELECT count(*) FROM memory_items WHERE status='Done'"))
        } finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); db.close() }
    }

    @Test fun fourChangeKindsAndStaleTargetsAreAtomic() = runBlocking {
        database().use { db ->
            seed(db); val changes = AskChanges(db,{at})
            suspend fun apply(json: String, refs: List<AskSource> = listOf(source(),source("event"))): Long {
                val id = save(db,changes.propose(JSONObject(json),refs)); changes.resolve(id,true); return id
            }
            apply("""{"operation":"create_item","category":"备忘","content":"带雨伞","due":"2026-10-24"}""")
            assertEquals(1,scalar(db,"SELECT count(*) FROM memory_items WHERE content='带雨伞' AND status='Accepted'"))
            apply("""{"operation":"item_due","id":1,"due":"2026-10-25 10:00"}""")
            assertEquals(AskTools.timestamp("2026-10-25 10:00",false,ZoneId.systemDefault()),scalar(db,"SELECT dueAt FROM memory_items WHERE id=1"))
            apply("""{"operation":"event_important","id":1,"important":true}""")
            assertEquals(1,scalar(db,"SELECT important FROM memory_events WHERE id=1"))
            val stale = save(db,changes.propose(JSONObject("""{"operation":"item_status","id":1,"status":"Done"}"""),listOf(source())))
            db.memory().setItemStatus(1,"Ignored",at)
            changes.resolve(stale,true)
            assertEquals(AskActionState.Failed,ProposedChange.decode(db.ask().message(stale)!!.actionJson)!!.action.state)
            assertEquals(1,scalar(db,"SELECT count(*) FROM memory_items WHERE id=1 AND status='Ignored'"))
        }
    }

    @Test fun askAboutStartsFreshAndIncludesOnlySelectedContextWithFortyLineLimit() = runBlocking {
        val db = database(); seed(db); seed(db,2,"无关事件")
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {
            for (id in 3..50) db.openHelper.writableDatabase.execSQL("INSERT INTO transcript_lines(id,segmentId,startAt,endAt,text,eventId) VALUES(?,1,?,?,'原话补充',1)",arrayOf(id,at,at+1000))
            var userText = ""
            val repo = RoomAskRepository(db.ask(),scope,{true},engine={ AskEngine(AskStream { messages,_,_,_->
                assertEquals(2,messages.length()); userText = messages.getJSONObject(1).getString("content")
                LlmStreamReply("需要把发票交财务[^2]",emptyList())
            },JSONArray(),{_,_,_->error("不需要额外检索")},{_,_->}) },system={"test"},
                recommended=AskSuggestions(context.getSharedPreferences("search-about",0),{false},{""},{emptyList()}),contextReader=AskContextReader(db))
            repo.ask("旧会话")
            withTimeout(5000) { repo.messages.first { it.size==2 && !it.last().streaming } }
            val old = repo.currentConversation.value
            repo.askAbout(source(),"要做什么")
            val response = withTimeout(5000) { repo.messages.first { it.size==2 && it.first().text=="要做什么" && !it.last().streaming } }
            assertNotEquals(old,repo.currentConversation.value)
            assertTrue(userText.contains("交通票据")); assertTrue(userText.contains("明天把车费发票交给财务"))
            assertFalse(userText.contains("无关事件")); assertNull(response.last().error)
            val context = AskContextReader(db).read(source())
            assertEquals(40,context.sources.count { it.kind=="line" })
            assertEquals(1,response.last().sources.size)
        } finally { scope.cancel(); scope.coroutineContext[Job]!!.join(); db.close() }
    }

    @Test fun embeddingBatchCapsNormalizesAndRejectsStaleSnapshot() {
        database().use { db ->
            for (id in 1..35) seed(db,id)
            val store = EmbeddingStore(db); val batch = store.pending("test")
            assertEquals(64,batch.size); val first = batch.first()
            store.save(first,"test",floatArrayOf(3f,4f))
            db.query("SELECT vector,dim FROM embeddings",null).use { c ->
                c.moveToFirst(); assertArrayEquals(floatArrayOf(.6f,.8f),Vectors.decode(c.getBlob(0),c.getInt(1)),.00001f)
            }
            db.openHelper.writableDatabase.execSQL("UPDATE memory_events SET title='变化' WHERE id=?",arrayOf(first.id))
            assertEquals(0,scalar(db,"SELECT count(*) FROM embeddings"))
            store.save(first,"test",floatArrayOf(3f,4f))
            assertEquals(0,scalar(db,"SELECT count(*) FROM embeddings"))
            db.openHelper.writableDatabase.execSQL("UPDATE memory_events SET deleted=1 WHERE id=1")
            assertFalse(store.pending("test").any { it.id==1L })
        }
    }
    @Test fun modelTokenizerAndResumableVerifiedDownloadProtocol() = runBlocking {
        val tokenizer = BertTokens(listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "你", "好", "，", "A", "##BC", "é", "##cole", "foo", "-", "bar"))
        assertArrayEquals(longArrayOf(2,4,5,6,7,8,9,10,11,12,13,3), tokenizer.encode("你好，ABC école foo-bar"))
        assertArrayEquals(longArrayOf(2,1,3), tokenizer.encode("abc")) // 不能擅自转小写或去重音。
        assertEquals(512, tokenizer.encode("你".repeat(600)).size)
        val server = okhttp3.mockwebserver.MockWebServer()
        val dir = kotlin.io.path.createTempDirectory("search-download").toFile()
        try {
            server.start()
            val file = File(dir,"model.onnx").apply { writeText("abc") }
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(206)
                .setHeader("Content-Range","bytes 3-5/6").setBody("def"))
            val spec = app.resound.transcribe.local.ModelSpec("test",server.url("/model").toString(),6,
                "bef57ec7f53a6d40beb640a780a639c83bc29ac8a9816f1fc6c5c6dcd93c4721", "model.onnx",6,
                "bef57ec7f53a6d40beb640a780a639c83bc29ac8a9816f1fc6c5c6dcd93c4721",false)
            app.resound.transcribe.local.downloadModelFile(spec,file)
            assertEquals("bytes=3-",server.takeRequest().getHeader("Range"))
            assertEquals(spec.modelHash,app.resound.transcribe.local.LocalModels.hash(file))
            app.resound.transcribe.local.ModelVerification.record(dir,spec)
            assertTrue(app.resound.transcribe.local.ModelVerification.matches(dir,spec))
            file.appendText("changed")
            assertFalse(app.resound.transcribe.local.ModelVerification.matches(dir,spec))
        } finally { server.shutdown(); dir.deleteRecursively() }
    }

}
