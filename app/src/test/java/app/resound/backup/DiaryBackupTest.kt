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
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.LocalDate
import java.util.Collections
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
        val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
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
        val server = MockWebServer()
        var failDiary = true
        var diaryDirectoryExists = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val diaryPath = WebDavClient.DIARY_FOLDER in request.requestUrl!!.pathSegments
                return when {
                    diaryPath && request.method == "PROPFIND" -> MockResponse().setResponseCode(if (diaryDirectoryExists) 207 else 404)
                    diaryPath && request.method == "MKCOL" -> { diaryDirectoryExists = true; MockResponse().setResponseCode(201) }
                    diaryPath && request.method == "PUT" -> MockResponse().setResponseCode(if (failDiary) 503 else 204)
                    request.method == "GET" || request.method == "HEAD" -> MockResponse().setResponseCode(404)
                    request.method == "PROPFIND" -> MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d='DAV:'/>")
                    request.method == "PUT" -> {
                        val zip = File(context.cacheDir, "diary-backup-upload.zip").apply { writeBytes(request.body.clone().readByteArray()) }
                        ZipFile(zip).use {
                            val settings = it.getInputStream(it.getEntry("settings.json")).bufferedReader().readText()
                            assertTrue(org.json.JSONObject(settings).has("diary"))
                            assertFalse(org.json.JSONObject(settings).has("diary-sync"))
                        }
                        zip.delete()
                        MockResponse().setResponseCode(201)
                    }
                    else -> MockResponse().setResponseCode(204)
                }
            }
        }
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
            diary.setCloudSync(true)
            val today = LocalDate.now()
            assertFalse(repository.runBackup(true))
            assertNotNull(repository.state.value.lastBackupAt)
            assertNull(repository.state.value.lastError)
            assertFalse(repository.state.value.running)
            assertEquals(listOf(today.minusDays(1), today), dates)
            val diagnostic = context.getSharedPreferences("diary-sync", 0)
            assertEquals("日记上传失败，下次定时备份时重试", diagnostic.getString("lastFailure", null))
            val failureAt = diagnostic.getLong("lastFailureAt", 0)
            assertTrue(failureAt > 0)
            failDiary = false
            assertFalse(repository.runBackup(true))
            assertEquals(listOf(today.minusDays(1), today, today.minusDays(1), today), dates)
            assertEquals(failureAt, diagnostic.getLong("lastFailureAt", 0))
            val uploads = requests.filter { it.method == "PUT" && WebDavClient.DIARY_FOLDER in it.requestUrl!!.pathSegments }
            assertEquals(listOf("${today.minusDays(1)}.md", "$today.md", "${today.minusDays(1)}.md", "$today.md"),
                uploads.map { it.requestUrl!!.pathSegments.last() })
            assertTrue(uploads.all { it.body.clone().readUtf8().startsWith("# 日记 ") })
            assertEquals(1, requests.count { it.method == "MKCOL" })
            assertNull(repository.state.value.lastError)
            assertTrue(BackupArchive.allowed("shared_prefs/diary.xml"))
            repository.setEnabled(false)
        } finally { server.shutdown(); db.close(); context.deleteDatabase(name) }
    }
}
