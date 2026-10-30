package app.resound.things

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import app.resound.ask.*
import app.resound.backup.*
import app.resound.data.*
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
import java.time.ZoneId
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Things079Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: CaptureDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var repo: RoomThingRepository
    private lateinit var root: File
    private val dao get() = db.things()
    private var clock = 1_800_000_000_000L
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries()
            .addCallback(SearchIndex.callback).addCallback(ThingSchema.callback).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repo = RoomThingRepository(db, scope, { error("offline") }, { clock }, { error(it) })
        root = File(context.cacheDir, "things072-${UUID.randomUUID()}").apply { mkdirs() }
    }
    @After fun cleanup() { scope.cancel(); db.close(); root.deleteRecursively() }
    private fun row(name: String = "钥匙", action: String = "Placed", place: String? = "鞋柜", at: Long = clock,
        thing: Long? = null, same: Long? = null, source: Long? = null) = ThingRecordRow(thingId = thing, name = name,
        action = action, place = place, who = if (action == "Lent") "老王" else null, owner = "Mine", at = at,
        sourceLineId = source, sourceText = "${name}在${place}", sameAsId = same, createdAt = clock)
    private suspend fun confirmed(name: String = "钥匙", place: String = "鞋柜"): Long {
        val id = dao.insertRecord(row(name, place = place)); repo.accept(id); repo.flush(); return dao.record(id)!!.thingId!!
    }
    private suspend fun line(id: Long, text: String = "钥匙搁鞋柜了", source: String = "mic", media: Boolean = false) {
        db.capture().segment(SegmentEntity(id, clock + id, clock + id + 1, source, "file$id", 0, "Done"))
        db.transcribe().insertLines(listOf(TranscriptLineEntity(id, id, clock + id, clock + id + 1, text, null, "我")))
        if (media) db.envSound().insert(app.resound.envsound.EnvSoundRow(id, clock + id, clock + id + 1, "file$id", "Media", null, "[]", "[]", "{}", false, false))
    }
    private suspend fun migration(version: Int) {
        val name = File(root, "from$version.db").path
        val schema = JSONObject(File("schemas/app.resound.data.CaptureDatabase/$version.json").readText()).getJSONObject("database")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(name, null).use { old ->
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { i ->
                val e = entities.getJSONObject(i)
                fun sql(s: String) = s.replace("\u0024{TABLE_NAME}", e.getString("tableName"))
                old.execSQL(sql(e.getString("createSql")))
                val indices = e.optJSONArray("indices")
                if (indices != null) repeat(indices.length()) { old.execSQL(sql(indices.getJSONObject(it).getString("createSql"))) }
            }
            val setup = schema.getJSONArray("setupQueries"); repeat(setup.length()) { old.execSQL(setup.getString(it)) }
            old.execSQL("INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status) VALUES(1,1,2,'mic','old',0,'Done')")
            old.version = version
        }
        val migrated = Room.databaseBuilder(context, CaptureDatabase::class.java, name).allowMainThreadQueries()
            .addMigrations(*CaptureDatabase.MIGRATIONS).build()
        try {
            assertEquals(CaptureDatabase.VERSION, migrated.openHelper.writableDatabase.version)
            assertEquals("old", migrated.transcribe().segment(1).path)
            val r = migrated.things().insertRecord(row())
            assertEquals("Pending", migrated.things().record(r)!!.status)
            migrated.transcribe().insertLines(listOf(TranscriptLineEntity(1, 1, 1, 2, "依据", null)))
            migrated.things().insertRecord(row(source = 1))
            migrated.privacy().deleteLine(1)
            assertEquals(1, migrated.things().allRecords().size)
        } finally { migrated.close() }
    }
    @Test fun migration26ToLatestPreservesRowsAndInstallsCleanup() = runBlocking { migration(26) }
    @Test fun migration1ToLatestUsesWholeRegisteredChain() = runBlocking { migration(1) }

    @Test fun pendingAcceptIgnoreAndBulkRemainSeparate() = runBlocking {
        val a = dao.insertRecord(row()); val b = dao.insertRecord(row("伞")); val c = dao.insertRecord(row("帽子"))
        assertTrue(repo.things().first().isEmpty())
        repo.ignore(c); repo.setAll(listOf(a,b),RecordStatus.Accepted); repo.flush()
        assertEquals(2, repo.things().first().size); assertEquals("Ignored", dao.record(c)!!.status)
        assertTrue(repo.pending().first().isEmpty())
        repo.accept(a); repo.flush(); assertEquals(2, dao.allThings().size)
    }
    @Test fun sameAsSupportsBothChoicesAndRenamedPendingMergesAlias() = runBlocking {
        val original = confirmed()
        val same = dao.insertRecord(row("车key", place = "餐桌", same = original))
        val other = dao.insertRecord(row("钥匙", place = "书柜", same = original, at = clock + 1))
        repo.accept(same); repo.accept(other, asNew = true); repo.flush()
        assertEquals(original, dao.record(same)!!.thingId); assertNotEquals(original, dao.record(other)!!.thingId)
        assertTrue(repo.thing(original).first()!!.aliases.contains("车key"))
        val rename = dao.insertRecord(row("未识别钥匙", place = "口袋"))
        repo.accept(rename, ThingEdit("车key",ThingAction.Placed,"口袋",null,ThingOwner.Mine)); repo.flush()
        assertEquals(original, dao.record(rename)!!.thingId)
    }
    @Test fun duplicatesUseCurrentLocationAndUpdatePendingWithinTwoHours() = runBlocking {
        val thing = confirmed()
        db.withTransaction {
            assertNull(repo.propose(ThingProposal(row(thing = thing),true)))
            val a = repo.propose(ThingProposal(row(place = "书柜",at = clock+1),true))!!
            val b = repo.propose(ThingProposal(row(place = "餐桌",at = clock+2),true))!!
            assertEquals(a.id,b.id); assertEquals("餐桌",dao.record(a.id)!!.place)
            assertNull(repo.propose(ThingProposal(row(place = "旧位置",at = clock+1),true)))
            assertEquals("餐桌",dao.record(a.id)!!.place)
            assertNotNull(repo.propose(ThingProposal(row(action = "TakenAway", place = null,thing = thing),true)))
        }
        repo.accept(repo.pending().first().first { it.action==ThingAction.TakenAway }.id); repo.flush()
        assertNotNull(db.withTransaction { repo.propose(ThingProposal(row(at = clock+3,thing = thing),true)) })
    }
    @Test fun failureWatermarkSurvivesRestartAndSkipsOnlyAfterThirdFailure() = runBlocking {
        line(1)
        fun watcher() = ThingWatcher(db,repo,{ _,_,_ -> error("offline") },now = { clock })
        assertTrue(watcher().runOnce()); assertEquals(0L,dao.state()!!.watermark)
        line(2)
        watcher().runOnce(); assertEquals(2,dao.state()!!.failures); assertEquals(1L,dao.state()!!.failedEnd)
        watcher().runOnce(); assertEquals(1L,dao.state()!!.watermark)
        assertEquals("ThingsSkipped",db.capture().events(0,Long.MAX_VALUE).first().single().type)
    }
    @Test fun firstStartSkipsOldHistoryButKeepsRecentLines() = runBlocking {
        line(1); line(2); line(3) // 开始时间是 clock+id
        val seen = mutableListOf<Long>()
        val watcher = ThingWatcher(db,repo,{ lines,_,_ -> seen += lines.map { it.id }; emptyList() },now = { clock })
        watcher.skipHistory(clock + 3); assertEquals(2L,dao.state()!!.watermark)
        watcher.skipHistory(clock + 1); assertEquals(2L,dao.state()!!.watermark) // 只进不退
        watcher.runOnce(); assertEquals(listOf(3L),seen)
    }
    @Test fun autoOffAdvancesAndToggleDuringRequestCannotInsertOldResults() = runBlocking {
        line(1); repo.setAutoDetect(false); repo.flush()
        line(2)
        val watcher = ThingWatcher(db,repo,{ _,_,_ -> error("should not call") })
        watcher.runOnce(); assertEquals(2L,dao.state()!!.watermark)
        line(3); repo.setAutoDetect(true); repo.flush(); assertEquals(3L,dao.state()!!.watermark)
        line(4)
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val inFlight = ThingWatcher(db,repo,{ lines,_,_ -> started.complete(Unit); finish.await()
            listOf(ThingProposal(row(at=lines[0].startAt,source=lines[0].id).copy(sourceText=lines[0].text),true)) })
        val job=async { inFlight.runOnce() }; started.await(); repo.setAutoDetect(false); repo.flush(); finish.complete(Unit); job.await()
        assertTrue(dao.allRecords().isEmpty()); assertEquals(4L,dao.state()!!.watermark)
    }
    @Test fun batchesHave80Lines20ContextExcludeImportMediaAndNotifyOnlyExplicit() = runBlocking {
        for (id in 1L..103L) line(id,source=if(id==2L) "import" else "mic",media=id==3L)
        val calls=mutableListOf<Pair<List<TranscriptLineEntity>,List<TranscriptLineEntity>>>(); val notices=mutableListOf<ThingRecordRow>()
        val watcher=ThingWatcher(db,repo,{ lines,previous,_ -> calls += lines to previous
            listOf(ThingProposal(row(name="物品${lines.first().id}",at=lines.first().startAt,source=lines.first().id)
                .copy(sourceText=lines.first().text,explicit=lines.first().id==1L),true)) },notices::add)
        assertTrue(watcher.runOnce()); assertFalse(watcher.runOnce())
        assertEquals(78,calls[0].first.size); assertEquals(23,calls[1].first.size); assertEquals(20,calls[1].second.size)
        assertEquals(1,notices.size); assertEquals(103L,dao.state()!!.watermark)
    }
    @Test fun deleteLineRedoAndEventDeletionRemovePendingButKeepAcceptedSnapshot() = runBlocking {
        line(1); line(2)
        val accepted=dao.insertRecord(row(source=1));repo.accept(accepted);repo.flush()
        dao.insertRecord(row("伞",source=1));db.privacy().deleteLine(1)
        assertNull(dao.record(accepted)!!.sourceLineId);assertNotNull(dao.record(accepted)!!.sourceText)
        assertTrue(repo.pending().first().isEmpty())
        dao.insertRecord(row(source=2))
        db.transcribe().completeRedo(db.transcribe().segment(2),listOf(TranscriptLineEntity(segmentId=2,startAt=clock+2,endAt=clock+3,text="重转写",speakerTag=null)),clock)
        assertTrue(repo.pending().first().isEmpty())
        db.memory().insertEvent(MemoryEventEntity(1,clock,clock+3,"独白","事件","","[]","[]",null,null,null,false,createdAt=clock,model="test"))
        db.openHelper.writableDatabase.execSQL("UPDATE transcript_lines SET eventId=1 WHERE id=2")
        val r=dao.insertRecord(row(source=2));repo.accept(r);repo.flush();dao.insertRecord(row("帽子",source=2))
        db.memory().deleteEvent(1)
        assertNull(dao.record(r)!!.sourceLineId);assertTrue(repo.pending().first().isEmpty())
    }
    @Test fun searchMatchesAliasesLastPlaceAndBorrowerWithLiteralLikeEscaping() = runBlocking {
        val id=confirmed("电钻","书柜%层")
        repo.rename(id,"冲击钻");repo.flush()
        val r=dao.insertRecord(row("冲击钻","Lent",null,clock+1,id));repo.accept(r);repo.flush()
        for(q in listOf("电钻","书柜%","老王")) assertEquals(id,repo.search(q).first().single().id)
        assertTrue(repo.search("' OR 1=1 --").first().isEmpty())
        assertEquals("书柜%层",repo.thing(id).first()!!.lastPlace)
    }
    @Test fun askToolIncludesUnconfirmedAndReferencesRealLineIds() = runBlocking {
        line(42)
        dao.insertRecord(row(source=42))
        val sources=mutableListOf<AskSource>()
        val result=AskTools(db,JSONObject(),ZoneId.of("Asia/Shanghai")).execute("find_things",JSONObject().put("place","鞋柜"),sources)
        assertTrue(result.toString().contains("未确认"));assertEquals("line",sources.single().kind);assertEquals(42L,sources.single().id)
        repo.accept(repo.pending().first().single().id);repo.flush()
        dao.insertRecord(row("钥匙",place="书柜",at=clock+1,thing=repo.things().first().single().id))
        val found=AskTools(db,JSONObject()).execute("find_things",JSONObject().put("query","钥匙"),mutableListOf())
        val item=found.getJSONArray("results").getJSONObject(0)
        assertEquals("鞋柜",item.getJSONObject("current").getString("place"));assertEquals("未确认",item.getJSONArray("pending").getJSONObject(0).getString("status"))
    }
    @Test fun manualFallbackAddReturnEditAndDeletionRespectContract() = runBlocking {
        val fallback=repo.draft("这是超过十二个字的原话用于降级处理")
        assertFalse(fallback.parsed);assertEquals(12,fallback.items.single().name.length);assertNull(fallback.items.single().place)
        repo.add(listOf(ThingDraft("电钻",ThingAction.Lent,null,"老王")),ThingOrigin.Voice,"电钻借老王了");repo.flush()
        val thing=repo.things().first().single();assertEquals(ThingOrigin.Voice,thing.latest.origin)
        clock+=1000;repo.markReturned(thing.id,"工具箱");repo.flush()
        assertEquals(ThingAction.Returned,repo.thing(thing.id).first()!!.latest.action)
        repo.editRecord(repo.thing(thing.id).first()!!.latest.id,ThingEdit("电钻",ThingAction.Placed,"书柜",null,ThingOwner.Family));repo.flush()
        assertEquals("书柜",repo.thing(thing.id).first()!!.lastPlace)
        repo.history(thing.id).first().forEach { repo.deleteRecord(it.id) };repo.flush()
        assertNull(repo.thing(thing.id).first());assertTrue(dao.allThings().isEmpty())
    }
    @Test fun dayVolumesRoundTripThingsRecordsAndWatermark() = runBlocking {
        val source=CaptureDatabase.open(context,File(root,"source.db").path)
        val snapshot=File(root,"snapshot.db")
        try {
            source.things().insert(ThingRow(1,"钥匙","[\"车key\"]","Mine",clock,clock))
            source.things().insertRecord(row(thing=1).copy(status="Accepted",acceptedAt=clock))
            source.things().state(ThingWatchState(watermark=123,enabled=false))
            BackupArchive.snapshot(source,snapshot)
        } finally { source.close() }
        val (version,rows)=DayVolumeRows.write(snapshot,File(root,"rows"))
        assertTrue(rows.getValue("state").readText().contains("things"));assertTrue(rows.getValue(DayVolumeRows.day(clock)).readText().contains("thing_records"))
        val target=File(root,"restored.db")
        DayVolumeRows.rebuild(context,target,version,rows.getValue("state"),rows.filterKeys { it!="state" }.values.toList())
        val restored=CaptureDatabase.open(context,target.path)
        try { assertEquals("钥匙",restored.things().allThings().single().name);assertEquals("Accepted",restored.things().allRecords().single().status);assertFalse(restored.things().state()!!.enabled) }
        finally { restored.close() }
    }
    @Test fun promptParsesUncertainKnownAndRejectsContextOnlyEvidence() = runBlocking {
        val line=TranscriptLineEntity(5,1,clock,clock+1,"钥匙在桌上",null)
        val content="""{"items":[{"name":"钥匙","aliases":["key"],"action":"Placed","place":"桌上","who":null,"owner":"Mine","lineId":5,"explicit":true,"knownId":7,"confident":false}]}"""
        val parsed=ThingPrompt.parse(content,listOf(line),setOf(7),clock).single()
        assertNull(parsed.row.thingId);assertEquals(7L,parsed.row.sameAsId)
        assertTrue(runCatching { ThingPrompt.parse(content.replace("\"lineId\":5","\"lineId\":4"),listOf(line),setOf(7),clock) }.isFailure)
        val prompts=app.resound.prompts.LocalPromptTemplates(context)
        assertTrue(prompts.list.value.any { it.id=="things" });assertFalse(ThingPrompt.system(prompts.text("things")).contains("{{CALENDAR}}"))
    }
    @Test fun completedRoundRequestsDetectionOnlyWhenNewLinesExist() = runBlocking {
        val file=File(root,"audio.ogg").apply { writeText("fixture") }
        db.capture().segment(SegmentEntity(1,clock,clock+1000,"mic",file.path,7))
        val detected=CompletableDeferred<Unit>(); var rounds=0
        val watcher=ThingWatcher(db,repo,{ _,_,_ -> detected.complete(Unit); emptyList() })
        watcher.start(scope,debounceMillis=20)
        val provider=object : AsrProvider {
            override suspend fun recognize(audio: AsrAudio,key: String,beforeAudio: suspend () -> Unit) =
                AsrResult(listOf(AsrSentence(0,1000,"钥匙放鞋柜了",null)),1000,null)
        }
        val runner=TranscribeRunner(db.transcribe(),provider,{ "test" },{ _,_ -> },now={ clock },
            afterRound={ rounds++;watcher.request() })
        runner.run(); withTimeout(2000) { detected.await() };runner.run()
        assertEquals(1,rounds)
    }

}
