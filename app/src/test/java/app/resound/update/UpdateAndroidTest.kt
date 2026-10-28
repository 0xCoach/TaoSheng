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
    @Test fun checkpointSnapshotIncludesWalAndPreferencesAndKeepsOnlyTheLatestCopy() {
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
            sql.execSQL("UPDATE update_backup_probe SET value='latest'")
            backup.snapshot()
            SQLiteDatabase.openDatabase(File(target, "databases/capture.db").path, null, SQLiteDatabase.OPEN_READONLY).use { copy ->
                copy.rawQuery("SELECT value FROM update_backup_probe", null).use { cursor ->
                    assertTrue(cursor.moveToFirst()); assertEquals("latest", cursor.getString(0))
                }
                copy.rawQuery("PRAGMA quick_check", null).use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals("ok", cursor.getString(0)) }
            }
            val prefs = File(target, "shared_prefs/voice-input.xml").readText()
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
