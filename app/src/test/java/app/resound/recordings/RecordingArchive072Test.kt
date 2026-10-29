package app.resound.recordings

import android.app.Application
import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.resound.data.*
import app.resound.push.*
import java.io.*
import java.time.*
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class RecordingArchive072Test {
    private lateinit var root: File
    private val today = LocalDate.of(2026, 10, 29)
    private val now get() = today.atTime(16, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    @Before fun setup() { root = createTempDir(prefix = "archive072-") }
    @After fun cleanup() { root.deleteRecursively() }
    private fun source(id: Long, day: LocalDate = today, status: String = "Done", text: String = "你好", excluded: Boolean = false): RecordingSource {
        val at = day.atTime(14, 5, 33).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val f = File(root, "$id.ogg").apply { writeText("OGG-$id") }
        return RecordingSource(SegmentWithLines(SegmentEntity(id, at, at + 192000, "mic", f.path, f.length(), status),
            if (text.isEmpty()) emptyList() else listOf(TranscriptLineEntity(id, id, at, at + 1000, text, "1", "阿涛"))), excluded)
    }
    @Test fun twoDaysZipStoresOriginalsAndTextWithCollisionNames() = runBlocking {
        val rows = listOf(source(1, today.minusDays(1)), source(2), source(3, text = ""))
        val file = File(root, "pack.zip")
        RecordingZip.write(file.outputStream(), rows, true) {}
        ZipFile(file).use { zip ->
            assertEquals(7, zip.size())
            // 命名：日期时刻 + 所属事件标题；没整理用原话首句，没转写写“未转写”
            assertEquals("OGG-3", zip.getInputStream(zip.getEntry("2026-10-29/2026-10-29 14.05 未转写.ogg")).reader().readText())
            assertEquals("OGG-2", zip.getInputStream(zip.getEntry("2026-10-29/2026-10-29 14.05 你好.ogg")).reader().readText())
            assertTrue(zip.entries().asSequence().all { it.method == 0 })
            val table = zip.getInputStream(zip.getEntry("原话.txt")).reader().readText()
            assertTrue(table.contains("阿涛：你好")); assertTrue(table.contains("（未转写）")); assertEquals(3, table.lines().filter { it.isNotBlank() }.size)
        }
        assertEquals("涛声录音 2026-10-28 至 2026-10-29.zip", RecordingZip.name(RecordingRange(today.minusDays(1), today)))
        assertEquals("涛声录音 2026-10-29.zip", RecordingZip.name(RecordingRange(today, today)))
    }
    @Test fun estimateAndSkipMediaOnlyCountExistingOriginals() {
        val rows = listOf(source(1), source(2, excluded = true), source(3).also { it.file.delete() })
        val selected = RecordingZip.select(rows, RecordingRange(today, today), RecordingPackOptions(skipMedia = true))
        assertEquals(RecordingPackEstimate(1, 1, 5, 192000), RecordingZip.estimate(selected))
    }
    @Test fun speechFilteringAndFirstEnableRange() = runBlocking {
        val rows = listOf(source(1), source(2, status = "Pending"), source(3, excluded = true), source(4, text = ""), source(5, day = today.minusDays(31)))
        val state = RecordingUploadState(File(root, "state.json"))
        assertEquals(listOf(1L), state.candidates(rows, PushSubscription(audio = AudioPush.Speech), today).map { it.id })
        assertEquals(listOf(1L, 2L, 4L), state.candidates(rows, PushSubscription(audio = AudioPush.All), today).map { it.id })
    }
    private class Remote : RecordingRemote {
        val audio = mutableListOf<String>(); val text = mutableListOf<String>()
        override suspend fun audio(path: String, file: File) { audio += path }
        override suspend fun text(path: String, content: String) { text += content }
    }
    @Test fun acknowledgementSurvivesRestartDeletionAndTextRedo() = runBlocking {
        val row = source(1); val remote = Remote(); val file = File(root, "state.json")
        val sub = PushSubscription(audio = AudioPush.All)
        RecordingUploader(RecordingUploadState(file), remote).run(listOf(row), sub, now)
        row.file.delete()
        val changed = row.copy(row = row.row.copy(lines = row.row.lines.map { it.copy(text = "修正后的原话") }))
        val state = RecordingUploadState(file)
        RecordingUploader(state, remote).run(listOf(changed), sub, now)
        RecordingUploader(state, remote).run(listOf(changed), sub, now)
        assertEquals(1, remote.audio.size); assertEquals(2, remote.text.size)
        val status = state.status(listOf(changed), sub, now)
        assertEquals(1, status.uploaded); assertEquals(0, status.pending)
        assertEquals(5L + row.text.toByteArray().size + changed.text.toByteArray().size, status.bytesThisMonth)
        assertTrue(status.monthlyEstimateBytes!! > 0)
    }
    @Test fun allPushesBeforeTranscriptAndLaterAddsText() = runBlocking {
        val row = source(1, status = "Pending", text = ""); val remote = Remote()
        val state = RecordingUploadState(File(root, "state.json")); val uploader = RecordingUploader(state, remote)
        val sub = PushSubscription(audio = AudioPush.All)
        uploader.run(listOf(row), sub, now)
        assertEquals(1, remote.audio.size); assertTrue(remote.text.isEmpty())
        uploader.run(listOf(source(1)), sub, now)
        assertEquals(1, remote.audio.size); assertEquals(1, remote.text.size)
    }
    @Test fun legacySubscriptionsAndCapabilitiesStayOffOutsideFolder() {
        val json = Subscriptions.json(PushSubscription()).apply { remove("audio"); remove("audioWithText"); remove("audioWifiOnly") }
        assertEquals(AudioPush.Off, Subscriptions.read(json).audio)
        val context = RuntimeEnvironment.getApplication() as Context
        val channels = AndroidPushChannels(context)
        PushKind.entries.forEach { assertEquals(it == PushKind.MarkdownFolder, channels.capabilities(it).audio) }
        val value = PushSubscription(audio = AudioPush.All, audioWithText = false, audioWifiOnly = false)
        assertEquals(value, Subscriptions.read(Subscriptions.json(value)))
        assertEquals(AudioPush.Off, Subscriptions.tighten(value, channels.capabilities(PushKind.Webhook)).audio)
    }
    @Test fun fakeDavWritesOggAndTextWithoutDeleteAndMapsErrors() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(201) }
            server.start()
            val remote = RecordingDav(mapOf("url" to server.url("/dav/").toString(), "folder" to "涛声", "account" to "fake", "password" to "fake"))
            val row = source(1)
            remote.audio("录音/$today/${row.stem}.ogg", row.file)
            remote.text("录音/$today/${row.stem}.txt", row.text)
            val requests = (1..server.requestCount).map { server.takeRequest() }
            assertEquals(listOf("MKCOL", "MKCOL", "MKCOL", "PUT", "PUT"), requests.map { it.method })
            assertEquals("OGG-1", requests[3].body.readUtf8())
            for ((code, expected) in listOf(401 to "坚果云账号或应用密码不对", 507 to "坚果云本月上传流量用完了")) {
                server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(code) }
                val error = runCatching { remote.audio("录音/$today/${row.stem}.ogg", row.file) }.exceptionOrNull()
                assertEquals(expected, error?.message)
            }
            assertEquals("坚果云本月上传流量用完了", RecordingUploadErrors.status(403, "upload quota exceeded"))
        }
    }
    @Test fun rebuildWhitelistCannotDeleteRecordingsOrUserNotes() {
        assertFalse(FolderSync.owned("录音/2026-10-29/14-05-33 3分12秒.ogg"))
        assertFalse(FolderSync.owned("录音/2026-10-29/14-05-33 3分12秒.txt"))
        assertFalse(FolderSync.owned("my-notes/note.md"))
        assertTrue(FolderSync.owned("README.md"))
    }
    private class Downloads(private val file: File) : ContentProvider() {
        var deleted = false
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/zip"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor = MatrixCursor(arrayOf("_id"))
        override fun insert(uri: Uri, values: ContentValues?): Uri = Uri.parse("content://media/external/downloads/1")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 1
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { deleted = true; file.delete(); return 1 }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(file,
            if (mode == "r") ParcelFileDescriptor.MODE_READ_ONLY else ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
    }
    @Test fun cancellationDeletesMediaStorePartialAndPersistsCancelled() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("recording-pack", 0).edit().clear().commit()
        val output = File(root, "partial.zip"); val provider = Downloads(output)
        provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        val rows = listOf(source(1), source(2)); val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val packer = AndroidRecordingPacker(context, { rows }, scope, enqueue = {}, stop = {})
            val id = packer.start(RecordingRange(today, today), RecordingPackOptions())
            try { packer.run(id) { packer.cancel(id) }; fail("应取消") } catch (_: CancellationException) { }
            assertTrue(provider.deleted); assertFalse(output.exists()); assertEquals(RecordingPackState.Cancelled, packer.current.value?.second)
            assertNull(packer.shareUri(id))
        } finally { scope.cancel() }
    }
    @Test fun wifiConstraintsAreIndependentFromTextPush() {
        assertEquals(androidx.work.NetworkType.UNMETERED, RecordingAudioPush.request(1, true).workSpec.constraints.requiredNetworkType)
        assertEquals(androidx.work.NetworkType.CONNECTED, RecordingAudioPush.request(1, false).workSpec.constraints.requiredNetworkType)
    }
}
