package app.resound.importer

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.room.Room
import app.resound.capture.SpeechSegmenter
import app.resound.data.*
import app.resound.digest.DigestPrompt
import app.resound.transcribe.*
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
class ImportHotwordsTest {
    private lateinit var context: Context
    private lateinit var db: CaptureDatabase
    private lateinit var scope: CoroutineScope
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("imports", 0).edit().clear().commit()
        context.getSharedPreferences("transcribe", 0).edit().clear().commit()
        File(context.cacheDir, "import").deleteRecursively()
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
    @After fun close() { scope.cancel(); db.close(); File(context.cacheDir, "import").deleteRecursively() }
    private fun uri(name: String, bytes: ByteArray = ByteArray(96_000) { (it % 127).toByte() }): Uri {
        val uri = Uri.parse("content://recordings/$name")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        return uri
    }
    private fun importer(date: String? = null, enqueue: () -> Unit = {}) = AndroidAudioImporter(context, scope,
        inspect = { AudioMetadata(3000, date) }, describe = { SharedAudio("会议.wav", 9000) }, enqueue = enqueue)

    @Test fun receiveCopiesBeforeReturningPersistsAndUsesDateBeforeModifiedTime() = runBlocking {
        val bytes = ByteArray(96_000) { (it % 127).toByte() }
        val importer = importer("20261024T080000Z")
        val pending = importer.receive(listOf(uri("meeting", bytes))).single().getOrThrow()
        assertArrayEquals(bytes, importer.file(pending.id).readBytes())
        assertEquals(Instant.parse("2026-10-24T08:00:00Z").toEpochMilli(), pending.info.suggestedStartAt)
        assertEquals(6000L, suggestedStart("bad-date", 9000, 3000))
        assertNull(suggestedStart(null, null, 3000))
        val restored = importer()
        assertEquals(listOf(pending), restored.pending.value)
        restored.discard(pending.id)
        withTimeout(3000) { restored.pending.first { it.isEmpty() } }
        assertFalse(restored.file(pending.id).exists())
    }

    @Test fun badAudioReturnsHumanReasonAndRemovesCopy() = runBlocking {
        val importer = AndroidAudioImporter(context, scope, inspect = { throw ImportFailure("这个文件里没找到声音") },
            describe = { SharedAudio("坏文件", null) }, enqueue = {})
        val results = importer.receive(listOf(uri("bad", byteArrayOf(1, 2))))
        assertEquals("这个文件里没找到声音", results.single().exceptionOrNull()?.message)
        assertTrue(importer.pending.value.isEmpty())
        assertEquals(0, File(context.cacheDir, "import").listFiles()!!.size)
    }

    @Test fun resamplerKeepsPhaseAcrossBuffersAndAveragesStereo() {
        val converter = StreamingResampler(48_000, 2)
        val input = ShortArray(96_000) { if (it % 2 == 0) 3000 else 1000 }
        val output = input.toList().chunked(733).flatMap { converter.accept(it.toShortArray()).toList() } + converter.finish().toList()
        assertEquals(16_000, output.size)
        assertTrue(output.all { it == 2000.toShort() })
        val ramp = StreamingResampler(24_000, 1)
        assertArrayEquals(shortArrayOf(0, 150, 300, 450), ramp.accept(shortArrayOf(0, 100, 200, 300, 400, 500)))
    }

    @Test fun importedSegmentsKeepOffsetsAndFiveMinuteOverlap() {
        val folder = File(context.cacheDir, "segment-test").apply { deleteRecursively() }
        try {
            ImportSegments(folder, "test", 100_000).use { sink ->
                val segmenter = SpeechSegmenter(sink)
                repeat(2) { segmenter.accept(ShortArray(16_000), false) }
                repeat(301) { segmenter.accept(ShortArray(16_000) { 2000 }, true) }
                segmenter.finish()
                assertEquals(2, sink.ready.size)
                val pieces = sink.ready.toList()
                assertEquals(101_000L, pieces[0].start)
                assertEquals(401_000L, pieces[0].end)
                assertEquals(400_000L, pieces[1].start)
                assertEquals(403_000L, pieces[1].end)
                assertEquals(300 * 16_000 * 2L, pieces[0].pcm.length())
            }
        } finally { folder.deleteRecursively() }
    }

