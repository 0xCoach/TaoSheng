package app.resound.backup

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import app.resound.data.CaptureDatabase
import app.resound.diary.DiaryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.LocalDate
import java.util.zip.ZipFile
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DiaryBackupTest {
    @Test fun failedDiaryUploadKeepsSuccessfulBackupAndRetriesBothDaysNextNight() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val name = "diary-backup.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, CaptureDatabase::class.java, name).build()
        val remote = BackupWebDavDispatcher().apply { failDiaryUploads = true }
        val requests = remote.requests
        val dates = mutableListOf<LocalDate>()
        val diary = object : DiaryRepository {
            override val cloudSync = MutableStateFlow(false)
            override fun setCloudSync(enabled: Boolean) { cloudSync.value = enabled }
            override suspend fun markdown(from: LocalDate, to: LocalDate, includeLines: Boolean): String {
                assertEquals(from, to); assertFalse(includeLines)
                dates += from
                return "# 日记 $from"
            }
            override suspend fun exportFile(from: LocalDate, to: LocalDate, includeLines: Boolean): Uri = error("不应导出缓存")
        }
        val server = MockWebServer().apply { dispatcher = remote }
        val zip = File(context.cacheDir, "diary-backup-upload.zip")
        server.start()
        try {
            val config = BackupConfig(server.url("/dav/").toString())
            context.getSharedPreferences("diary", 0).edit().putBoolean("cloudSync", true).commit()
            val repository = AndroidBackupRepository(context, db, config, diary) {
                WebDavClient(config, "回响", "test@example.com", "test-password")
            }
            repository.setEnabled(true)
            // 默认关：成功备份不应触发日记生成。
            assertFalse(repository.runBackup(true)); assertTrue(dates.isEmpty())
            assertNull(repository.state.value.lastError)
            assertEquals(1, repository.remoteBackups().getOrThrow().size)
            assertFalse(requests.any { WebDavClient.DIARY_FOLDER in it.requestUrl!!.pathSegments })
            diary.setCloudSync(true)
            val today = LocalDate.now()
            assertFalse(repository.runBackup(true))
            assertNotNull(repository.state.value.lastBackupAt)
            assertNull(repository.state.value.lastError)
            assertFalse(repository.state.value.running)
            assertEquals(2, repository.remoteBackups().getOrThrow().size)
            assertEquals(listOf(today.minusDays(1), today), dates)
            val diagnostic = context.getSharedPreferences("diary-sync", 0)
            assertEquals("日记上传失败，下次定时备份时重试", diagnostic.getString("lastFailure", null))
            val failureAt = diagnostic.getLong("lastFailureAt", 0)
            assertTrue(failureAt > 0)
            remote.failDiaryUploads = false
            assertFalse(repository.runBackup(true))
            assertEquals(listOf(today.minusDays(1), today, today.minusDays(1), today), dates)
            assertEquals(failureAt, diagnostic.getLong("lastFailureAt", 0))
            val uploads = requests.filter { it.method == "PUT" && WebDavClient.DIARY_FOLDER in it.requestUrl!!.pathSegments }
            assertEquals(listOf("${today.minusDays(1)}.md", "$today.md", "${today.minusDays(1)}.md", "$today.md"),
                uploads.map { it.requestUrl!!.pathSegments.last() })
            assertTrue(uploads.all { it.body.clone().readUtf8().startsWith("# 日记 ") })
            assertEquals(1, requests.count { it.method == "MKCOL" && WebDavClient.DIARY_FOLDER in it.requestUrl!!.pathSegments })
            for (date in listOf(today.minusDays(1), today)) {
                assertEquals("# 日记 $date", remote.read("/dav/${WebDavClient.DIARY_FOLDER}/$date.md").toString(Charsets.UTF_8))
            }
            // 状态 ZIP 从历史恢复点定位；JSON 恢复点和其他对象不能都当作 ZIP。
            // 在测试线程验证，避免 Dispatcher 内断言失败造成客户端等待超时。
            val points = repository.remoteBackups().getOrThrow()
            assertEquals(3, points.size)
            for (point in points) {
                val checkpoint = JSONObject(remote.read("/dav/${WebDavClient.FOLDER}/${point.name}").toString(Charsets.UTF_8))
                val refs = checkpoint.getJSONArray("refs")
                val state = (0 until refs.length()).map(refs::getJSONObject).single { it.getString("logical") == "resound-state.zip" }
                zip.writeBytes(remote.read("/dav/${WebDavClient.FOLDER}/${state.getString("path")}"))
                ZipFile(zip).use {
                    val settings = JSONObject(it.getInputStream(it.getEntry("settings.json")).bufferedReader().use { reader -> reader.readText() })
                    assertTrue(settings.has("diary"))
                    assertFalse(settings.has("diary-sync"))
                }
            }
            assertFalse(remote.paths().any { it.endsWith(".part") })
            assertNull(repository.state.value.lastError)
            assertTrue(BackupArchive.allowed("shared_prefs/diary.xml"))
            repository.setEnabled(false)
        } finally { server.shutdown(); db.close(); context.deleteDatabase(name); zip.delete() }
    }
}
