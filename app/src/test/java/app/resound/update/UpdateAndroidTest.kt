package app.resound.update

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import app.resound.backup.BackupRepository
import app.resound.backup.BackupState
import app.resound.data.CaptureDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class UpdateAndroidTest {
    @Test fun checkpointSnapshotIncludesWalAndPreferencesAndKeepsLatestThreeCopies() {
        val context = RuntimeEnvironment.getApplication() as Context
        context.deleteDatabase("capture.db")
        val database = Room.databaseBuilder(context, CaptureDatabase::class.java, "capture.db").allowMainThreadQueries().build()
        val target = File(context.filesDir, "pre-update")
        try {
            val sql = database.openHelper.writableDatabase
            sql.execSQL("CREATE TABLE update_backup_probe(value TEXT)")
            sql.execSQL("INSERT INTO update_backup_probe VALUES('before')")
            context.getSharedPreferences("voice-input", 0).edit().putBoolean("enabled", true).putString("probe", "涛声<&").commit()
            val backup = PreUpdateBackup(context, database, EmptyBackup(), {})
            backup.snapshot()
            // 模拟卡 051 的单份布局，后续快照应先迁移旧数据，再按时间保留三份。
            val legacy = File(context.filesDir, "legacy-test")
            check(target.listFiles()!!.single().renameTo(legacy))
            check(target.delete()); check(legacy.renameTo(target)); check(target.setLastModified(1000))
            val oldest = File(target, "%019d".format(1000L))
            repeat(3) {
                sql.execSQL("UPDATE update_backup_probe SET value='latest'")
                backup.snapshot()
            }
            assertEquals(3, target.listFiles()!!.size)
            assertFalse(oldest.exists())
            val latest = target.listFiles()!!.maxBy { it.name }
            SQLiteDatabase.openDatabase(File(latest, "databases/capture.db").path, null, SQLiteDatabase.OPEN_READONLY).use { copy ->
                copy.rawQuery("SELECT value FROM update_backup_probe", null).use { cursor ->
                    assertTrue(cursor.moveToFirst()); assertEquals("latest", cursor.getString(0))
                }
                copy.rawQuery("PRAGMA quick_check", null).use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals("ok", cursor.getString(0)) }
            }
            val prefs = File(latest, "shared_prefs/voice-input.xml").readText()
            assertTrue(prefs.contains("enabled")); assertTrue(prefs.contains("涛声&lt;&amp;"))
            assertFalse(File(context.filesDir, "pre-update-previous").exists())
            assertFalse(File(context.filesDir, "pre-update-staging").exists())
        } finally { database.close(); context.deleteDatabase("capture.db"); target.deleteRecursively() }
    }

    @Test fun validatesPackageVersionAndCurrentSigningCertificates() {
        fun verify(name: String = "app.resound", version: Long = 26, signers: Set<String> = setOf("current")) =
            AndroidUpdatePlatform.validateIdentity(name, version, 25, 26, signers, setOf("current"))
        verify()
        assertEquals("安装包签名不对，已删除", assertThrows(UpdateFailure::class.java) { verify(signers = setOf("other")) }.human)
        assertThrows(UpdateFailure::class.java) { verify(name = "app.other") }
        assertThrows(UpdateFailure::class.java) { verify(version = 25) }
    }
}

private class EmptyBackup : BackupRepository {
    override val state = MutableStateFlow(BackupState(false, null, false, false, null, null, null, null))
    override fun saveCredentials(account: String, appPassword: String) {}
    override fun clearCredentials() {}
    override fun setEnabled(enabled: Boolean) {}
    override fun backupNow() {}
    override suspend fun test() = Result.success("ok")
}
