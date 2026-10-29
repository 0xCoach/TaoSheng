package app.resound.diary

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.resound.data.*
import app.resound.digest.*
import app.resound.review.*
import app.resound.push.Notifier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class DayDiaryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: CaptureDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var memory: RoomMemoryRepository
    private lateinit var diary: LocalDiaryRepository
    private lateinit var originalZone: TimeZone
    private val date = LocalDate.parse("2026-10-23")
    private fun at(day: LocalDate = date, hour: Int = 0, minute: Int = 0) =
        day.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before fun setup() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val llm = LlmClient()
        val settings = AndroidDigestSettings(context, llm)
        val runner = DigestRunner(context, db.memory(), llm, settings, { "" }, { false })
        memory = RoomMemoryRepository(db.memory(), runner, { error("禁止触发整理") }, scope)
        val reviews = RoomReviewRepository(db.review(), db.memory(), context.getSharedPreferences("review", 0), scope,
            { error("禁止读模型凭证") }, { _, _, _, _ -> error("禁止调用模型") }, { "" }, "回响",
            object : Notifier { override suspend fun send(title: String, body: String, deepLink: String?, topic: app.resound.push.PushTopic?) { error("禁止推送") } }, {})
        diary = LocalDiaryRepository(context, memory, reviews, db.memory())
    }
    @After fun close() = runBlocking {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        db.close()
        TimeZone.setDefault(originalZone)
    }
    private suspend fun event(id: Long = 1, start: Long = at(hour = 14, minute = 5), scene: String = "会议", deleted: Boolean = false) {
        db.memory().insertEvent(MemoryEventEntity(id, start, start + 35 * 60_000, scene,
            if (scene == "媒体") "外放视频" else "和客户聊会议室", "讨论会议室方案", "[\"确认设备\",\"周三报价\"]",
            "[\"张三\",\"李四\"]", "公司", null, null, false, deleted, start, "test"))
    }
    private fun item(id: Long, category: String = "待办", status: String = "Pending", created: Long = at(),
        due: Long? = null, done: Long? = null, sort: Long? = null, event: Long = 1, content: String = "条目$id") =
        MemoryItemEntity(id, event, category, null, content, null, due, null, null, status, created, done, null, sort)

    @Test fun card059FolderTimelineAndExportLinesHaveDates() = runBlocking {
        event()
        db.capture().segment(SegmentEntity(1, at(hour = 14), at(hour = 15), "mic", "/test", 1, "Done"))
        val line = TranscriptLineEntity(1, 1, at(hour = 14, minute = 5), at(hour = 14, minute = 6), "先确认需求", null, "我")
        db.transcribe().insertLines(listOf(line))
        val row = MemoryEventEntity(1, at(hour = 14, minute = 5), at(hour = 14, minute = 40), "会议",
            "和客户聊会议室", "讨论会议室方案", "[]", "[]", null, null, null, false, false, at(), "test")
        val files = app.resound.push.MemoryFolder(date).generate(
            app.resound.push.PushSnapshot(events = listOf(row), lines = listOf(line)), app.resound.push.PushSubscription.FullArchive)
        assertTrue(files.getValue(app.resound.push.MemoryFolder.dailyPath(date)).contains("### E1 · 10月23日 周五 14:05–14:40"))
        assertTrue(files.getValue(app.resound.push.MemoryFolder.transcriptPath(date)).contains("### L1 · 10月23日 14:05 · 我"))
        val year = if (LocalDate.now().year == date.year) "" else "${date.year}年"
        val exported = diary.markdown(date, date, true)
        assertTrue(exported.contains("**${year}10月23日 14:05 我**：先确认需求"))
        assertTrue(exported.contains("### ${year}10月23日 周五 14:05–14:40"))
        File("build/outputs/card059/导出样例.md").apply { parentFile!!.mkdirs(); writeText(exported) }
        File("build/outputs/card059/文件夹时间线样例.md").writeText(files.getValue(app.resound.push.MemoryFolder.dailyPath(date)))
    }

    @Test fun nineToTenBackfillsOnlyDoneAndPreservesOldFields() = runBlocking { migrate(9) }
    @Test fun oneToTenRunsWholeChainAndValidatesRoomSchema() = runBlocking { migrate(1) }
    private suspend fun migrate(version: Int) {
        val name = "diary-migration-$version.db"
        context.deleteDatabase(name)
        val schema = JSONObject(File("schemas/app.resound.data.CaptureDatabase/$version.json").readText()).getJSONObject("database")
        context.openOrCreateDatabase(name, 0, null).use { old ->
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { index ->
                val entity = entities.getJSONObject(index)
                fun sql(s: String) = s.replace("\u0024{TABLE_NAME}", entity.getString("tableName"))
                old.execSQL(sql(entity.getString("createSql")))
                val indices = entity.optJSONArray("indices") ?: JSONArray()
                repeat(indices.length()) { old.execSQL(sql(indices.getJSONObject(it).getString("createSql"))) }
            }
            val setup = schema.getJSONArray("setupQueries")
            repeat(setup.length()) { old.execSQL(setup.getString(it)) }
            old.execSQL("INSERT INTO segments(id,startAt,endAt,sourceId,path,sizeBytes,status) VALUES(1,1000,2000,'mic','/old',10,'Done')")
            if (version == 9) {
                old.execSQL("INSERT INTO memory_events(id,startAt,endAt,scene,title,summary,pointsJson,peopleJson,important,createdAt,model) VALUES(1,1000,2000,'会议','保留事件','摘要','[]','[]',0,1000,'test')")
                old.execSQL("INSERT INTO memory_items(id,eventId,category,content,status,createdAt,dueAt) VALUES(1,1,'待办','已完成','Done',1500,3000),(2,1,'备忘','进行中','Accepted',1600,NULL)")
            }
            old.version = version
        }
        val migrated = Room.databaseBuilder(context, CaptureDatabase::class.java, name).allowMainThreadQueries()
            .addMigrations(*CaptureDatabase.MIGRATIONS).build()
        try {
            assertEquals(CaptureDatabase.VERSION, migrated.openHelper.writableDatabase.version)
            assertEquals("/old", migrated.transcribe().segment(1).path)
            if (version == 9) {
                val rows = migrated.memory().itemsByCategory(listOf("待办", "备忘")).first().associateBy { it.id }
                assertEquals(1500L, rows[1]!!.doneAt)
                assertEquals(3000L, rows[1]!!.dueAt)
                assertEquals("已完成", rows[1]!!.content)
                assertNull(rows[2]!!.doneAt)
                assertTrue(rows.values.all { it.statusChangedAt == null && it.sortOrder == null })
            }
        } finally { migrated.close(); context.deleteDatabase(name) }
    }

    @Test fun statusWritesTimesAndAcceptingOrUndoingDoneMovesToFront() = runBlocking {
        event()
        db.memory().insertItems(listOf(item(1, status = "Accepted", sort = 4), item(2), item(3, status = "Accepted"),
            item(4, status = "Accepted", created = at() + 1)))
        db.memory().setItemStatus(2, "Done", 100)
        var result = memory.items(setOf("待办"), null).first().single { it.id == 2L }
        assertEquals(100L, result.doneAt); assertEquals(100L, result.statusChangedAt)
        db.memory().setItemStatus(2, "Accepted", 200)
        result = memory.inProgress().first().first()
        assertEquals(2L, result.id); assertEquals(3L, result.sortOrder); assertNull(result.doneAt)
        assertEquals(200L, result.statusChangedAt)
        assertEquals(listOf(2L, 1L, 4L, 3L), memory.inProgress().first().map { it.id })
        memory.setItemStatus(1, ItemStatus.Accepted)
        result = withTimeout(5000) { memory.inProgress().first { it.first().id == 1L }.first() }
        assertEquals(2L, result.sortOrder); assertTrue(result.statusChangedAt!! > 200)
        db.memory().setItemStatus(1, "Done", 300)
        db.memory().setItemStatus(1, "Ignored", 400)
        assertNull(memory.items(setOf("待办"), null).first().single { it.id == 1L }.doneAt)
        db.memory().setItemStatus(2, "Ignored", 400)
        db.memory().setItemStatus(3, "Accepted", 500)
        assertEquals(0L, memory.inProgress().first().first().sortOrder)
    }

    @Test fun reorderPersistsRequestedSequenceAndLeavesOtherRowsAlone() = runBlocking {
        event()
        db.memory().insertItems((1L..3L).map { item(it, status = "Accepted", sort = 10 + it) })
        memory.reorder(listOf(2, 1))
        val rows = withTimeout(5000) { memory.inProgress().first { it.first().id == 2L } }
        assertEquals(listOf(2L, 1L, 3L), rows.map { it.id })
        assertEquals(listOf(0L, 1L, 13L), rows.map { it.sortOrder })
    }

    @Test fun appointmentsSortDatesFirstAndUndatedNewestFirst() = runBlocking {
        event()
        db.memory().insertItems(listOf(item(1, "约定", "Accepted", due = at() + 200),
            item(2, "约定", "Accepted", due = at() + 100), item(3, "约定", "Accepted"),
            item(4, "约定", "Accepted", created = at() + 1), item(5, "约定"), item(6, status = "Accepted")))
        assertEquals(listOf(2L, 1L, 4L, 3L), memory.appointments().first().map { it.id })
    }

    @Test fun dayGroupsUseLocalMidnightsAcrossDstAndAllowRepeatedMembership() = runBlocking {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val day = LocalDate.parse("2026-11-01")
        val start = at(day); val end = at(day.plusDays(1))
        assertEquals(25 * 3600_000L, end - start)
        event(start = start)
        event(2, start, deleted = true)
        db.memory().insertItems(listOf(item(1, created = start, due = end - 1, done = start + 1, status = "Done"),
            item(2, "约定", created = start - 1, due = start), item(3, "灵感", created = end - 1),
            item(4, "知识", created = start), item(5, created = end, due = end),
            item(6, status = "Ignored", created = start, due = start), item(7, created = start, event = 2),
            item(8, "备忘", created = start + 1)))
        val groups = memory.dayItems(day).first()
        assertEquals(listOf(1L, 8L), groups.mentioned.map { it.id })
        assertEquals(listOf(2L, 1L), groups.due.map { it.id })
        assertEquals(listOf(1L), groups.done.map { it.id })
        assertEquals(listOf(4L, 3L), groups.notes.map { it.id })
    }

    @Test fun activeDaysCombinesEventStartsAndItemCreationWithinMonth() = runBlocking {
        event(start = at(date.minusDays(1)))
        event(2, at(date.plusDays(1)), deleted = true)
        event(3, at(LocalDate.parse("2026-11-01")))
        db.memory().insertItems(listOf(item(1, created = at(), due = at(date.plusDays(4))),
            item(2, created = at(date.plusDays(2)), event = 2), item(3, created = at(LocalDate.parse("2026-11-01")))))
        assertEquals(setOf(date.minusDays(1), date), memory.activeDays(YearMonth.from(date)).first())
    }

    @Test fun markdownMatchesFullSampleSkipsMediaAndExportsWithoutInvokingModels() = runBlocking {
        event(); event(2, at(hour = 15), scene = "媒体")
        db.memory().insertItems(listOf(item(1, status = "Done", due = at(hour = 16), done = at(hour = 17), content = "发送报价").copy(who = "张三"),
            item(2, "灵感", content = "把报价做成模板"), item(3, "关键信息", content = "预算两万元"),
            item(4, "决定", content = "先做试点"), item(5, "知识", content = "收音需要实测"), item(6, content = "准备演示")))
        db.review().save(ReviewEntity(startDate = "$date", endDate = "$date", brief = "讨论了会议室方案",
            sectionsJson = """[{"title":"一句话","lines":["讨论了会议室方案"],"eventIds":[1]},{"title":"要紧的","lines":["报价已发送"],"eventIds":[1]}]""", generatedAt = at(), model = "test"))
        db.capture().segment(SegmentEntity(1, at(hour = 14), at(hour = 15), "mic", "/test", 1, "Done"))
        db.capture().segment(SegmentEntity(2, at(hour = 14), at(hour = 15), "mic", "/pending", 1, "Pending"))
        db.transcribe().insertLines(listOf(TranscriptLineEntity(1, 1, at(hour = 14, minute = 6), at(hour = 14, minute = 7), "周三发报价", null),
            TranscriptLineEntity(2, 1, at(hour = 14, minute = 5), at(hour = 14, minute = 6), "先确认需求", null, "我"),
            TranscriptLineEntity(3, 2, at(hour = 14), at(hour = 14, minute = 1), "不能导出的未完成转写", null)))
        val year = if (LocalDate.now().year == date.year) "" else "${date.year}年"
        val expected = """
            # 10月23日 星期五

            ## 回顾
            讨论了会议室方案
            **要紧的**
            - 报价已发送

            ## 时间线
            ### ${year}10月23日 周五 14:05–14:40 和客户聊会议室（会议）
            讨论会议室方案
            - 确认设备
            - 周三报价
            人物：张三、李四 · 地点：公司

            ## 待办
            **当天提到**
            - [x] 发送报价（截止 ${year}10月23日 16:00 · 对方：张三）
            - [ ] 准备演示
            **当天到期**
            - [x] 发送报价（截止 ${year}10月23日 16:00 · 对方：张三）
            **当天完成**
            - [x] 发送报价（截止 ${year}10月23日 16:00 · 对方：张三）

            ## 灵感
            - 把报价做成模板

            ## 关键信息
            - 预算两万元

            ## 决定
            - 先做试点

            ## 知识
            - 收音需要实测
        """.trimIndent()
        val markdown = diary.markdown(date.minusDays(1), date.plusDays(1), false)
        assertEquals(expected, markdown)
        assertEquals(expected + "\n\n## 原话\n**${year}10月23日 14:05 我**：先确认需求\n**${year}10月23日 14:06 某人**：周三发报价", diary.markdown(date, date, true))
        assertEquals("", diary.markdown(date.plusDays(1), date.plusDays(2), true))
        // 保留真实输出，交付说明引用这份测试生成的样例。
        File("build/outputs/diary/回响日记_2026-10-23.md").apply { parentFile!!.mkdirs(); writeText(markdown) }
        val uri = diary.exportFile(date, date, false)
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.files", uri.authority)
        assertEquals(expected, File(context.cacheDir, "diary/resound-diary_2026-10-23.md").readText())
        val rangeUri = diary.exportFile(date.minusDays(1), date, false)
        assertTrue(rangeUri.lastPathSegment!!.endsWith("2026-10-22至2026-10-23.md"))
        assertFalse(diary.cloudSync.value)
        diary.setCloudSync(true)
        assertTrue(context.getSharedPreferences("diary", 0).getBoolean("cloudSync", false))
        event(3, at(date.plusDays(2), 14, 5))
        val multi = diary.markdown(date, date.plusDays(2), false)
        assertTrue(multi.startsWith(expected + "\n\n# 10月25日 星期日\n"))
        assertFalse(multi.contains("10月24日"))
        val meta = app.resound.transcribe.VoiceInputMetadata.json(app.resound.transcribe.VoiceInputMeta(
            "合成聊天", "synthetic.chat", "老王", "合成之前上下文", "合成之后回复")).toString()
        db.openHelper.writableDatabase.execSQL("UPDATE transcript_lines SET voiceInputJson=? WHERE id=2", arrayOf(meta))
        val full = diary.markdownWithContext(date, date, true, true)
        assertTrue(full.contains("**${year}10月23日 14:05 我**：先确认需求"))
        assertTrue(full.contains("> 合成之前上下文")); assertTrue(full.contains("> 合成之后回复"))
        val without = diary.markdownWithContext(date, date, true, false)
        assertTrue(without.contains("⌨ 语音输入 · 合成聊天 · 老王"))
        assertFalse(without.contains("合成之前上下文")); assertFalse(without.contains("合成之后回复"))
    }
}
