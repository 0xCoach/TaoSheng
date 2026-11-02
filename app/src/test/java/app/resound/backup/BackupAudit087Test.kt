package app.resound.backup

import android.app.Application
import app.resound.data.CaptureDatabase
import app.resound.data.SegmentEntity
import app.resound.envsound.EnvSoundRow
import app.resound.transcribe.AndroidAsrSpeedSettings
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.UUID

/** 087 审计问题的修复回归。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BackupAudit087Test {
    @Test fun everySchemaTableHasAnExplicitBackupPolicy() {
        val entities = org.json.JSONObject(File("schemas/app.resound.data.CaptureDatabase/${CaptureDatabase.VERSION}.json").readText())
            .getJSONObject("database").getJSONArray("entities")
        val actual = (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }.toSet()
        val rebuildableOrLocalOnly = setOf("lines_fts", "events_fts", "items_fts", "embeddings",
            "redo_snapshot", "privacy_file_cleanup", "digest_pending")
        assertEquals(actual, DayVolumeRows.dated.keys + DayVolumeRows.global + rebuildableOrLocalOnly)
    }
    @Test fun dayVolumeRoundTripPreservesEnvironmentCorrections() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val root = File(context.cacheDir, "audit087-${UUID.randomUUID()}").apply { mkdirs() }
        val original = CaptureDatabase.open(context, File(root, "original.db").path)
        try {
            original.capture().segment(SegmentEntity(id = 1, startAt = 1, endAt = 1001,
                sourceId = "mic", path = "/synthetic-audit", sizeBytes = 0, status = "Done"))
            original.envSound().insert(EnvSoundRow(1, 1, 1001, "/synthetic-audit", "Keep", null,
                "[]", "[]", "[]", false, false, restored = true, reportedWrong = true))
            assertTrue(original.envSound().get(1)!!.reportedWrong)
            val snapshot = File(root, "snapshot.db")
            BackupArchive.snapshot(original, snapshot)
            val (version, rows) = DayVolumeRows.write(snapshot, File(root, "rows"))
            val target = File(root, "restored.db")
            DayVolumeRows.rebuild(context, target, version, rows.getValue("state"), rows.filterKeys { it != "state" }.values.toList())
            val restored = CaptureDatabase.open(context, target.path)
            try {
                assertNotNull(restored.transcribe().findSegment(1))
                assertEquals(original.envSound().get(1), restored.envSound().get(1))
            } finally { restored.close() }
        } finally { original.close(); root.deleteRecursively() }
    }

    @Test fun restoredSettingsAreReadBeforeConstructingRuntimeState() {
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val root = File(context.cacheDir, "audit087-settings-${UUID.randomUUID()}").apply { mkdirs() }
        val prefs = context.getSharedPreferences("asr-speed", 0)
        prefs.edit().clear().commit()
        try {
            val source = AndroidAsrSpeedSettings(prefs, scope, now = { 100 })
            source.setSeparateSpeakers(false)
            BackupSettings.capture(context, root) { _, _ -> "synthetic" }
            prefs.edit().clear().commit()
            // 与修复后的 ResoundApp 相同：先恢复，再构造依赖设置的对象。
            BackupSettings.apply(context, root) { _, _, _ -> }
            val running = AndroidAsrSpeedSettings(prefs, scope, now = { 100 })
            running.refresh()
            assertFalse(running.effectiveSeparateSpeakers(101))
            running.startClassMode(30)
            assertFalse("后续修改不能覆盖恢复的历史", running.effectiveSeparateSpeakers(99 + 2))
        } finally { scope.cancel(); prefs.edit().clear().commit(); root.deleteRecursively() }
    }
}
