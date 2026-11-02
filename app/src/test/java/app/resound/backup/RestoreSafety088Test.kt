package app.resound.backup

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class RestoreSafety088Test {
    private fun checkRollback(interrupted: Boolean) {
        val context = RuntimeEnvironment.getApplication()
        val db = File(context.applicationInfo.dataDir, BackupArchive.DATABASE).apply { parentFile!!.mkdirs(); writeText("original-db") }
        val prefs = context.getSharedPreferences("asr-speed", 0)
        val stage = File(context.cacheDir, "restore-test-stage").apply { deleteRecursively(); mkdirs() }
        listOf("backup-restore", "backup-restore-done", "backup-restore-failed", "restore-journal")
            .forEach { File(context.noBackupFilesDir, it).deleteRecursively() }
        try {
            prefs.edit().putString("history", "restored-value").commit()
            BackupSettings.capture(context, stage) { _, _ -> "synthetic" }
            prefs.edit().putString("history", "original-value").commit()
            File(stage, "day-volumes").writeText("")
            File(stage, BackupArchive.DATABASE).apply { parentFile!!.mkdirs(); writeText("restored-db") }
            BackupRestore.stage(context, stage)
            try {
                BackupRestore.applyPending(context) { path ->
                    if (path == BackupArchive.DATABASE) {
                        assertEquals("restored-value", prefs.getString("history", null))
                        db.writeText("partial-db")
                        if (interrupted) throw SimulatedProcessDeath()
                        throw IOException("synthetic disk error")
                    }
                }
                assertFalse(interrupted)
            } catch (_: SimulatedProcessDeath) { assertTrue(interrupted) }
            if (interrupted) BackupRestore.applyPending(context)
            assertEquals("original-db", db.readText())
            assertEquals("original-value", prefs.getString("history", null))
            assertFalse(File(context.noBackupFilesDir, "backup-restore").exists())
            assertTrue(context.getSharedPreferences("backup", 0).getString("error", "")!!.contains("已保留原来的数据"))
            BackupRestore.applyPending(context) // 下次启动不再重试坏恢复或覆盖原数据。
            assertEquals("original-db", db.readText())
        } finally {
            db.delete(); stage.deleteRecursively()
            listOf("backup-restore", "backup-restore-done", "backup-restore-failed", "restore-journal")
                .forEach { File(context.noBackupFilesDir, it).deleteRecursively() }
        }
    }
    private class SimulatedProcessDeath : Error()
    @Test fun ioFailureRollsBackFilesAndCachedSettings() = checkRollback(false)
    @Test fun interruptedRestoreRollsBackOnNextStartup() = checkRollback(true)
}
