package app.resound.transcribe

import android.app.Application
import androidx.room.Room
import app.resound.data.*
import app.resound.privacy.RoomPrivacyActions
import app.resound.search.SearchIndex
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class AudioExport068Test {
    private lateinit var root: File
    private val outputs get() = File(root, "exports")
    @Before fun setup() { root = File(RuntimeEnvironment.getApplication().cacheDir, "export-${System.nanoTime()}").apply { mkdirs() } }
    @After fun close() { root.deleteRecursively() }
    private fun event(scene: String = "其他") = MemoryEventEntity(1, 0, 100_000, scene, "讨论", "摘要", "[]", "[\"小王\"]", null, null, null, false, createdAt = 0, model = "test")
    private fun source(id: Long, audio: Boolean = true, voice: Boolean = false, events: List<MemoryEventEntity> = emptyList()): ExportSource {
        val file = File(root, "$id.ogg")
        if (audio) file.writeText("audio")
        val start = id * 10_000
        return ExportSource(SegmentWithLines(SegmentEntity(id, start, start + 2000, "mic", file.path, file.length()),
            listOf(TranscriptLineEntity(id, id, start + 500, start + 1500, "原话$id", "0",
                voiceInputJson = if (voice) "{\"app\":\"微信\",\"package_name\":\"wechat\"}" else null))), events)
    }
    private class Sink(val target: File) : ExportPcmSink {
        var bytes = 0L
        override suspend fun append(pcm: File) { pcm.inputStream().use { input -> val block = ByteArray(8192); while (true) { currentCoroutineContext().ensureActive(); val n = input.read(block); if (n < 0) break; bytes += n } } }
        override suspend fun silence() { bytes += 16000 }
        override suspend fun finish() { target.writeText(bytes.toString()) }
        override fun close() {}
    }
    private fun exporter(sources: List<ExportSource>, decode: suspend (File, File) -> Unit = { _, to -> to.writeBytes(ByteArray(64000)) }) =
        LocalAudioExporter(outputs, { sources }, decode, ::Sink)
    private suspend fun done(exporter: AudioExporter, request: ExportRequest = ExportRequest(listOf(1, 2, 3), "测试")) =
        (exporter.export(request).toList().last() as ExportProgress.Done).files

    @Test fun voiceAndExpiredRemainTextOnly() = runBlocking {
        val exporter = exporter(listOf(source(1), source(2, audio = false, voice = true), source(3, audio = false)))
        assertEquals(ExportEstimate(1, 1, 1, 0, 2000, 16000), exporter.estimate(ExportRequest(listOf(1, 2, 3), "测试")))
        val files = done(exporter)
        val txt = files.first { it.extension == "txt" }.readText()
        val srt = files.first { it.extension == "srt" }.readText()
        assertTrue(txt.contains("[—]")); assertTrue(txt.contains("⌨ 语音输入 · 微信（无录音）")); assertTrue(txt.contains("原话3"))
        assertFalse(srt.contains("原话2")); assertFalse(srt.contains("原话3"))
        assertEquals("64000", files.first().readText())
    }
    @Test fun skipMediaExcludesAudioTextAndMetadata() = runBlocking {
        val exporter = exporter(listOf(source(1, events = listOf(event("媒体"))), source(2)))
        assertEquals(1, exporter.estimate(ExportRequest(listOf(1, 2), "测试")).mediaSkipped)
        val txt = done(exporter).first { it.extension == "txt" }.readText()
        assertFalse(txt.contains("原话1")); assertFalse(txt.contains("讨论")); assertTrue(txt.contains("原话2"))
        assertEquals(2, exporter.estimate(ExportRequest(listOf(1, 2), "测试", skipMedia = false)).audioSegments)
    }
    @Test fun badSegmentSkippedAndActualPcmTimelineMatchesSubtitles() = runBlocking {
        val sources = listOf(source(3, events = listOf(event())), source(2), source(1))
        val exporter = exporter(sources) { from, to -> if (from.name == "2.ogg") error("bad"); to.writeBytes(ByteArray(64000)) }
        val files = done(exporter, ExportRequest(listOf(3, 2, 1), " /\\:*?\"<>|测试 "))
        assertTrue(files.all { it.nameWithoutExtension == "测试" })
        assertEquals("144000", files.first().readText()) // 2 + 0.5 + 2 秒
        val txt = files.first { it.extension == "txt" }.readText()
        val srt = files.first { it.extension == "srt" }.readText()
        assertTrue(txt.contains("[00:03]")); assertTrue(srt.contains("00:00:03,000 --> 00:00:04,000"))
        assertTrue(txt.contains("有 1 段录音读不出来")); assertTrue(txt.contains("小王")); assertTrue(txt.contains("说话人 1"))
        assertFalse(srt.contains("原话2"))
        val failed = exporter(sources) { _, _ -> error("bad") }.export(ExportRequest(listOf(1, 2, 3), "失败")).toList().last()
        assertTrue(failed is ExportProgress.Failed)
    }
    @Test fun cancellationRemovesPartialsAndCleanupOnlyRemovesExpiredExports() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val exporter = exporter(listOf(source(1))) { _, to -> to.writeText("partial"); entered.complete(Unit); awaitCancellation() }
        val job = launch { exporter.export(ExportRequest(listOf(1), "取消")).collect() }
        entered.await(); job.cancelAndJoin()
        assertTrue(outputs.listFiles().orEmpty().isEmpty())
        val old = File(outputs, "old.txt").apply { writeText("old"); setLastModified(System.currentTimeMillis() - 86_400_001) }
        val fresh = File(outputs, "fresh.txt").apply { writeText("fresh") }
        exporter.cleanup(); assertFalse(old.exists()); assertTrue(fresh.exists())
    }
    @Test fun batchDeletionKeepsEventsTodosAndOtherSegmentsButRemovesSearchAndVectors() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CaptureDatabase::class.java)
            .addCallback(SearchIndex.callback).allowMainThreadQueries().build()
        try {
            val selected = source(1); val kept = source(2)
            db.memory().insertEvent(event().copy(endAt = 1000))
            for (s in listOf(selected, kept)) {
                db.capture().segment(s.row.segment)
                db.transcribe().insertLines(s.row.lines.map { it.copy(eventId = 1) })
            }
            assertEquals(1L, db.transcribe().exportEvents(1, 10000, 12000).single().id)
            assertEquals(2, LocalAudioExporter(root, db.transcribe()).estimate(ExportRequest(listOf(2, 1, 1), "数据库")).audioSegments)
            val todo = MemoryItemEntity(1, 1, "Todo", null, "保留待办", null, null, null, 1, "Pending", 0)
            db.memory().insertItems(listOf(todo))
            val sql = db.openHelper.writableDatabase
            sql.execSQL("INSERT INTO embeddings VALUES ('line',1,'test',1,X'00000000',0)")
            val deleter = RoomPrivacyActions(db)
            assertEquals(1, deleter.deleteSegments(listOf(1, 1, 999)))
            assertNull(db.privacy().segment(1)); assertNotNull(db.privacy().segment(2))
            assertTrue(db.voiceprint().lines(1).isEmpty()); assertNotNull(db.memory().event(1))
            fun count(query: String) = sql.query(query).use { it.moveToFirst(); it.getInt(0) }
            assertEquals(0, count("SELECT COUNT(*) FROM lines_fts WHERE rowid=1"))
            assertEquals(0, count("SELECT COUNT(*) FROM embeddings WHERE kind='line' AND refId=1"))
            assertEquals(1, count("SELECT COUNT(*) FROM memory_items WHERE id=1 AND lineId=1"))
            assertFalse(File(selected.row.segment.path).exists()); assertTrue(File(kept.row.segment.path).exists())
            assertEquals(0, deleter.deleteSegments(listOf(1)))
        } finally { db.close() }
    }
    @Test fun twoHourPcmUsesBoundedChunks() = runBlocking {
        val runtime = Runtime.getRuntime()
        val before = runtime.totalMemory() - runtime.freeMemory()
        var peak = before
        val start = System.nanoTime()
        val exporter = exporter(listOf(source(1), source(2))) { _, to ->
            to.outputStream().buffered().use { output ->
                val block = ByteArray(32000)
                repeat(3600) {
                    output.write(block)
                    peak = maxOf(peak, runtime.totalMemory() - runtime.freeMemory())
                }
            }
        }
        val files = done(exporter)
        assertEquals((7200L * 32000 + 16000).toString(), files.first().readText())
        assertTrue(outputs.walkTopDown().none { it.extension == "pcm" })
        println("068 synthetic PCM: 7200s, elapsedMs=${(System.nanoTime() - start) / 1_000_000}, heapDeltaBytes=${peak - before}, maxPcmOnDisk=115200000, inputBlock=32000, readBlock=8192; codec excluded")
    }
}
