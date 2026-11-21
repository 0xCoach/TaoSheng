package app.resound.importer

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import app.resound.data.CaptureDatabase
import app.resound.data.SegmentEntity
import app.resound.diagnostics.DatabaseQueryStats
import app.resound.diagnostics.QueryStatsDriver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class FolderLoad209Test {
    @Test fun oneDayOfMicrophoneWrites() = runTest {
        val context: Context = RuntimeEnvironment.getApplication()
        val stats = DatabaseQueryStats { 0 }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
            .allowMainThreadQueries().setQueryCoroutineContext(dispatcher)
            .setDriver(QueryStatsDriver(AndroidSQLiteDriver(), stats)).build()
        try {
            val tree = WatchedFolder("calls", "通话录音", ImportKind.Call, true, true, null, 40)
            val folders = MutableStateFlow(FolderImportState(listOf(tree), false))
            val importer = AndroidAudioImporter(context, owner, enqueue = {})
            var scans = 0
            repeat(40) { i ->
                val name = "录音$i"
                val imported = db.imports().insert(ImportRecordRow(pendingId=name, fileName=name, kind="Call",
                    startAt=0, endAt=1000, byChannel=false, status=if (i == 0) "Transcribing" else "Done"))
                db.capture().segment(SegmentEntity(startAt=0, endAt=1000, sourceId="import",
                    path=name, sizeBytes=10, importRecordId=imported, status="Done"))
                db.folderRecordings().insert(FolderRecordingRow(folderId=tree.id, documentUri=name, fileName=name,
                    size=10, modified=0, startAt=0, durationMillis=1000, createdAt=0, kind="Call",
                    state="Imported", importRecordId=imported, backfill=true))
            }
            lateinit var recordings: AndroidFolderRecordings
            recordings = AndroidFolderRecordings(context, db, owner, importer, AndroidFolderDocuments(context),
                { testScheduler.currentTime }, folders, { scans++ }, completed = { _, _, _ -> },
                resumeQueue = { owner.launch { recordings.pump() } })
            recordings.start()
            owner.launch { db.imports().observeSummaryReady().collect() }
            owner.launch { db.transcribe().observeQueueCounts().collect() }
            owner.launch { db.transcribe().observeVoiceprintCounts().collect() }
            runCurrent()
            val initial = stats.current().queries
            val initialScans = scans
            assertEquals(BackfillState(1, 40, false), recordings.backfill.value)
            repeat(300) { i ->
                advanceTimeBy(288_000)
                val at = testScheduler.currentTime
                db.capture().segment(SegmentEntity(startAt=at, endAt=at+1000, sourceId="mic", path="mic/$i", sizeBytes=10))
                runCurrent()
            }
            stats.current().queries.forEach { (source, q) ->
                val old = initial[source] ?: DatabaseQueryStats.Query()
                println("209LOAD $source calls=${q.count-old.count} rows=${q.rows-old.rows} ms=${(q.nanos-old.nanos)/1_000_000}")
            }
            for (source in listOf("FolderRecordingDao.all", "FolderRecordingDao.observe", "FolderRecordingDao.roundCounts",
                "ImportRecordDao.observe", "ImportRecordDao.关联行")) {
                assertEquals(source, initial[source]?.count ?: 0L, stats.current().queries[source]?.count ?: 0L)
            }
            assertEquals(initialScans, scans)
            println("209LOAD scans=${scans-initialScans} startupScans=$initialScans initial=$initial")
            assertEquals(BackfillState(1, 40, false), recordings.backfill.value)
            val first = requireNotNull(db.imports().get(1))
            db.imports().update(first.copy(status = "Done"))
            runCurrent()
            assertEquals(BackfillState(0, 0, false), recordings.backfill.value)
            assertEquals(initialScans + 1, scans)
            folders.value = FolderImportState(emptyList(), false)
            runCurrent()
            val stopped = stats.current().queries["FolderRecordingDao.roundCounts"]
            db.imports().update(first)
            runCurrent()
            assertEquals(stopped, stats.current().queries["FolderRecordingDao.roundCounts"])
        } finally { owner.cancel(); runCurrent(); db.close() }
    }
}
