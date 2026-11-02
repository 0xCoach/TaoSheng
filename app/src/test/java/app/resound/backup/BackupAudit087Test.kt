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

/** 审计复现：断言当前缺陷的观察结果；修复时应改为数据保留/设置及时生效的回归断言。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BackupAudit087Test {
    @Test fun dayVolumeRoundTripCurrentlyDropsEnvironmentCorrections() = runBlocking {
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
                assertNull("已确认缺陷：环境音及纠错状态未被导出", restored.envSound().get(1))
            } finally { restored.close() }
        } finally { original.close(); root.deleteRecursively() }
    }

    @Test fun restoringSettingsAfterConstructionLeavesOldHistoryInMemory() {
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
            // 与 ResoundApp 相同：先构造设置对象，再恢复偏好文件。
            val running = AndroidAsrSpeedSettings(prefs, scope, now = { 100 })
            BackupSettings.apply(context, root) { _, _, _ -> }
            running.refresh()
            assertTrue("已确认缺陷：当前进程仍使用恢复前的设置", running.effectiveSeparateSpeakers(101))
            assertFalse("下一次构造才读到恢复值", AndroidAsrSpeedSettings(prefs, scope, now = { 100 }).effectiveSeparateSpeakers(101))
        } finally { scope.cancel(); prefs.edit().clear().commit(); root.deleteRecursively() }
    }
}
