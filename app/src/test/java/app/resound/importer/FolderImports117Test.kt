package app.resound.importer

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.room.Room
import app.resound.backup.BackupSettings
import app.resound.backup.DayVolumeRows
import app.resound.data.CaptureDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class FolderImports117Test {
    private lateinit var context: Context
    private lateinit var db: CaptureDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var importer: AndroidAudioImporter
    private lateinit var source: FakeDocuments
    private lateinit var folders: AndroidFolderImports
    private val tree = Uri.parse("content://recordings/tree/root")
    private var now = 1_000_000L
    private var date: String? = null
    private var wakeups = 0
    private val notifications = mutableListOf<Int>()
    private inner class FakeDocuments : FolderDocuments {
        var allowed = true
        var unreadable: Uri? = null
        var rows = mutableListOf<FolderDocument>()
        val bytes = mutableMapOf<Uri, ByteArray>()
        override fun acquire(tree: Uri) { if (!allowed) throw SecurityException() }
        override fun granted(tree: Uri) = allowed
        override fun root(tree: Uri) = FolderDocument(tree, "录音笔", 0, 0, android.provider.DocumentsContract.Document.MIME_TYPE_DIR)
        override fun files(tree: Uri) = rows.toList()
        override fun stat(uri: Uri) = rows.single { it.uri == uri }
        override fun fingerprint(document: FolderDocument): String {
            if (document.uri == unreadable) throw java.io.IOException("文件暂不可读")
            return audioFingerprint(ByteArrayInputStream(bytes.getValue(document.uri)))
        }
        fun file(name: String = "new.wav", content: ByteArray = ByteArray(150_000) { (it % 97).toByte() }, modified: Long = 500_000L): FolderDocument {
            val uri = Uri.parse("content://recordings/document/$name")
            val row = FolderDocument(uri, name, content.size.toLong(), modified)
            rows.removeAll { it.uri == uri }; rows += row; bytes[uri] = content
            shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(content) }
            return row
        }
    }
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        listOf("imports", "folder-imports").forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        File(context.cacheDir, "import").deleteRecursively()
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        source = FakeDocuments()
        importer = AndroidAudioImporter(context, scope, inspect = { AudioMetadata(3000, date) },
            describe = { SharedAudio("share.wav", 500_000) }, enqueue = { wakeups++ }, fingerprints = db.folderImports())
        folders = repository()
    }
    private fun repository() = AndroidFolderImports(context, db, scope, importer, source, { now }, { _, _, count -> notifications += count }, {})
    @After fun close() = runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin(); db.close(); File(context.cacheDir, "import").deleteRecursively(); Unit }

    @Test fun stableFileImportsOnceEvenAfterRenameAndRepositoryRestart() = runBlocking {
        val original = source.file()
        folders.addFolder(tree, ImportKind.Training, true)
        assertNull(importer.next()) // 第一次只记大小/时间。
        folders.scan()
        val entry = requireNotNull(importer.next())
        assertEquals(ImportKind.Training, entry.kind); assertNull(entry.asr)
        assertEquals(original.modified, entry.startAt); assertTrue(importer.pending.value.isEmpty())
        assertEquals(listOf(1), notifications); assertEquals(1, folders.state.value.lastImported)
        assertEquals(now, folders.state.value.lastImportedAt)
        importer.finish(entry.pending.id, null)
        source.rows.clear(); source.file("child/renamed.wav", source.bytes.getValue(original.uri))
        folders = repository(); folders.scan(); folders.scan()
        assertNull(importer.next()); assertEquals(1, folders.state.value.folders.single().imported)
        assertArrayEquals(source.bytes.getValue(original.uri), source.bytes.values.first())
    }

    @Test fun changingSizeAndRecentModificationWaitForTwoStableScans() = runBlocking {
        source.file(modified = now - 1000)
        folders.addFolder(tree, ImportKind.Normal, true); folders.scan(); assertNull(importer.next())
        source.file(content = ByteArray(160_000) { 2 }, modified = now - 1000)
        now += 180_000
        folders.scan(); assertNull(importer.next())
        folders.scan(); assertNotNull(importer.next())
    }

    @Test fun oldFilesIncludingEmptyGrowingFileAreIgnoredButNewFileImports() = runBlocking {
        source.file("old.wav"); source.file("empty.wav", ByteArray(0))
        folders.addFolder(tree, ImportKind.Normal, false)
        source.file("empty.wav", byteArrayOf(1, 2, 3))
        folders.scan(); assertNull(importer.next())
        source.file("new.wav", ByteArray(100) { 7 })
        folders.scan(); folders.scan()
        assertEquals("new.wav", importer.next()!!.pending.info.displayName)
        assertEquals(1, folders.state.value.folders.single().imported)
    }

    @Test fun baselineReadFailureDoesNotTreatLaterFilesAsOld() = runBlocking {
        val old = source.file("old.wav")
        source.unreadable = old.uri
        folders.addFolder(tree, ImportKind.Normal, false)
        assertNotNull(folders.state.value.folders.single().lastError)
        source.file("new.wav", byteArrayOf(6, 4, 2))
        folders.scan(); folders.scan()
        assertEquals("new.wav", importer.next()!!.pending.info.displayName)
        source.unreadable = null
        folders.scan()
        assertEquals(1, folders.state.value.folders.single().imported)
    }

    @Test fun permissionLossStopsScanningUntilReselectedAndRestoredSettingsRequireGrant() = runBlocking {
        source.file(); folders.addFolder(tree, ImportKind.Call, true)
        source.allowed = false; folders.scan()
        assertFalse(folders.state.value.folders.single().accessible)
        assertTrue(folders.state.value.folders.single().lastError!!.contains("重新选择"))
        val restored = repository()
        assertFalse(restored.state.value.folders.single().accessible)
        source.allowed = true; restored.scan(); assertNull(importer.next())
        restored.addFolder(tree, ImportKind.Call, true); restored.scan()
        assertNotNull(importer.next())
    }

    @Test fun sharedQueuedAndFinishedAudioIsNotImportedAgainAndMetadataWins() = runBlocking {
        val file = source.file()
        date = "20261024T080000Z"
        val shared = importer.receive(listOf(file.uri)).single().getOrThrow()
        importer.start(shared.id, shared.info.suggestedStartAt!!)
        withTimeout(3000) { importer.progress.first { it.queued == 1 } }
        // 先分享入队，文件夹即使立即扫描也不应重复。
        folders.addFolder(tree, ImportKind.Normal, true); folders.scan()
        assertEquals(0, folders.state.value.folders.single().imported)
        importer.rememberQueued(); importer.finish(shared.id, null)
        source.file("other.wav", byteArrayOf(9, 7, 6)); folders.scan(); folders.scan()
        assertEquals(Instant.parse("2026-10-24T08:00:00Z").toEpochMilli(), importer.next()!!.startAt)
        assertEquals(1, folders.state.value.folders.single().imported)
    }

    @Test fun copyThatChangesAfterHashIsRejectedWithoutPendingOrQueue() = runBlocking {
        val file = source.file()
        val key = source.fingerprint(file)
        val result = runCatching { importer.receiveFolder(file, ImportKind.Normal, key) { false } }
        assertTrue(result.isFailure); assertNull(importer.next()); assertTrue(importer.pending.value.isEmpty())
        assertEquals(0, File(context.cacheDir, "import").listFiles()!!.size)
    }

    @Test fun fingerprintUsesSizeHeadAndTailAndSettingsAreBackedUp() = runBlocking {
        val data = ByteArray(200_000) { (it % 127).toByte() }
        val key = audioFingerprint(ByteArrayInputStream(data))
        data[90_000] = 3; assertEquals(key, audioFingerprint(ByteArrayInputStream(data)))
        data[data.lastIndex] = 42; assertNotEquals(key, audioFingerprint(ByteArrayInputStream(data)))
        assertNotEquals(key, audioFingerprint(ByteArrayInputStream(data.copyOf(199_999))))
        folders.addFolder(tree, ImportKind.Training, false)
        val directory = File(context.cacheDir, "folder-backup").apply { mkdirs() }
        try {
            BackupSettings.capture(context, directory) { _, _ -> "" }
            assertTrue(JSONObject(File(directory, "settings.json").readText()).has("folder-imports"))
            context.getSharedPreferences("folder-imports", 0).edit().clear().commit()
            BackupSettings.apply(context, directory) { _, _, _ -> }
            source.allowed = false
            assertFalse(repository().state.value.folders.single().accessible)
            assertTrue(DayVolumeRows.global.containsAll(listOf("import_fingerprints", "folder_observations")))
        } finally { directory.deleteRecursively() }
    }

    @Test fun migrationFrom29PreservesDataAndAddsPersistentFingerprintTables() = runBlocking {
        val name = "folder117-migration.db"
        context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/app.resound.data.CaptureDatabase/29.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { i ->
                val entity = entities.getJSONObject(i)
                fun sql(value: String) = value.replace("\u0024{TABLE_NAME}", entity.getString("tableName"))
                old.execSQL(sql(entity.getString("createSql")))
                val indices = entity.optJSONArray("indices")
                if (indices != null) repeat(indices.length()) { old.execSQL(sql(indices.getJSONObject(it).getString("createSql"))) }
            }
            val queries = schema.getJSONArray("setupQueries")
            repeat(queries.length()) { old.execSQL(queries.getString(it)) }
            old.execSQL("INSERT INTO battery VALUES (1,1000,80,0)")
            old.version = 29
        }
        val migrated = Room.databaseBuilder(context, CaptureDatabase::class.java, name).allowMainThreadQueries()
            .addMigrations(*CaptureDatabase.MIGRATIONS).build()
        try {
            assertEquals(CaptureDatabase.VERSION, migrated.openHelper.writableDatabase.version)
            assertEquals(80, migrated.capture().batteries(0, 2000).first().single().percent)
            migrated.folderImports().remember(ImportFingerprint("3:abc", now))
            migrated.folderImports().observe(listOf(FolderObservation("root", "doc", 3, now, false)))
            assertTrue(migrated.folderImports().contains("3:abc"))
            assertEquals(3L, migrated.folderImports().observations("root").single().size)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