    @Test fun queueUsesConfirmationOrderAndCancellationDoesNotDropFollowingFiles() = runBlocking {
        val importer = importer()
        val items = importer.receive(listOf(uri("a"), uri("b"))).map { it.getOrThrow() }
        importer.start(items[1].id, 2000); importer.start(items[0].id, 1000)
        withTimeout(3000) { importer.progress.first { it.queued == 2 } }
        assertEquals(items[1].id, importer.next()!!.pending.id)
        importer.update(items[1].id, 20, 1)
        assertEquals(1, importer.progress.value.queued)
        importer.cancel(items[1].id)
        assertTrue(importer.isCancelled(items[1].id))
        importer.finish(items[1].id, "已取消导入")
        assertEquals(items[0].id, importer.next()!!.pending.id)
        assertEquals(1000L, importer().next()!!.startAt)
        assertFalse(importer.file(items[1].id).exists())
    }

    @Test fun transcribedImportsDeleteImmediatelyButFailuresWaitAtMostThreeDays() = runBlocking {
        val files = (0..3).map { File.createTempFile("import-test", ".ogg", context.cacheDir).apply { writeBytes(byteArrayOf(1)) } }
        try {
            files.forEachIndexed { i, file -> db.capture().segment(SegmentEntity(startAt = i * 3000L, endAt = i * 3000L + 2000,
                sourceId = if (i == 3) "mic" else "import", path = file.path, sizeBytes = 1)) }
            val provider = object : AsrProvider {
                override suspend fun recognize(audio: AsrAudio, key: String, beforeAudio: suspend () -> Unit): AsrResult {
                    if (audio.file == files[2]) throw AsrException(AsrError.Audio)
                    return AsrResult(if (audio.file == files[1]) emptyList() else listOf(AsrSentence(0, 2000, "开会", null)), 2000, null)
                }
            }
            val voiceprints = app.resound.voiceprint.LocalVoiceprintRepository(db, scope,
                readPcm = { file -> assertTrue(file.exists()); ShortArray(32_000) { 2000 } },
                createEngine = { app.resound.voiceprint.SpeakerEmbedding { FloatArray(192).apply { this[0] = 1f } } }, schedule = {})
            val runner = TranscribeRunner(db.transcribe(), provider, { "test" }, { _, _ -> }, deferAudioCleanup = true,
                afterImport = { voiceprints.run(); voiceprints.cleanupAudio() })
            runner.run()
            val done = db.transcribe().observeSegments().first().single { it.path == files[0].path }
            val line = db.voiceprint().lines(done.id).single()
            assertNotNull(done.voiceprintProcessedAt)
            assertFalse(files[0].exists()); assertFalse(files[1].exists())
            assertTrue(files[2].exists()); assertTrue(files[3].exists())
            voiceprints.nameAndAssign(line.id, "小李").getOrThrow()
            assertEquals(1, db.voiceprint().allSamples().size) // 音频删了，仍能用缓存特征补样本。
            val now = System.currentTimeMillis()
            files[2].setLastModified(now - IMPORT_RETENTION - 1)
            runner.run() // 上传之前也执行三天清理，不依赖周期任务恰好及时调度。
            assertFalse(files[2].exists())
            val failed = db.transcribe().observeSegments().first().single { it.path == files[2].path }
            assertEquals(5, failed.attempts); assertNull(failed.nextRetryAt)
        } finally { files.forEach { it.delete() } }
    }

