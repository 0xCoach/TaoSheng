package app.resound.importer

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.room.Room
import app.resound.data.CaptureDatabase
import app.resound.data.testSql
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayInputStream
import java.io.File
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],manifest=Config.NONE,application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CallRecording199AndroidTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: CaptureDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var importer: AndroidAudioImporter
    private lateinit var docs: Documents
    private lateinit var folders: AndroidFolderImports
    private val tree=Uri.parse("content://calls/tree/root")
    private val now=1794902400000L
    private inner class Documents: FolderDocuments {
        val files=mutableListOf<FolderDocument>();var reads=0;var granted=true
        override fun acquire(tree: Uri) { check(granted) }
        override fun granted(tree: Uri)=granted
        override fun root(tree: Uri)=FolderDocument(tree,"通话录音",0,0,"vnd.android.document/directory")
        override fun files(tree: Uri)=files.toList()
        override fun stat(uri: Uri)=files.first { it.uri==uri }
        override fun fingerprint(document: FolderDocument)=audioFingerprint(ByteArrayInputStream(bytes(document)))
        override fun metadata(document: FolderDocument): AudioMetadata { reads++;return AudioMetadata(60_000,null) }
        fun bytes(file: FolderDocument)=ByteArray(100) { (file.name.hashCode()+it).toByte() }
        fun add(name: String, days: Int): FolderDocument {
            val file=FolderDocument(Uri.parse("content://calls/document/$name"),name,100,now-days*86400_000L)
            files+=file;shadowOf(context.contentResolver).registerInputStreamSupplier(file.uri) { ByteArrayInputStream(bytes(file)) };return file
        }
    }
    @Before fun setup() {
        listOf("folder-imports","imports").forEach { context.getSharedPreferences(it,0).edit().clear().commit() }
        File(context.cacheDir,"import").deleteRecursively()
        db=Room.inMemoryDatabaseBuilder(context,CaptureDatabase::class.java).setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).addCallback(ImportRecordSchema.callback).build()
        scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        docs=Documents()
        importer=AndroidAudioImporter(context,scope,inspect={ AudioMetadata(60_000,null) },enqueue={},fingerprints=db.folderImports())
        folders=AndroidFolderImports(context,db,scope,importer,docs,{now},{_,_,_->},{})
    }
    @After fun close()=runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin();db.close();File(context.cacheDir,"import").deleteRecursively();Unit }
    @Test fun previewThreeHundredFilesCachedAndAddReusesMetadata()=runBlocking {
        repeat(300) { docs.add("录音$it.wav",1+it%40) }
        val begin=System.nanoTime();val preview=folders.preview(tree)
        assertEquals(300,preview.total);assertTrue((System.nanoTime()-begin)/1_000_000 < 10_000)
        val reads=docs.reads;folders.preview(tree);assertEquals(reads,docs.reads)
        folders.addHistory(tree,ImportKind.Normal,FolderHistory.ListAll)
        assertEquals(reads,docs.reads);assertEquals(300,db.folderRecordings().all().size)
        assertNull(importer.next())
    }
    @Test fun onlyNewAndListAllNeverQueueAndNonCallHasNoParty()=runBlocking {
        docs.add("张三13800138000.wav",1)
        folders.addHistory(tree,ImportKind.Normal,FolderHistory.OnlyNew)
        assertTrue(db.folderRecordings().all().isEmpty());assertNull(importer.next())
        folders.addHistory(tree,ImportKind.Normal,FolderHistory.ListAll)
        val row=db.folderRecordings().all().single();assertEquals("Listed",row.state);assertNull(row.number);assertNull(row.partyName)
    }
    @Test fun sevenDayAndThirtyDayWindowsQueueOnlyRecent()=runBlocking {
        val files=listOf(1,10,40).map { days -> FolderMediaSnapshot("$days","$days.wav",1,now-days*86400_000L,1000,null,CallFileInfo(null,null,now-days*86400_000L,null,CallTimeSource.FileClock)) }
        val store=FolderHistoryStore(db.folderRecordings())
        store.history("7",ImportKind.Call,files,FolderHistory.Days7,now)
        store.history("30",ImportKind.Call,files,FolderHistory.Days30,now)
        assertEquals(1,db.folderRecordings().all().count { it.folderId=="7" && it.state=="Queued" })
        assertEquals(2,db.folderRecordings().all().count { it.folderId=="30" && it.state=="Queued" })
        assertEquals(3,db.folderRecordings().all().count { it.state=="Listed" })
    }
    @Test fun legacyPublicAddTrueMapsToThirtyDays()=runBlocking {
        docs.add("录音.wav",10);folders.add(tree,ImportKind.Normal,true)
        withTimeout(5000) { folders.state.first { it.folders.firstOrNull()?.history==FolderHistory.Days30 } }
        withTimeout(5000) { while (db.folderRecordings().all().isEmpty()) delay(10) }
        assertEquals("Queued",db.folderRecordings().all().single().state)
    }
    @Test fun pauseSurvivesRestartAndManualRequestPassesIt()=runBlocking {
        docs.add("录音.wav",1);folders.recordings.pauseBackfill(true)
        folders.addHistory(tree,ImportKind.Normal,FolderHistory.Days7);assertNull(importer.next())
        val restart=AndroidFolderRecordings(context,db,scope,importer,docs,{now},folders.state,{},{_,_,_->})
        assertTrue(restart.backfill.value.paused)
        FolderHistoryStore(db.folderRecordings()).transcribe(db.folderRecordings().all().map { it.id })
        restart.pump();assertFalse(importer.next()!!.backfill)
    }
    @Test fun backfillOneAtATimeAndOneWholeRoundNotification()=runBlocking {
        val messages=mutableListOf<Int>()
        docs.add("a.wav",1);docs.add("b.wav",2)
        folders.recordings.pauseBackfill(true);folders.addHistory(tree,ImportKind.Normal,FolderHistory.Days7)
        val records=AndroidFolderRecordings(context,db,scope,importer,docs,{now},folders.state,{},{_,_,n->messages+=n})
        records.pauseBackfill(false)
        repeat(2) { index ->
            records.pump();val entry=requireNotNull(importer.next());assertTrue(entry.backfill)
            assertEquals(1,importer.progress.value.queued)
            val id=db.imports().insert(ImportRecordRow(pendingId=entry.pending.id,fileName="录音",kind="Normal",startAt=now-86400_000,endAt=now,byChannel=false,status="Done",ingestComplete=true))
            db.folderRecordings().attached(entry.pending.id,id);importer.finish(entry.pending.id,null)
            assertTrue(messages.isEmpty())
        }
        records.pump();records.pump();assertEquals(listOf(2),messages)
    }
    @Test fun deletedImportReturnsToListedAndDoesNotRequeue()=runBlocking {
        docs.add("录音.wav",1);folders.addHistory(tree,ImportKind.Normal,FolderHistory.Days7)
        val entry=importer.next()!!
        val id=db.imports().insert(ImportRecordRow(pendingId=entry.pending.id,fileName="录音",kind="Normal",startAt=1,endAt=2,byChannel=false,status="Done"))
        db.folderRecordings().attached(entry.pending.id,id);importer.finish(entry.pending.id,null);db.imports().delete(id)
        assertEquals("Listed",db.folderRecordings().all().single().state);assertNull(db.folderRecordings().all().single().importRecordId)
        folders.scan();assertNull(importer.next())
    }
    @Test fun labelsSearchSuffixAndPermissionRestore()=runBlocking {
        docs.add("张三(13800138000).wav",1);folders.addHistory(tree,ImportKind.Call,FolderHistory.ListAll)
        folders.recordings.setLabel("+86 138 0013 8000","张总")
        withTimeout(5000) { folders.recordings.labels.first { it["13800138000"]=="张总" } }
        val result=withTimeout(5000) { folders.recordings.search("8000").first { it.isNotEmpty() } }
        assertEquals("张总",result.single().call!!.display)
        assertTrue(folders.recordings.search("800").first().isEmpty())
        assertEquals(1,folders.recordings.search("张总").first().size)
        docs.granted=false;folders.scan()
        assertEquals(ListedState.Missing,folders.recordings.search("张总").first().single().state)
        docs.granted=true;folders.addHistory(tree,ImportKind.Call,FolderHistory.ListAll)
        assertEquals(ListedState.Listed,folders.recordings.search("张总").first().single().state)
    }
    @Test fun newFileHasPriorityOverQueuedBackfill()=runBlocking {
        docs.add("old.wav",1);folders.addHistory(tree,ImportKind.Normal,FolderHistory.Days7)
        assertTrue(importer.next()!!.backfill)
        docs.add("new.wav",2);folders.scan();folders.scan()
        assertEquals("new.wav",importer.next()!!.pending.info.displayName)
        assertFalse(importer.next()!!.backfill)
    }
    @Test fun summaryRequestUsesOnlyNameAndNeverRawFilenameNumber()=runBlocking {
        val file="src/main/assets/skills/import_call"
        val template=app.resound.skills.SkillJson.parse(File(file,"skill.json").readText(),app.resound.skills.SkillOrigin.Builtin).skill to File(file,"prompt.md").readText()
        for (label in listOf(null,"张总")) {
            var captured: String?=null
            val summary=ImportSummary(template={template},request={ _,user -> captured=user;throw IllegalStateException("captured") },callName={label})
            val row=ImportRecordRow(pendingId="test",fileName="13800138000_20261117153012.mp3",kind="Call",startAt=now-1000,endAt=now,byChannel=false)
            val line=app.resound.data.TranscriptLineEntity(id=1,segmentId=1,startAt=now-1000,endAt=now,text="明天再谈",speakerTag="1")
            val error=runCatching { summary.generate(row,listOf(line)) }.exceptionOrNull()
            assertEquals("captured",error?.message);assertFalse(captured!!.contains("13800138000"));assertTrue(captured!!.contains(label ?: "对方"))
        }
    }
    @Test fun displayFlowAndFreshSnapshotFollowLabelsWithoutWritingIdentity()=runBlocking {
        val record=db.imports().insert(ImportRecordRow(pendingId="display",fileName="call",kind="Call",startAt=1,endAt=3000,byChannel=false))
        db.folderRecordings().insert(FolderRecordingRow(folderId="f",documentUri="u",fileName="call",size=1,modified=1,startAt=1,durationMillis=3000,
            number="13800138000",state="Imported",importRecordId=record,createdAt=1,kind="Call",backfill=true))
        db.capture().segment(app.resound.data.SegmentEntity(id=1,startAt=1,endAt=3000,sourceId="import",path="/unused",sizeBytes=1,status="Done",importRecordId=record))
        db.transcribe().insertLines(listOf(
            app.resound.data.TranscriptLineEntity(id=1,segmentId=1,startAt=1,endAt=1000,text="你好",speakerTag="call:0",speakerName="我",speakerPersonId=0),
            app.resound.data.TranscriptLineEntity(id=2,segmentId=1,startAt=1001,endAt=3000,text="你好",speakerTag="call:1")))
        val resolver=CallDisplayNames(db.folderRecordings())
        assertEquals("13800138000",resolver.snapshot()[2L])
        val changed=async { withTimeout(5000) { resolver.names.first { it[2L]=="我" } } }
        db.folderRecordings().label(PhoneLabelRow("13800138000","我",now))
        assertEquals("我",changed.await()[2L]);assertEquals("我",resolver.snapshot()[2L])
        assertNull(db.imports().lines(record).last().speakerName);assertNull(db.imports().lines(record).last().speakerScore)
        assertEquals(setOf(1L,2L),db.folderRecordings().quietLines(now).toSet())
    }
    @Test fun migration38ToCurrentPreservesOldDataAndAddsTables()=runBlocking {
        val name="call199-migration.db";context.deleteDatabase(name)
        val path=context.getDatabasePath(name);path.parentFile!!.mkdirs()
        val schema=JSONObject(File("../shared/schemas/app.resound.data.CaptureDatabase/38.json").readText()).getJSONObject("database")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path,null).use { old ->
            val entities=schema.getJSONArray("entities")
            repeat(entities.length()) { val e=entities.getJSONObject(it);old.execSQL(e.getString("createSql").replace("\u0024{TABLE_NAME}",e.getString("tableName")))
                val indices=e.optJSONArray("indices") ?: org.json.JSONArray();repeat(indices.length()) { n -> old.execSQL(indices.getJSONObject(n).getString("createSql").replace("\u0024{TABLE_NAME}",e.getString("tableName"))) } }
            val setup=schema.getJSONArray("setupQueries");repeat(setup.length()) { old.execSQL(setup.getString(it)) }
            old.execSQL("INSERT INTO import_fingerprints VALUES ('keep',123)");old.version=38
        }
        val migrated=Room.databaseBuilder(context,CaptureDatabase::class.java,name).setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).addMigrations(*CaptureDatabase.MIGRATIONS).build()
        try { assertTrue(migrated.folderImports().contains("keep"));assertTrue(migrated.folderRecordings().all().isEmpty());assertEquals(CaptureDatabase.VERSION,migrated.testSql.version)
            migrated.folderRecordings().label(PhoneLabelRow("10010","联通",1));assertEquals("联通",migrated.folderRecordings().labelSnapshot().single().name)
        } finally { migrated.close();context.deleteDatabase(name) }
    }
}
