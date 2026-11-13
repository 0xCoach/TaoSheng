package app.resound.backup

import app.resound.data.open

import app.resound.data.testSql
import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import app.resound.data.CaptureDatabase
import app.resound.diary.DiaryRepository
import app.resound.prompts.LocalPromptTemplates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BackupPrompts029Test {
    private lateinit var context: Context
    private lateinit var db: CaptureDatabase
    private lateinit var root: File
    private lateinit var server: MockWebServer
    private lateinit var client: WebDavClient
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("capture.db")
        db = Room.databaseBuilder(context, CaptureDatabase::class.java, "capture.db").setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).allowMainThreadQueries().build()
        db.testSql.version
        root = File(context.cacheDir, "029-test").apply { deleteRecursively(); mkdirs() }
        listOf("before-restore", "backup-restore", "backup-restore-done").forEach { File(context.noBackupFilesDir, it).deleteRecursively() }
        File(context.filesDir, "prompts").deleteRecursively()
        server = MockWebServer().apply { start() }
        client = WebDavClient(BackupConfig(server.url("/dav/").toString()), "回响", "test@example.com", "test-only")
    }
    @After fun cleanup() { server.shutdown(); db.close(); root.deleteRecursively() }
    private fun listing(names: List<String>, bytes: Long = 1234) = MockResponse().setResponseCode(207).setBody(
        "<d:multistatus xmlns:d='DAV:'>" + names.joinToString("") {
            val href = server.url("/dav/").newBuilder().addPathSegment(WebDavClient.FOLDER).addPathSegment(it).build().encodedPath
            "<d:response><d:href>$href</d:href><d:propstat><d:prop><d:getcontentlength>$bytes</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
        } + "</d:multistatus>")
    private fun archive(name: String = "current"): File = File(root, "$name.zip").also {
        BackupArchive.create(context, db, File(root, name).apply { mkdirs() }, it, 12345)
    }
    private fun failure(block: () -> Unit): String {
        try { block(); fail("应失败") } catch (e: BackupFailure) { return e.human }
        error("应失败")
    }
    @Test fun retainsNewestThirtyAcrossPlainAndEncryptedWithoutTouchingDiary() {
        val names = (0..32).map { i -> "resound-${LocalDate.of(2026, 9, 1).plusDays(i.toLong())}-0310.zip" + if (i % 2 == 0) ".enc" else "" }
        server.enqueue(listing(names.reversed() + listOf("2026-09-01.md", "unrelated.zip", names.last() + ".part")))
        repeat(3) { server.enqueue(MockResponse().setResponseCode(204)) }
        assertEquals(30, client.retain(30))
        assertEquals("PROPFIND", server.takeRequest().method)
        val deleted = (0..2).map { server.takeRequest().also { assertEquals("DELETE", it.method) }.requestUrl!!.pathSegments.last() }
        assertEquals(names.take(3).toSet(), deleted.toSet())
    }
    @Test fun parsesTimesSizesEncryptionAndRejectsInvalidNames() {
        val old = "resound-2026-10-23-0310.zip"
        val recent = "resound-2026-10-24-0310-59123-abcdef12.zip.enc"
        server.enqueue(listing(listOf(old, recent, recent, "resound-2026-02-30-0310.zip", "$old.part"), 5678))
        server.enqueue(MockResponse().setResponseCode(404))
        val list = client.backups()
        assertEquals(listOf(recent, old), list.map { it.name })
        assertTrue(list[0].encrypted); assertFalse(list[1].encrypted)
        assertEquals(5678L, list[0].bytes)
        val expected = LocalDate.of(2026, 10, 24).atTime(3, 10, 59, 123_000_000).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, list[0].at)
    }
    @Test fun restoreSnapshotsCurrentDataBeforeReplacingAndMigratesOlderDatabase() {
        val templates = LocalPromptTemplates(context)
        val localText = templates.defaultText("digest") + "\n本机修改"
        assertTrue(templates.save("digest", localText).isSuccess)
        context.getSharedPreferences("capture", 0).edit().putString("probe", "local-before").commit()
        val stage = File(root, "old").apply { mkdirs() }
        val file = File(stage, BackupArchive.DATABASE).apply { parentFile!!.mkdirs() }
        val schema = JSONObject(File("../shared/schemas/app.resound.data.CaptureDatabase/10.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { i ->
                val entity = entities.getJSONObject(i)
                fun sql(value: String) = value.replace("\u0024{TABLE_NAME}", entity.getString("tableName"))
                old.execSQL(sql(entity.getString("createSql")))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                repeat(indices.length()) { j -> old.execSQL(sql(indices.getJSONObject(j).getString("createSql"))) }
            }
            val queries = schema.getJSONArray("setupQueries")
            repeat(queries.length()) { old.execSQL(queries.getString(it)) }
            old.version = 10
        }
        val zip = File(root, "old.zip")
        BackupArchive.pack(stage, zip, 123, "old", 10)
        val encrypted = BackupEncryption.prepare(zip, "test-restore")
        server.enqueue(MockResponse().setBody(okio.Buffer().write(encrypted.readBytes())))
        val calls = mutableListOf<String>()
        BackupRestore.restore(context, client, "回响-2026-10-23-0310.zip.enc", db,
            storedPassphrase = "outdated-test", suppliedPassphrase = "test-restore", closeDatabase = {
                val snapshot = File(context.noBackupFilesDir, "before-restore").listFiles()!!.single()
                val bundle = File(root, "snapshot-bundle")
                DayVolumes.unzip(snapshot, bundle, ::volumePath)
                val work = File(root, "snapshot-read").apply { mkdirs() }
                val saved = DayVolumes.readState(DirectoryVolumeStore(bundle), work, null)!!
                assertEquals(localText, File(saved.directory, "files/prompts/digest.md").readText())
                assertTrue(File(saved.directory, "settings.json").readText().contains("local-before"))
                assertTrue(File(saved.directory, "secrets.json").exists())
                calls += "close"; db.close()
            }, restartProcess = { calls += "restart" })
        assertEquals(listOf("close", "restart"), calls)
        assertEquals("GET", server.takeRequest().method)
        BackupRestore.applyPending(context)
        assertFalse(File(context.filesDir, "prompts/digest.md").exists())
        val restored = CaptureDatabase.open(context)
        try { assertEquals(CaptureDatabase.VERSION, restored.testSql.version) } finally { restored.close() }
        assertTrue(File(context.noBackupFilesDir, "before-restore").listFiles()!!.single().isFile)
    }
    @Test fun newerVersionIsRejectedBeforeSnapshotOrReplacement() {
        val stage = File(root, "future").apply { mkdirs() }
        BackupArchive.snapshot(db, File(stage, BackupArchive.DATABASE).apply { parentFile!!.mkdirs() })
        val zip = File(root, "future.zip")
        BackupArchive.pack(stage, zip, 123, "future", db.testSql.version + 1)
        server.enqueue(MockResponse().setBody(okio.Buffer().write(zip.readBytes())))
        assertEquals("备份数据库版本比 App 新，请先升级 App", failure {
            BackupRestore.restore(context, client, "回响-2026-10-24-0310.zip", db,
                closeDatabase = { fail("不应关闭") }, restartProcess = { fail("不应重启") })
        })
        assertEquals(CaptureDatabase.VERSION, db.testSql.version)
        assertFalse(File(context.noBackupFilesDir, "before-restore").exists())
        assertFalse(File(context.noBackupFilesDir, "backup-restore").exists())
    }
    @Test fun encryptionRoundTripUsesRandomSaltAndRejectsWrongPasswordAndTampering() {
        val zip = archive()
        val first = BackupEncryption.prepare(zip, "test-passphrase")
        val second = File(root, "second.enc")
        BackupEncryption.encrypt(zip, second, "test-passphrase")
        assertFalse(first.readBytes().contentEquals(second.readBytes()))
        val output = File(root, "plain.zip")
        BackupEncryption.decrypt(first, output, "test-passphrase", "ignored-test")
        assertArrayEquals(zip.readBytes(), output.readBytes())
        assertEquals("口令不对", failure { BackupEncryption.decrypt(first, output, null, "wrong-test") })
        assertFalse(output.exists())
        val bytes = first.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); first.writeBytes(bytes)
        assertEquals("口令不对", failure { BackupEncryption.decrypt(first, output, null, "test-passphrase") })
        assertFalse(output.exists())
    }
    @Test fun disablingEncryptionChangesNextUploadAndKeepsDiaryAndSnapshotCleanup() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val store = object : BackupPassphrases {
            var value: String? = null
            override fun has() = value != null
            override fun read() = value.orEmpty()
            override fun write(value: String?) { this.value = value }
        }
        val diary = object : DiaryRepository {
            override val cloudSync = MutableStateFlow(true)
            override fun setCloudSync(enabled: Boolean) { cloudSync.value = enabled }
            override suspend fun markdown(from: LocalDate, to: LocalDate, includeLines: Boolean) = "# 日记 $from"
            override suspend fun exportFile(from: LocalDate, to: LocalDate, includeLines: Boolean): Uri = error("不调用")
        }
        val remote = BackupWebDavDispatcher().apply { failBackupUploads = true }
        server.dispatcher = remote
        val requests = remote.requests
        val repository = AndroidBackupRepository(context, db, diary = diary, passphrases = store, clientFactory = { client })
        val snapshot = File(context.noBackupFilesDir, "before-restore/keep.zip").apply { parentFile!!.mkdirs(); writeText("snapshot") }
        repository.setEncryption("test-only"); repository.setEnabled(true)
        assertTrue(repository.state.value.encrypted)
        assertTrue(repository.runBackup(true)); assertTrue(snapshot.exists())
        assertEquals("坚果云暂时不可用", repository.state.value.lastError)
        assertTrue(repository.remoteBackups().getOrThrow().isEmpty())
        assertFalse(requests.any { it.method == "PUT" && WebDavClient.DIARY_FOLDER in it.requestUrl!!.pathSegments })
        remote.failBackupUploads = false
        assertFalse(repository.runBackup(true)); assertNull(repository.state.value.lastError)
        assertFalse(snapshot.exists())
        val encryptedPoint = repository.remoteBackups().getOrThrow().single()
        assertTrue(encryptedPoint.encrypted)
        repository.setEncryption(null)
        assertFalse(repository.state.value.encrypted)
        assertFalse(repository.runBackup(true)); assertNull(repository.state.value.lastError)
        val points = repository.remoteBackups().getOrThrow()
        assertEquals(2, points.size)
        val plainPoint = points.single { it.name != encryptedPoint.name }
        assertFalse(plainPoint.encrypted)
        // 卡 039 后扩展名属于逻辑分卷；远端 .bin 对象须由恢复点的引用定位。
        fun stateObject(point: RemoteBackup, logical: String): File {
            val checkpoint = JSONObject(remote.read("/dav/${WebDavClient.FOLDER}/${point.name}").toString(Charsets.UTF_8))
            assertEquals(point.encrypted, checkpoint.getBoolean("encrypted"))
            val refs = checkpoint.getJSONArray("refs")
            val state = (0 until refs.length()).map(refs::getJSONObject).single { it.getString("logical") == logical }
            return File(root, logical).apply { writeBytes(remote.read("/dav/${WebDavClient.FOLDER}/${state.getString("path")}")) }
        }
        val encrypted = stateObject(encryptedPoint, "resound-state.zip.enc")
        assertTrue(BackupEncryption.isEncrypted(encrypted))
        val decrypted = File(root, "decrypted-state.zip")
        BackupEncryption.decrypt(encrypted, decrypted, "test-only", null)
        val plain = stateObject(plainPoint, "resound-state.zip")
        assertFalse(BackupEncryption.isEncrypted(plain))
        for (file in listOf(decrypted, plain)) ZipFile(file).use { assertNotNull(it.getEntry("settings.json")) }
        assertEquals(4, requests.count { it.method == "PUT" && WebDavClient.DIARY_FOLDER in it.requestUrl!!.pathSegments })
        assertFalse(remote.paths().any { it.endsWith(".part") })
        assertTrue(requests.filter { it.method == "DELETE" }.all { it.requestUrl!!.pathSegments.last().endsWith(".part") })
        repository.setEnabled(false)
    }
    @Test fun templatesReadDefaultsAndPersistOverridesInBackup() {
        val templates = LocalPromptTemplates(context)
        val expectedIds = listOf("digest", "review", "ask", "things", "merge")
        assertEquals(expectedIds, templates.list.value.map { it.id })
        for (id in expectedIds) {
            assertEquals(templates.defaultText(id), templates.text(id))
            assertTrue(templates.save(id, templates.defaultText(id) + "\n自定义规则").isSuccess)
            assertTrue(LocalPromptTemplates(context).text(id).endsWith("自定义规则"))
        }
        assertTrue(templates.list.value.all { it.customized })
        ZipFile(archive()).use { zip ->
            for (id in expectedIds) {
                assertEquals(templates.text(id), zip.getInputStream(zip.getEntry("files/prompts/$id.md")).bufferedReader().readText())
            }
        }
    }
    @Test fun missingPlaceholderAndBlankTemplateDoNotOverwriteSavedText() {
        val templates = LocalPromptTemplates(context)
        val original = templates.text("digest")
        assertEquals("缺少 {{CALENDAR}}，整理时会用到", templates.save("digest", original.replace("{{CALENDAR}}", "")).exceptionOrNull()!!.message)
        assertTrue(templates.save("digest", "  ").isFailure)
        assertEquals(original, templates.text("digest"))
        assertEquals(listOf("{{APP_NAME}}", "{{CALENDAR}}"), templates.list.value.first().requiredPlaceholders)
    }
    @Test fun resetRemovesOverrideAndReturnsDefault() {
        val templates = LocalPromptTemplates(context)
        assertTrue(templates.save("ask", templates.defaultText("ask") + "\n修改").isSuccess)
        templates.reset("ask")
        assertEquals(templates.defaultText("ask"), templates.text("ask"))
        assertFalse(templates.list.value.first { it.id == "ask" }.customized)
        assertFalse(File(context.filesDir, "prompts/ask.md").exists())
    }
}