    @Test fun hotwordsPersistNormalizeAndTrackPeopleRenamesAndDeletes(): Unit = runBlocking {
        val settings = AndroidHotwordSettings(context, db.voiceprint(), scope)
        settings.setWords(listOf(" 阿涛 ", "阿涛", "", "x".repeat(21)) + (1..205).map { "术语$it" })
        assertEquals(200, settings.words.value.size); assertEquals("阿涛", settings.words.value.first())
        assertEquals(settings.words.value, AndroidHotwordSettings(context, db.voiceprint(), scope).words.value)
        val person = db.voiceprint().insertPerson(VoiceprintPersonEntity(name = "小李", createdAt = 0))
        db.voiceprint().insertPerson(VoiceprintPersonEntity(name = "我", createdAt = 0))
        withTimeout(3000) { settings.automatic.first { it == listOf("小李") } }
        db.voiceprint().renamePerson(person, "李明")
        withTimeout(3000) { settings.automatic.first { it == listOf("李明") } }
        assertEquals("阿涛", settings.merged().first()); assertEquals("李明", settings.merged().last())
        db.voiceprint().deletePerson(person)
        withTimeout(3000) { settings.automatic.first { it.isEmpty() } }
        // 仅以假凭证验证备份白名单；恢复热词不覆盖本机其余转写配置。
        val prefs = context.getSharedPreferences("transcribe", 0)
        prefs.edit().putString("encryptedKey", "fake-device-bound").putString("model", "kept-model").commit()
        val staging = File(context.cacheDir, "hotword-backup").apply { deleteRecursively(); mkdirs() }
        val xml = File(staging, "shared_prefs/transcribe.xml")
        app.resound.backup.BackupArchive.writeHotwords(context, xml)
        assertFalse(xml.readText().contains("encryptedKey")); assertFalse(xml.readText().contains("fake-device-bound"))
        prefs.edit().putString("hotwords", "[]").commit()
        app.resound.backup.BackupRestore.stage(context, staging)
        app.resound.backup.BackupRestore.applyPending(context)
        assertEquals(200, org.json.JSONArray(prefs.getString("hotwords", "[]")).length())
        assertEquals("kept-model", prefs.getString("model", null))
        // 029 规则：恢复后所有密钥清空、需重填
        assertEquals(null, prefs.getString("encryptedKey", null))
    }

    @Test fun cloudRequestsIncludePrioritizedHotwordsAndBoundCustomPrompt() {
        val words = Hotwords.merge(listOf("手动词"), listOf("自动词", "手动词")) + (1..5000).map { "词$it" }
        val request = JSONObject(String(DoubaoProtocol.config("pcm", words))).getJSONObject("request")
        val hotwords = JSONObject(request.getJSONObject("corpus").getString("context")).getJSONArray("hotwords")
        assertEquals(5000, hotwords.length()); assertEquals("手动词", hotwords.getJSONObject(0).getString("word"))
        val file = File.createTempFile("custom", ".ogg", context.cacheDir)
        try {
            val body = customAsrBody(AsrAudio(file, "ogg", 1000), "test", words)
            val part = body.parts.single { it.headers?.get("Content-Disposition")?.contains("name=\"prompt\"") == true }
            val buffer = okio.Buffer(); part.body.writeTo(buffer)
            val prompt = buffer.readUtf8()
            assertTrue(prompt.startsWith("可能出现的词：手动词、自动词")); assertEquals(200, prompt.length)
        } finally { file.delete() }
    }

    @Test fun digestInputAddsScopedCorrectionWithoutChangingOriginalLines() {
        val text = "[1] 08:00:00 某人：让肖李处理"
        val input = DigestPrompt.withHotwords(text, listOf("小李", "回响"))
        assertTrue(input.contains("常见人名与专有名词：小李、回响"))
        assertTrue(input.contains("只纠正这些词的同音错字，不改别的原话"))
        assertTrue(input.endsWith(text)); assertEquals(text, DigestPrompt.withHotwords(text, emptyList()))
    }
}
