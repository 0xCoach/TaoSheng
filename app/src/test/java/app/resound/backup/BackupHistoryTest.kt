package app.resound.backup

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import app.resound.data.CaptureDatabase
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],manifest=Config.NONE,application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BackupHistoryTest {
    private lateinit var context: Context
    private lateinit var db: CaptureDatabase
    private lateinit var root: File
    private lateinit var remote: File
    private lateinit var disk: DirectoryVolumeStore
    private lateinit var store: VolumeStore
    private var time = LocalDate.parse("2026-10-24").atStartOfDay(DayVolumeRows.zone).toInstant().toEpochMilli()
    private val writes = mutableListOf<String>()
    private var fail = ""
    private fun work() = File(root,UUID.randomUUID().toString()).apply { mkdirs() }
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        root=File(context.cacheDir,"history-tests").apply { deleteRecursively();mkdirs() }
        remote=File(root,"remote").apply { mkdirs() };disk=DirectoryVolumeStore(remote)
        (BackupSettings.prefs+BackupSettings.stores.keys+"backup-encryption-secrets").forEach { context.getSharedPreferences(it,0).edit().clear().commit() }
        File(context.applicationInfo.dataDir,BackupArchive.FEATURES).deleteRecursively()
        File(context.noBackupFilesDir,"backup-restore").deleteRecursively()
        context.deleteDatabase("capture.db");db=CaptureDatabase.open(context);db.openHelper.writableDatabase
        store=object:VolumeStore by disk {
            override fun put(path:String,source:File) {
                if(fail=="point" && historyPath(path) || fail=="object" && objectPath(path))throw BackupFailure("模拟中断",true)
                writes+=path;disk.put(path,source)
            }
        }
        add(1,time,"before");add(2,time-86400000L,"unchanged")
    }
    @After fun cleanup() { db.close();root.deleteRecursively() }
    private fun add(id:Long,at:Long,title:String) = db.openHelper.writableDatabase.execSQL(
        "INSERT INTO ask_conversations(id,createdAt,title) VALUES(?,?,?)",arrayOf(id,at,title))
    private fun edit(title:String) = db.openHelper.writableDatabase.execSQL("UPDATE ask_conversations SET title=? WHERE id=1",arrayOf(title))
    private fun sync(keep:Int=7,pass:String?=null):BackupSummary {
        time+=1000
        return HistoryVolumes.sync(context,db,store,work(),pass,keep,time)
    }
    private fun pointRefs(name:String):Map<String,String> {
        val array=JSONObject(File(remote,name).readText()).getJSONArray("refs")
        return (0 until array.length()).associate { array.getJSONObject(it).let { obj -> obj.getString("logical") to obj.getString("path") } }
    }
    private fun restored(name:String,pass:String?=null):File {
        val workspace=work();val verified=File(workspace,"verified")
        DayVolumes.restore(context,HistoryVolumes.selected(store,name,workspace),workspace,verified,pass,null)
        return verified
    }
    private fun title(verified:File):String {
        val restored=CaptureDatabase.open(context,File(verified,BackupArchive.DATABASE).path)
        return try { restored.openHelper.writableDatabase.query("SELECT title FROM ask_conversations WHERE id=1").use { assertTrue(it.moveToFirst());it.getString(0) } }
        finally { restored.close() }
    }

    @Test fun changedDayIsIncrementalAndEachPointRestoresItsOwnDataAndSettings() {
        context.getSharedPreferences("ui",0).edit().putString("theme","old").commit()
        sync();val old=HistoryVolumes.list(store).single();val oldRefs=pointRefs(old.name)
        edit("after");context.getSharedPreferences("ui",0).edit().putString("theme","new").commit()
        writes.clear();val result=sync();val points=HistoryVolumes.list(store)
        assertEquals(2,points.size);assertEquals(2,writes.count(::objectPath)) // 变化的那天 + 当前配置
        assertEquals(oldRefs["days/resound-day-2026-10-23.zip"],pointRefs(points.first().name)["days/resound-day-2026-10-23.zip"])
        val saved=restored(old.name);assertEquals("before",title(saved));assertEquals("after",title(restored(points.first().name)))
        assertEquals("old",JSONObject(File(saved,"settings.json").readText()).getJSONObject("ui").getJSONObject("theme").getString("value"))
        assertEquals(result.lastUploadBytes,HistoryVolumes.summary(store,work(),null)!!.lastUploadBytes)
        writes.clear();sync();assertEquals(1,writes.count(::objectPath)) // 无变化只写状态
    }

    @Test fun interruptedObjectsAndCheckpointNeverChangeCommittedHistoryAndRetryWorks() {
        sync();val old=HistoryVolumes.list(store).single()
        val before=File(remote,old.name).readBytes();edit("after")
        for(stage in listOf("object","point","point")) {
            fail=stage;assertTrue(runCatching { sync() }.isFailure)
            assertEquals(listOf(old),HistoryVolumes.list(store));assertArrayEquals(before,File(remote,old.name).readBytes())
            assertEquals("before",title(restored(old.name)))
        }
        fail="";sync();assertEquals("after",title(restored(HistoryVolumes.list(store).first().name)))
    }

    @Test fun retentionPrunesPointsAndUnreferencedOldObjectsButKeepsSharedData() {
        sync();val shared=pointRefs(HistoryVolumes.list(store).first().name)["days/resound-day-2026-10-23.zip"]!!
        repeat(5) { edit("version-$it");sync() }
        assertEquals(6,HistoryVolumes.list(store).size)
        File(remote,"objects").listFiles()!!.forEach { check(it.setLastModified(time-2*86400000L)) }
        edit("latest");sync(keep=3)
        val retained=HistoryVolumes.list(store);assertEquals(3,retained.size)
        assertEquals(listOf("latest","version-4","version-3"),retained.map { title(restored(it.name)) })
        val referenced=retained.flatMap { pointRefs(it.name).values }.toSet()
        assertEquals(referenced,store.files("objects").map { it.path }.toSet())
        assertTrue(File(remote,shared).exists())
    }

    @Test fun encryptedVersionsSurvivePassphraseChangeAndCorruptionNeverStagesRestore() {
        sync(pass="old-passphrase");val old=HistoryVolumes.list(store).single()
        edit("new-encrypted");sync(pass="new-passphrase");val newest=HistoryVolumes.list(store).first()
        assertEquals("before",title(restored(old.name,"old-passphrase")))
        assertTrue(runCatching { restored(old.name,"new-passphrase") }.isFailure)
        assertEquals("new-encrypted",title(restored(newest.name,"new-passphrase")))
        val data=pointRefs(old.name).getValue("days/resound-day-2026-10-24.zip.enc")
        File(remote,data).appendText("corrupt")
        assertTrue(runCatching { BackupRestore.restoreVolumes(context,store,db,"old-passphrase",null,db::close,{fail("must not restart")},old.name) }.isFailure)
        assertTrue(db.isOpen);assertFalse(File(context.noBackupFilesDir,"backup-restore").exists())
    }

    @Test fun cleanupFailureLeavesBothPublishedPointsRestorableUntilNextRetry() {
        sync();val old=HistoryVolumes.list(store).single()
        store=object:VolumeStore by disk {
            override fun delete(path:String) { if(path==old.name)throw BackupFailure("模拟删除失败",true);disk.delete(path) }
        }
        edit("newest")
        assertTrue(runCatching { sync(keep=1) }.isFailure)
        assertEquals(2,HistoryVolumes.list(store).size)
        assertEquals("before",title(restored(old.name)))
        assertEquals("newest",title(restored(HistoryVolumes.list(store).first().name)))
        store=disk;sync(keep=1)
        assertEquals(1,HistoryVolumes.list(store).size)
        assertEquals("newest",title(restored(HistoryVolumes.list(store).single().name)))
    }

    @Test fun migratesCurrentVolumesAndDeletesOnlyRecognizedLegacyAfterCommit() {
        DayVolumes.sync(context,db,disk,work(),null,time)
        val english=File(remote,"resound-2026-01-01-0310.zip").apply { writeText("old") }
        val chinese=File(remote,"回响-2026-01-01-0310.zip").apply { writeText("old") }
        val unrelated=File(remote,"notes.zip").apply { writeText("keep") }
        fail="point";assertTrue(runCatching { sync() }.isFailure)
        assertTrue(english.exists());assertTrue(chinese.exists())
        val legacySaved=File(work(),"legacy")
        DayVolumes.restore(context,disk,work(),legacySaved,null,null);assertEquals("before",title(legacySaved))
        fail="";sync();assertFalse(english.exists());assertFalse(chinese.exists());assertTrue(unrelated.exists())
        assertEquals("before",title(restored(HistoryVolumes.list(store).single().name)))
    }

    @Test fun safRepositoryListsAndRestoresSelectedVersionWithDefaultSeven() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context,Configuration.Builder().build())
        val files=LocalBackupFiles(context,{DocumentFile.fromFile(remote)},{})
        val passphrases=object:BackupPassphrases { override fun has()=false;override fun read()="";override fun write(value:String?)=Unit }
        var restarted=false
        val repo=AndroidBackupRepository(context,db,passphrases=passphrases,localFiles=files,restartProcess={restarted=true})
        try {
            assertEquals(7,repo.local.value.keep)
            repo.setLocalFolder(Uri.parse("content://test/history"));repo.runLocalBackup(false)
            val first=repo.localBackups().getOrThrow().single()
            edit("second");repo.runLocalBackup(false)
            assertEquals(2,repo.localBackups().getOrThrow().size)
            assertTrue(repo.restoreLocal(first.name).isSuccess);assertTrue(restarted)
            BackupRestore.applyPending(context)
            db=CaptureDatabase.open(context)
            db.openHelper.writableDatabase.query("SELECT title FROM ask_conversations WHERE id=1").use { it.moveToFirst();assertEquals("before",it.getString(0)) }
        } finally { BackupScheduler(context).cancel() }
    }

    @Test fun webDavImmutableObjectsAndCheckpointListingRestoreBothVersions() {
        val objects=mutableMapOf<String,ByteArray>()
        val server=MockWebServer()
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(r:RecordedRequest):MockResponse = synchronized(objects) {
                val path=r.requestUrl!!.encodedPath
                when(r.method) {
                    "PROPFIND" -> {
                        val prefix=path.trimEnd('/')+"/"
                        val rows=objects.filterKeys { it.startsWith(prefix) && '/' !in it.removePrefix(prefix) }.map { (key,value) ->
                            "<d:response><d:href>$key</d:href><d:propstat><d:prop><d:getcontentlength>${value.size}</d:getcontentlength><d:getlastmodified>Sat, 24 Oct 2026 00:00:00 GMT</d:getlastmodified></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
                        }.joinToString("")
                        MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d='DAV:'>$rows</d:multistatus>")
                    }
                    "GET" -> objects[path]?.let { MockResponse().setBody(okio.Buffer().write(it)) } ?: MockResponse().setResponseCode(404)
                    "HEAD" -> MockResponse().setResponseCode(if(path in objects)200 else 404)
                    "PUT" -> { objects[path]=r.body.readByteArray();MockResponse().setResponseCode(201) }
                    "MOVE" -> { objects[java.net.URI(r.getHeader("Destination")!!).rawPath]=objects.remove(path)!!;MockResponse().setResponseCode(201) }
                    "DELETE" -> { objects.remove(path);MockResponse().setResponseCode(204) }
                    else -> MockResponse().setResponseCode(405)
                }
            }
        }
        server.start()
        try {
            store=WebDavClient(BackupConfig(server.url("/dav/").toString()),"Resound","test","synthetic").volumes()
            sync();val first=HistoryVolumes.list(store).single()
            edit("cloud-second");sync();val points=HistoryVolumes.list(store)
            assertEquals(2,points.size);assertEquals("before",title(restored(first.name)))
            assertEquals("cloud-second",title(restored(points.first().name)))
            assertFalse(objects.keys.any { it.endsWith(".part") })
        } finally { server.shutdown() }
    }
}
