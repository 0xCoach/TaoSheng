package app.resound.transcribe

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class AudioExport115Test {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var scope: CoroutineScope
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        root = File(context.cacheDir, "export115-${System.nanoTime()}").apply { mkdirs() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        context.getSharedPreferences("export-jobs", 0).edit().clear().commit()
    }
    @After fun close() { scope.cancel(); root.deleteRecursively() }
    private fun event(id: Long) = MemoryEventEntity(id, id * 10000, id * 10000 + 2000,
        "工作", "事件$id", "摘要$id", "[]", "[\"小王\"]", null, null, null, false, createdAt = 0, model = "test")
    private fun item(id: Long, eventId: Long = 1, status: String = "Accepted", category: String = "待办") =
        MemoryItemEntity(id, eventId, category, null, "事项$id", "2026-11-10", null, null, null, status, id)
    private fun source(id: Long, events: List<MemoryEventEntity> = listOf(event(id)), items: List<MemoryItemEntity> = emptyList()): ExportSource {
        val file = File(root, "$id.ogg").apply { writeText("audio") }
        val start = id * 10000
        return ExportSource(SegmentWithLines(SegmentEntity(id, start, start + 2000, "mic", file.path, file.length()),
            listOf(TranscriptLineEntity(id, id, start + 500, start + 1500, "原话$id", "0"))), events, items)
    }
    private fun textExporter(rows: List<ExportSource>) = LocalAudioExporter(File(root, "exports"), { rows },
        { _, _ -> error("纯文字不能解码") }, { error("纯文字不能创建编码器") })
    private fun request(parts: Set<ExportTextPart> = setOf(ExportTextPart.Lines), format: ExportTextFormat = ExportTextFormat.Txt) =
        ExportRequest(listOf(2, 1, 1), "测试", includeAudio = false, textParts = parts, textFormat = format)
    private suspend fun files(exporter: AudioExporter, request: ExportRequest) =
        (exporter.export(request).toList().last() as ExportProgress.Done).files

    @Test fun textOnlyWritesOneBomTxtWithoutAudioPositionsOrSubtitleAndZeroEstimate() = runBlocking {
        val exporter = textExporter(listOf(source(2), source(1)))
        assertEquals(ExportEstimate(2, 0, 0, 0, 0, 0, 0), exporter.estimate(request()))
        val file = files(exporter, request()).single()
        assertEquals("txt", file.extension)
        assertArrayEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), file.readBytes().take(3).toByteArray())
        val text = file.readText()
        assertTrue(text.contains("人名：小王")); assertTrue(text.contains("说话人 1：原话1"))
        assertTrue(text.indexOf("原话1") < text.indexOf("原话2"))
        assertFalse(text.contains("总时长")); assertFalse(text.contains("[—]")); assertFalse(text.contains("[00:"))
        assertFalse(text.contains("摘要1")); assertFalse(text.contains("事件1"))
        assertEquals(listOf(file), File(root, "exports").walkTopDown().filter { it.isFile }.toList())
    }

    @Test fun allMarkdownPartsAreOrderedAndEventsAndTodosDeduplicated() = runBlocking {
        val events = listOf(event(2), event(1))
        val items = listOf(item(1), item(2, status = "Done", category = "约定"))
        val rows = listOf(source(2, events, items), source(1, events, items), source(1, events, items))
        val file = files(textExporter(rows), request(ExportTextPart.entries.reversed().toSet(), ExportTextFormat.Markdown)).single()
        val text = file.readText()
        assertEquals("md", file.extension); assertTrue(text.startsWith("# 测试\n"))
        assertTrue(text.indexOf("## 摘要") < text.indexOf("## 待办")); assertTrue(text.indexOf("## 待办") < text.indexOf("## 原话"))
        assertTrue(text.indexOf("摘要1") < text.indexOf("摘要2"))
        assertEquals(1, Regex("摘要1").findAll(text).count()); assertEquals(1, Regex("事项1").findAll(text).count())
        assertEquals(1, Regex("原话1").findAll(text).count())
        assertTrue(text.contains("- [ ] 未完成 · 待办：事项1 · 到期：2026\\-11\\-10"))
        assertTrue(text.contains("- [x] 已完成 · 约定：事项2"))
        assertTrue(text.contains("说话人 1：原话1  \n"))
    }

    @Test fun emptySelectionFailsWithExplanationAndNoFiles() = runBlocking {
        val result = textExporter(listOf(source(1))).export(request(emptySet())).toList().last()
        assertEquals(ExportProgress.Failed("没有选要导出的内容"), result)
        assertTrue(File(root, "exports").listFiles().orEmpty().isEmpty())
    }

    @Test fun selectedSectionsOnlyAndMarkdownContentEscaped() = runBlocking {
        val row = source(1).let { it.copy(events = listOf(event(1).copy(title = "# 事件", summary = "<script>\n- [x] 内容"))) }
        val text = files(textExporter(listOf(row)), request(setOf(ExportTextPart.Summary), ExportTextFormat.Markdown)).single().readText()
        assertTrue(text.contains("\\# 事件")); assertTrue(text.contains("\\<script\\> \\- \\[x\\] 内容"))
        assertFalse(text.contains("## 待办")); assertFalse(text.contains("## 原话")); assertFalse(text.contains("原话1"))
    }

    @Test fun databaseLoadsEventTodosAndFiltersDeletedMergedIgnoredAndUnrelated() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).allowMainThreadQueries().build()
        try {
            val row = source(1)
            db.capture().segment(row.row.segment); db.transcribe().insertLines(row.row.lines)
            db.memory().insertEvent(event(1)); db.memory().insertEvent(event(2))
            db.memory().insertItems(listOf(item(1), item(2, status = "Done", category = "约定"),
                item(3).copy(deleted = true), item(4).copy(mergedInto = 1), item(5, status = "Ignored"),
                item(6, category = "灵感"), item(7, eventId = 2)))
            val text = files(LocalAudioExporter(root, db.transcribe()), request(setOf(ExportTextPart.Todos))).single().readText()
            assertTrue(text.contains("未完成 · 待办：事项1")); assertTrue(text.contains("已完成 · 约定：事项2"))
            for (id in 3..7) assertFalse(text.contains("事项$id"))
        } finally { db.close() }
    }

    @Test fun audioOnlyCanStillProduceSubtitlesWithoutText() = runBlocking {
        val exporter = LocalAudioExporter(File(root, "exports"), { listOf(source(1)) }, { _, send -> send(ByteArray(64000)) },
            { target -> object : ExportPcmSink {
                override suspend fun append(pcm: ByteArray) {}
                override suspend fun silence() {}
                override suspend fun finish() { target.writeText("audio") }
                override fun close() {}
            } })
        val result = files(exporter, request(emptySet()).copy(includeAudio = true))
        assertEquals(listOf("m4a", "srt"), result.map { it.extension })
        assertTrue(result.last().readText().contains("原话1"))
    }

    @Test fun newJobJsonRestoresEveryOptionAndResumesTextOnly() = runBlocking {
        val exporter = textExporter(listOf(source(1, items = listOf(item(1)))))
        val jobs = AndroidExportJobs(context, exporter, scope, {}, {})
        val wanted = request(setOf(ExportTextPart.Summary, ExportTextPart.Todos), ExportTextFormat.Markdown).copy(includeSrt = false, skipMedia = false)
        val id = jobs.start(wanted)
        val json = JSONArray(context.getSharedPreferences("export-jobs", 0).getString("jobs", "[]")).getJSONObject(0)
        assertFalse(json.getBoolean("includeAudio")); assertEquals("Markdown", json.getString("textFormat"))
        assertEquals(2, json.getJSONArray("textParts").length()); assertFalse(json.has("text"))
        val restored = AndroidExportJobs(context, exporter, scope, {}, {})
        assertEquals(id, restored.start(wanted)); assertTrue(restored.run(id) {})
        val file = (restored.jobs.value.single().state as ExportJobState.Done).files.single()
        assertEquals("md", file.extension); assertFalse(file.readText().contains("## 原话"))
    }

    @Test fun oldJobJsonRestoresAudioAndOptionalLinesWithOldFlags() = runBlocking {
        val array = JSONArray()
        for (text in listOf(true, false)) array.put(JSONObject().put("id", "old-$text").put("title", "旧任务")
            .put("started", 1).put("updated", 1).put("segments", JSONArray(listOf(1)))
            .put("text", text).put("srt", false).put("media", false).put("state", "running"))
        context.getSharedPreferences("export-jobs", 0).edit().putString("jobs", array.toString()).commit()
        val requests = mutableListOf<ExportRequest>()
        val exporter = object : AudioExporter {
            override suspend fun estimate(request: ExportRequest) = error("unused")
            override fun cleanup() {}
            override fun export(request: ExportRequest): Flow<ExportProgress> { requests += request; return flowOf(ExportProgress.Done(emptyList())) }
        }
        val restored = AndroidExportJobs(context, exporter, scope, {}, {})
        assertTrue(restored.run("old-true") {}); assertTrue(restored.run("old-false") {})
        assertEquals(listOf(ExportRequest(listOf(1), "旧任务", includeSrt = false, skipMedia = false),
            ExportRequest(listOf(1), "旧任务", textParts = emptySet(), includeSrt = false, skipMedia = false)), requests)
    }
}
