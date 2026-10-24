package app.resound.backup

import android.app.Application
import android.content.Context
import app.resound.data.CaptureDatabase
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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DayVolumesTest {
    private lateinit var context: Context
    private lateinit var db: CaptureDatabase
    private lateinit var root: File
    private lateinit var remote: File
    private lateinit var disk: DirectoryVolumeStore
    private lateinit var store: VolumeStore
    private val writes = mutableListOf<String>()
    private var fail: String? = null
    private val now = at("2026-10-24")
    private fun at(day: String) = LocalDate.parse(day).atStartOfDay(DayVolumeRows.zone).toInstant().toEpochMilli()
    private fun work() = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        root = File(context.cacheDir, "day-tests").apply { deleteRecursively(); mkdirs() }
        remote = File(root, "remote").apply { mkdirs() }; disk = DirectoryVolumeStore(remote)
        context.deleteDatabase("capture.db"); db = CaptureDatabase.open(context); db.openHelper.writableDatabase
        (BackupSettings.prefs + BackupSettings.stores.keys + "backup-encryption-secrets").forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        File(context.applicationInfo.dataDir, BackupArchive.FEATURES).deleteRecursively()
        File(context.noBackupFilesDir, "backup-restore").deleteRecursively()
        store = object : VolumeStore by disk {
            override fun put(path: String, source: File) {
                if (path == fail) throw BackupFailure("模拟断网", true)
                writes += path; disk.put(path, source)
            }
        }
    }
    @After fun cleanup() { db.close(); root.deleteRecursively() }
    private fun add(id: Long, day: String, title: String = "hello") {
        db.openHelper.writableDatabase.execSQL("INSERT INTO ask_conversations(id,createdAt,title) VALUES(?,?,?)", arrayOf(id, at(day), title))
    }
    private fun sync(time: Long = now, pass: String? = null) = DayVolumes.sync(context, db, store, work(), pass, time)
    private fun state(pass: String? = null) = DayVolumes.readState(store, work(), pass)!!
    private fun restore(pass: String? = null): File = File(root, "verified-${UUID.randomUUID()}").also {
        DayVolumes.restore(context, store, work(), it, pass, null)
    }
    private fun update(id: Long) = db.openHelper.writableDatabase.execSQL("UPDATE ask_conversations SET title=title||' changed' WHERE id=?", arrayOf(id))

    @Test fun stableRowsAndBeijingBoundary() {
        add(2, "2026-10-24"); add(1, "2026-10-21")
        fun hashes(): Map<String, String> {
            val dir = work(); val file = File(dir, "copy.db"); BackupArchive.snapshot(db, file)
            return DayVolumeRows.write(file, File(dir, "rows")).second.mapValues { BackupArchive.sha(it.value) }
        }
        val original = hashes(); assertEquals(original, hashes()); update(1)
        val changed = hashes(); assertNotEquals(original["2026-10-21"], changed["2026-10-21"])
        assertEquals(original["2026-10-24"], changed["2026-10-24"])
        assertEquals("2026-10-23", DayVolumeRows.day(now - 1)); assertEquals("2026-10-24", DayVolumeRows.day(now))
    }
    @Test fun firstAllThenTodayThenOldEditAndTodayWithoutLocalBaseline() {
        add(1, "2026-10-21"); add(2, "2026-10-24"); assertEquals(2, sync().days)
        assertEquals(2, writes.count { it.startsWith("days/") })
        writes.clear(); update(2); val summary = sync()
        assertEquals(listOf("days/resound-day-2026-10-24.zip", "resound-state.zip"), writes)
        assertEquals(writes.sumOf { File(remote, it).length() }, summary.lastUploadBytes)
        writes.clear(); update(1); update(2); sync()
        assertEquals(2, writes.count { it.startsWith("days/") }); assertEquals("resound-state.zip", writes.last())
        writes.clear(); sync(); assertEquals(listOf("resound-state.zip"), writes)
    }
    @Test fun missingManifestRetransmitsAllDays() {
        val feature = File(context.applicationInfo.dataDir, BackupArchive.FEATURES + "b".repeat(64)).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(4, 5, 6)) }
        add(1, "2026-10-21"); add(2, "2026-10-24"); sync()
        assertTrue(feature.exists())
        File(remote, "resound-state.zip").delete(); writes.clear(); sync()
        assertEquals(2, writes.count { it.startsWith("days/") }); assertEquals(2, state().summary().days)
        // 声纹特征按天打包（坚果云限流），清单丢失时和日分卷一样重传，只有一个包
        assertEquals(1, writes.count { it.startsWith("features/resound-features-") })
    }
    @Test fun interruptionAfterOverwriteKeepsOldManifestRestorableAndRetryCommits() {
        add(1, "2026-10-21", "before"); add(2, "2026-10-24"); sync(); sync() // 已有上一轮清理记录
        val previous = File(remote, "resound-state.zip").readBytes()
        update(1); update(2); fail = "days/resound-day-2026-10-24.zip"
        assertTrue(runCatching { sync() }.isFailure)
        assertArrayEquals(previous, File(remote, "resound-state.zip").readBytes())
        assertTrue(runCatching { sync() }.isFailure) // 连续两次中断不能覆盖第一次留下的旧内容
        val restored = CaptureDatabase.open(context, File(restore(), BackupArchive.DATABASE).path)
        try { restored.openHelper.writableDatabase.query("SELECT title FROM ask_conversations WHERE id=1").use { assertTrue(it.moveToFirst()); assertEquals("before", it.getString(0)) } }
        finally { restored.close() }
        fail = null; sync(); assertFalse(File(remote, "days").listFiles()!!.any { it.name.startsWith(".previous-") })
    }
    @Test fun restoresRowsFtsFeaturesAndOmitsEmbeddings() = runBlocking {
        db.capture().segment(app.resound.data.SegmentEntity(1, now, now + 1000, "mic", "/old/audio.ogg", 100, "Done"))
        db.transcribe().insertLines(listOf(app.resound.data.TranscriptLineEntity(segmentId = 1, startAt = now, endAt = now + 1000, text = "hello world", speakerTag = null)))
        val feature = File(context.applicationInfo.dataDir, BackupArchive.FEATURES + "a".repeat(64)).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        // 自定义技能和改过的要求也在备份里（原卡 031 的整包往返测试移到这里）
        val skill = File(context.filesDir, "skills/mine/my_meeting/prompt.md").apply { parentFile!!.mkdirs(); writeText("自定义要求") }
        sync(); val saved = restore()
        assertArrayEquals(feature.readBytes(), File(saved, BackupArchive.FEATURES + feature.name).readBytes())
        assertEquals("自定义要求", File(saved, "files/skills/mine/my_meeting/prompt.md").readText()); skill.delete()
        val room = CaptureDatabase.open(context, File(saved, BackupArchive.DATABASE).path)
        try {
            val sql = room.openHelper.writableDatabase
            sql.query("SELECT text FROM transcript_lines").use { assertTrue(it.moveToFirst()); assertEquals("hello world", it.getString(0)) }
            sql.query("SELECT rowid FROM lines_fts WHERE lines_fts MATCH 'hello'").use { assertTrue(it.moveToFirst()) }
            sql.query("SELECT COUNT(*) FROM embeddings").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            sql.query("PRAGMA integrity_check").use { it.moveToFirst(); assertEquals("ok", it.getString(0)) }
        } finally { room.close() }
        writes.clear(); sync(); assertFalse(writes.any { it.startsWith("features/") })
    }
    @Test fun oldMissingColumnDefaultsAndFutureVersionRefused() {
        val state = File(root, "state.jsonl").apply { writeText("") }
        val rows = File(root, "old.jsonl").apply { writeText("""{"table":"segments","values":{"id":1,"startAt":0,"endAt":1,"sourceId":"mic","path":"old","sizeBytes":0,"status":"Done"}}
{"table":"memory_events","values":{"id":1,"startAt":0,"endAt":1,"scene":"独白","title":"旧事件","summary":"摘要","pointsJson":"[]","peopleJson":"[]","important":1,"createdAt":0,"model":"test"}}
{"table":"memory_items","values":{"id":1,"eventId":1,"category":"灵感","content":"旧灵感","status":"Pending","createdAt":0}}
""") }
        val target = File(root, "old.db")
        DayVolumeRows.rebuild(context, target, 1, state, listOf(rows))
        val room = CaptureDatabase.open(context, target.path)
        try {
            room.openHelper.writableDatabase.query("SELECT starred,starredAt,important FROM memory_events").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertTrue(it.isNull(1)); assertEquals(1, it.getInt(2))
            }
            room.openHelper.writableDatabase.query("SELECT attempts,playbackMillis FROM segments").use { it.moveToFirst(); assertEquals(0, it.getInt(0)); assertEquals(0, it.getInt(1)) }
            room.openHelper.writableDatabase.query("SELECT content,mergedInto,noMerge FROM memory_items").use {
                assertTrue(it.moveToFirst()); assertEquals("旧灵感", it.getString(0)); assertTrue(it.isNull(1)); assertEquals(0, it.getInt(2))
            }
        }
        finally { room.close() }
        val error = runCatching { DayVolumeRows.rebuild(context, File(root, "future.db"), 999, state, listOf(rows)) }.exceptionOrNull()
        assertEquals("备份数据库版本比 App 新，请先升级 App", error!!.message)
    }
    @Test fun secretsAndTypedSettingsMergePreserveMissingKeysAndExcludePassphrase() {
        context.getSharedPreferences("digest-secrets", 0).edit().putString("model", "fake-cipher").commit()
        context.getSharedPreferences("transcribe", 0).edit().putString("encryptedKey", "fake-cipher").putString("endpoint", "https://example.invalid").commit()
        context.getSharedPreferences("backup-encryption-secrets", 0).edit().putString("passphrase", "never-export").commit()
        context.getSharedPreferences("backup", 0).edit().putBoolean("enabled", true).putString("account", "test@example.invalid").commit()
        val config = work(); BackupSettings.capture(context, config) { store, key -> "$store/$key/test-only" }
        assertFalse(File(config, "settings.json").readText().contains("encryptedKey"))
        assertFalse(File(config, "secrets.json").readText().contains("never-export"))
        val keys = mutableMapOf("digest-secrets/model" to "local", "push-secrets/existing" to "keep")
        context.getSharedPreferences("ui", 0).edit().putString("only-on-phone", "keep").commit()
        BackupSettings.apply(context, config) { store, key, value -> keys["$store/$key"] = value }
        assertEquals("digest-secrets/model/test-only", keys["digest-secrets/model"])
        assertEquals("keep", keys["push-secrets/existing"])
        assertEquals("keep", context.getSharedPreferences("ui", 0).getString("only-on-phone", null))
        assertTrue(context.getSharedPreferences("backup", 0).getBoolean("enabled", false))
    }
    @Test fun unencryptedSummaryIncludesSecretsAndDeletingDayRemovesRemote() {
        add(1, "2026-10-21"); add(2, "2026-10-24"); val summary = sync()
        assertTrue(summary.includesSecrets); assertFalse(summary.encrypted)
        db.openHelper.writableDatabase.execSQL("DELETE FROM ask_conversations WHERE id=1")
        store = object : VolumeStore by disk {
            override fun delete(path: String) { if (path == "days/resound-day-2026-10-21.zip") throw BackupFailure("模拟删除失败"); disk.delete(path) }
        }
        assertTrue(runCatching { sync() }.isFailure)
        assertTrue(File(remote, "days/resound-day-2026-10-21.zip").exists())
        store = disk; sync()
        assertFalse(File(remote, "days/resound-day-2026-10-21.zip").exists()); assertEquals(1, state().summary().days)
    }
    @Test fun exportBundleRestoresWithoutFilenameOrLocalBaseline() {
        add(1, "2026-10-24")
        val bundle = File(root, "arbitrary-name"); DayVolumes.export(context, db, work(), bundle, "test-only")
        assertTrue(DayVolumes.isBundle(bundle))
        var restarted = false
        BackupRestore.restoreFile(context, bundle, db, suppliedPassphrase = "test-only", closeDatabase = db::close, restartProcess = { restarted = true })
        assertTrue(restarted); assertTrue(File(context.noBackupFilesDir, "before-restore").listFiles()!!.isNotEmpty())
        BackupRestore.applyPending(context)
        val restored = CaptureDatabase.open(context)
        try { restored.openHelper.writableDatabase.query("SELECT COUNT(*) FROM ask_conversations").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) } }
        finally { restored.close() }
    }
    @Test fun cleanupWaitsSevenDaysOfSuccessfulFormatAndKeepsUnrelatedFiles() {
        add(1, "2020-01-01") // 数据很老不代表新格式已经稳定运行 7 天。
        val old = File(remote, "resound-2026-01-01-0310.zip").apply { writeText("legacy") }
        val unrelated = File(remote, "notes.zip").apply { writeText("keep") }
        val chinese = File(remote, "回响-2026-01-01-0310.zip").apply { writeText("keep") }
        sync(); assertTrue(old.exists()); sync(now + 6 * 86400000L); assertTrue(old.exists())
        sync(now + 7 * 86400000L); assertFalse(old.exists()); assertTrue(unrelated.exists()); assertTrue(chinese.exists())
        assertTrue(File(remote, "days/resound-day-2020-01-01.zip").exists())
    }
    @Test fun wrongPassphraseAndTamperedVolumeNeverStageReplacement() {
        add(1, "2026-10-24"); sync(pass = "test-only")
        assertTrue(runCatching { restore("wrong") }.isFailure)
        File(remote, "days/resound-day-2026-10-24.zip.enc").appendText("bad")
        assertTrue(runCatching { restore("test-only") }.isFailure)
        assertTrue(db.isOpen); assertFalse(File(context.noBackupFilesDir, "backup-restore").exists())
    }
    @Test fun newPhoneCanSelectEncryptedFolderBeforeSupplyingPassphrase() = runBlocking {
        add(1, "2026-10-24"); sync(pass = "test-only")
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context, androidx.work.Configuration.Builder().build())
        val files = LocalBackupFiles(context, { androidx.documentfile.provider.DocumentFile.fromFile(remote) }, {})
        val passphrases = object : BackupPassphrases {
            override fun has() = false
            override fun read() = ""
            override fun write(value: String?) = Unit
        }
        var restarted = false
        val repository = AndroidBackupRepository(context, db, passphrases = passphrases, localFiles = files, restartProcess = { restarted = true })
        repository.setLocalFolder(android.net.Uri.parse("content://test/new-phone"))
        assertNotNull(repository.local.value.folder); assertNull(repository.local.value.lastError)
        assertTrue(repository.restoreAllFromLocal("test-only").isSuccess); assertTrue(restarted)
        BackupScheduler(context).cancel()
    }
    @Test fun mockWebDavUsesAtomicMoveAndServerSideRecoveryCopy() {
        val objects = mutableMapOf<String, ByteArray>(); val methods = mutableListOf<String>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse = synchronized(objects) {
                methods += r.method!!
                val path = r.requestUrl!!.encodedPath
                when (r.method) {
                    "PROPFIND" -> MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d='DAV:'/>")
                    "PUT" -> { objects[path] = r.body.readByteArray(); MockResponse().setResponseCode(201) }
                    "MOVE", "COPY" -> { objects[java.net.URI(r.getHeader("Destination")!!).rawPath] = objects.getValue(path)
                        if (r.method == "MOVE") objects.remove(path); MockResponse().setResponseCode(201) }
                    "GET" -> objects[path]?.let { MockResponse().setBody(okio.Buffer().write(it)) } ?: MockResponse().setResponseCode(404)
                    "HEAD" -> MockResponse().setResponseCode(if (path in objects) 200 else 404)
                    "DELETE" -> { objects.remove(path); MockResponse().setResponseCode(204) }
                    else -> MockResponse().setResponseCode(405)
                }
            }
        }
        server.start()
        try {
            store = WebDavClient(BackupConfig(server.url("/dav/").toString()), "回响", "test", "test").volumes()
            add(1, "2026-10-24"); sync(); update(1); sync()
            assertTrue("COPY" in methods); assertTrue("MOVE" in methods); assertEquals(1, state().summary().days)
            assertTrue(File(restore(), BackupArchive.DATABASE).isFile)
        } finally { server.shutdown() }
    }
}
