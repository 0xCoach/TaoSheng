package app.resound.night
import android.app.Application
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import app.resound.data.*
import app.resound.backup.BackupArchive
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import app.resound.platform.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],manifest=Config.NONE,application=Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Night202AndroidTest {
    private val context get()=RuntimeEnvironment.getApplication()
    @Test fun migrates39ToCurrentAndMatchesFreshSchema()=runBlocking(Dispatchers.IO) {
        val file=File(context.cacheDir,"night39.db").apply {delete()}
        val schema=JSONObject(File("../shared/schemas/app.resound.data.CaptureDatabase/39.json").readText()).getJSONObject("database")
        AndroidSQLiteDriver().open(file.path).use { db->
            val tables=schema.getJSONArray("entities")
            repeat(tables.length()) {db.execSQL(tables.getJSONObject(it).getString("createSql").replace("\${TABLE_NAME}",tables.getJSONObject(it).getString("tableName")))}
            repeat(tables.length()) {i->val table=tables.getJSONObject(i);val indices=table.optJSONArray("indices") ?: org.json.JSONArray();repeat(indices.length()) {db.execSQL(indices.getJSONObject(it).getString("createSql").replace("\${TABLE_NAME}",table.getString("tableName")))}}
            db.userVersion=39
        }
        val db=CaptureDatabase.open(context,file.path)
        try {
            assertEquals(CaptureDatabase.VERSION,db.readSql("PRAGMA user_version") {it.step();it.getLong(0).toInt()})
            val id=db.night().insert(NightRow(date="2026-11-17",start=0,end=1,state="Ready",payload="{}",signals="{}",createdAt=0,updatedAt=0))
            db.night().insertClip(NightClipRow(nightId=id,start=0,end=1,path=null,scores=byteArrayOf(),features="{}",confirmed=false,expiresAt=1,candidate=false))
            db.night().delete(id);assertTrue(db.night().clips(id).isEmpty())
        } finally {db.close();file.delete()}
    }
    @Test fun rejectedAudioExpiresAfterSevenDaysAndConfirmedAudioSurvives() = runBlocking(Dispatchers.IO) {
        val db = CaptureDatabase.open(context, "night-retention.db")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val files = NightFiles(context)
        val privacy = app.resound.privacy.RoomPrivacyActions(db)
        val now = 1_000_000L
        val store = NightStore(db, scope, NightSettings(), {}, {}, { now }, { ZoneHistory(emptyList(),"UTC") },
            files::exists, { FileRef(files.cleanupPath(it)) }, { error("unused") },
            { assertTrue(privacy.retryFileCleanup()) }, {}, MutableStateFlow(emptyList()))
        try {
            val night = store.create(NightSession(0,0,0,now), LocalDate(2026,11,17))
            suspend fun sound(start: Long): Pair<Long, File> {
                val path = "$night/$start-test.ogg"
                val file = files.resolve(path).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1,2)) }
                val clip = db.night().insertClip(NightClipRow(nightId=night,start=start,end=start+1000,path=path,
                    scores=byteArrayOf(),features="{}",confirmed=false,expiresAt=1,candidate=false))
                return store.addEvent(night,NightEvent(0,NightEventKind.Snore,start,start+1000,clipId=clip)) to file
            }
            val (rejected, expiring) = sound(0)
            val (corrected, permanent) = sound(2000)
            store.feedback(rejected, Feedback.Yes)
            store.feedback(rejected, Feedback.No)
            store.feedback(corrected, Feedback.No)
            val deadline = now + 7L * 86_400_000
            store.expire(deadline - 1)
            assertTrue(expiring.isFile && permanent.isFile)
            assertTrue(store.detail(night)!!.events.first { it.id == rejected }.audioAvailable)
            assertTrue(db.privacy().pendingCleanup().isEmpty())
            store.feedback(corrected, Feedback.Yes)
            store.expire(deadline)
            assertFalse(expiring.exists())
            store.expire(Long.MAX_VALUE)
            assertTrue(permanent.isFile)
            assertEquals(mapOf(rejected to false, corrected to true), db.night().feedback().associate { it.eventId to it.yes })
            store.deleteNight(night)
            assertFalse(permanent.exists())
        } finally { scope.cancel(); db.close(); context.deleteDatabase("night-retention.db") }
    }
    @Test fun backupContainsOnlyConfirmedClipAndSnapshotDropsExpiredPaths()=runBlocking(Dispatchers.IO) {
        val db=CaptureDatabase.open(context,"night-backup.db")
        val dir=File(context.filesDir,"night/1").apply {mkdirs()}
        val root=File(context.cacheDir,"night-backup-check").apply {deleteRecursively();mkdirs()}
        try {
            val id=db.night().insert(NightRow(date="2026-11-17",start=0,end=1,state="Ready",payload="{}",signals="{}",createdAt=0,updatedAt=0))
            for((name,confirmed) in listOf("1-yes.ogg" to true,"2-no.ogg" to false)) {
                File(dir,name).writeBytes(byteArrayOf(1,2,3))
                db.night().insertClip(NightClipRow(nightId=id,start=0,end=1,path="1/$name",scores=byteArrayOf(),features="{}",confirmed=confirmed,expiresAt=0,candidate=false))
            }
            val zip=File(root,"backup.zip");BackupArchive.create(context,db,File(root,"stage"),zip,0)
            java.util.zip.ZipFile(zip).use { assertNotNull(it.getEntry("files/night/1/1-yes.ogg"));assertNull(it.getEntry("files/night/1/2-no.ogg")) }
        } finally {db.close();context.deleteDatabase("night-backup.db");dir.deleteRecursively();root.deleteRecursively()}
    }
    @Test fun compressedScoresAndEnvelopeAreLosslessAndBackupPathIsRestricted() {
        val scores=(0..526).associate {it.toString().padStart(3,'0') to it/527f}
        assertEquals(scores.toSortedMap().values.toList(),NightFiles.scores(NightFiles.scores(scores)))
        val bytes=byteArrayOf(-80,-60,-70);assertArrayEquals(bytes,NightFiles.unpack(NightFiles.envelope(bytes)))
        assertTrue(BackupArchive.allowed("files/night/1/123-abc.m4a"));assertFalse(BackupArchive.allowed("files/night/../other"))
    }
}
