package app.resound.devices

import android.app.Application
import androidx.room.Room
import app.resound.data.CaptureDatabase
import app.resound.devices.driver.ClockPort
import app.resound.diagnostics.FailureNotes
import app.resound.privacy.FileCleanupPending
import app.resound.privacy.RoomPrivacyActions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],manifest=Config.NONE,application=Application::class)
class DeviceArchivePrivacyTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: CaptureDatabase
    private val root get() = File(context.filesDir,"device-archives")
    private val ledger get() = File(context.noBackupFilesDir,"device-archive-privacy.json")
    private val clock = object : ClockPort {
        override fun nowEpochMillis() = 10000L
        override fun monotonicMillis() = 10000L
        override fun zoneOffsetMinutes() = 0
    }
    @Before fun setup() {
        root.deleteRecursively();root.mkdirs();ledger.delete()
        File(context.filesDir,"device-sync").deleteRecursively()
        db = Room.inMemoryDatabaseBuilder(context,CaptureDatabase::class.java)
            .setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).allowMainThreadQueries().build()
    }
    @After fun close() { db.close();root.deleteRecursively();ledger.delete() }
    private fun repo() = DeviceArchiveRepository(context,db,clock,{null},{_,_,_->false},{_,_->})
    private fun seed(letter: Char,start: Long,hash: String = letter.toString().repeat(64)): DeviceArchiveRecord {
        val row = DeviceArchiveRecord(letter.toString().repeat(64),"driver","stable","file-$letter",start,1000,4000,
            3,"f".repeat(64),3,hash,"Ogg",16000,4000,"installation",protected=true,localDeviceHandle="handle")
        File(root,"${row.id}.json").writeText(Json.encodeToString(row))
        File(root,"$hash.audio").writeText("pcm")
        return row
    }
    @Test fun rangeDeletesArchiveOnlyAndSharedContentWithoutErasingOtherDayOrIndependentBackup() = runBlocking {
        val selected = seed('a',500)
        val shared = seed('b',8000,selected.archiveSha256)
        val kept = seed('c',3000)
        val archive = repo()
        val remote = File(context.cacheDir,"independent-backup.audio").apply { writeText("pcm") }
        val source = DeviceFile(selected.fileId,"",selected.sourceBytes,selected.startedAtMillis,selected.durationMillis,FileFormat.Opus)
        val cache = File(context.filesDir,"device-sync/downloads/${deviceSourceKey("handle",source)}")
        cache.parentFile!!.mkdirs();cache.writeText("pcm")
        val key = deviceTransferKey("handle",source,0,requireNotNull(source.durationMillis))
        val legacy = File(cache.parentFile,key).apply { writeText("pcm") }
        db.deviceSync().save(DeviceTransfer(key,"handle",source.id,source.name,source.bytes,source.startedAt,
            source.durationMillis,source.format.name,0,1000,500,imported=true))
        val actions = RoomPrivacyActions(db,now={10000},archiveFiles=archive::withPrivacyDeletion)
        assertEquals(0,actions.deleteRange(1000,1999).segments)
        assertFalse(archive.audio(selected).exists());assertFalse(cache.exists());assertFalse(legacy.exists())
        assertFalse(File(root,"${selected.id}.json").exists());assertFalse(File(root,"${shared.id}.json").exists())
        assertTrue(archive.audio(kept).isFile);assertEquals(listOf(kept.id),archive.records.value.map { it.id })
        assertEquals("pcm",remote.readText());assertTrue(db.privacy().pendingCleanup().isEmpty())
        assertFalse(repo().allowsSource(source));assertTrue(repo().allowsSource(source.copy(startedAt=20000)))
        assertTrue(remote.delete())
    }
    @Test fun allDeletionLogsCorruptManifestAndRetriesFailedLocalFileAfterRecreation() = runBlocking {
        val row = seed('a',50000) // A future device clock must not escape full local deletion.
        val broken = File(root,"${"e".repeat(64)}.json").apply { writeText("private-invalid-json") }
        val orphan = File(root,"${"d".repeat(64)}.audio").apply { writeText("orphan") }
        val archive = repo()
        assertTrue(FailureNotes.dump().contains("DeviceArchiveRepository:manifest"))
        assertFalse(FailureNotes.dump().contains("private-invalid-json"))
        val audio = archive.audio(row);audio.delete();audio.mkdirs()
        val obstruction = File(audio,"blocked").apply { writeText("synthetic") }
        val actions = RoomPrivacyActions(db,now={10000},archiveFiles=archive::withPrivacyDeletion)
        assertTrue(runCatching { actions.deleteAll() }.exceptionOrNull() is FileCleanupPending)
        assertFalse(broken.exists());assertFalse(orphan.exists())
        assertTrue(db.privacy().pendingCleanup().any { it.path == audio.path })
        assertTrue(repo().records.value.isEmpty())
        obstruction.delete()
        assertTrue(RoomPrivacyActions(db).retryFileCleanup());assertFalse(audio.exists())
        assertTrue(db.privacy().pendingCleanup().isEmpty())
    }
    @Test fun futureClockOriginalCannotRecreateOrphanAudioAfterFullDeletion() = runBlocking {
        androidx.work.WorkManager.initialize(context,androidx.work.Configuration.Builder().build())
        val archive = repo()
        archive.configure("stable",DeviceArchiveOptions(archiveEnabled=true))
        val raw = File(context.cacheDir,"future-privacy-source.pcm").apply { writeBytes(ByteArray(32000)) }
        val source = DeviceFile("future-clock","",raw.length(),50000,1000,FileFormat.Pcm16)
        val hash = DeviceSourceCache.sha256(raw)
        archive.archive("driver","stable","handle",source,raw,hash)
        val original = archive.records.value.single()
        RoomPrivacyActions(db,now={10000},archiveFiles=archive::withPrivacyDeletion).deleteAll()
        archive.archive("driver","stable","handle",source,raw,hash)
        assertTrue(archive.records.value.isEmpty());assertFalse(archive.audio(original).exists())
        assertTrue(raw.delete())
    }
}
