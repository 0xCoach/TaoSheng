package app.resound.search

import android.app.Application
import androidx.room.Room
import app.resound.data.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ExpandedSearchDb194Test {
    private lateinit var db: CaptureDatabase
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CaptureDatabase::class.java)
            .setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).allowMainThreadQueries().addCallback(SearchIndex.callback).build()
    }
    @After fun cleanup() { db.close() }

    // 从实际 Room 表结构补齐无默认值的必填列；显式覆盖本用例有语义的字段。
    private suspend fun put(table: String, id: Long = 1, vararg fields: Pair<String, Any?>) {
        val values = db.readSql("PRAGMA table_info($table)") { s -> buildMap<String, Any?> {
            while (s.step()) if (s.getLong(3) == 1L && s.isNull(4)) put(s.getText(1), if (s.getText(2) == "TEXT") "" else 0L)
        } }.toMutableMap()
        values["id"] = id
        values.putAll(fields)
        db.writeSql("INSERT INTO $table (${values.keys.joinToString()}) VALUES (${values.keys.joinToString { "?" }})", values.values.toTypedArray())
    }
    private suspend fun search(q: String, kind: SearchKind? = null, size: Int = 100, offset: Int = 0, start: Long? = null, end: Long? = null) =
        RecordSearch(db).searchAll(ExpandedSearchRequest(q, kind, start, end, if (kind == null) emptyMap() else mapOf(kind to offset), size))
    private suspend fun event(id: Long = 1, deleted: Int = 0, points: String = "[]") = put("memory_events", id,
        "startAt" to id, "title" to "合同", "pointsJson" to points, "deleted" to deleted)
    private suspend fun item(id: Long, category: String, status: String = "Pending", vararg fields: Pair<String, Any?>) =
        put("memory_items", id, "category" to category, "status" to status, "content" to "合同", "createdAt" to id, *fields)

    @Test fun allFiveKindsAndEveryOtherSourceReturnLocations() = runBlocking {
        put("segments", 1)
        put("transcript_lines", 1, "segmentId" to 1L, "text" to "合同", "startAt" to 8L)
        event()
        item(1, "待办", "Done", "eventId" to 1L)
        item(2, "知识")
        put("things", 1, "name" to "合同", "aliasesJson" to "[]", "deleted" to 0)
        put("thing_records", 1, "thingId" to 1L, "name" to "合同", "status" to "Accepted")
        put("topics", 1, "name" to "合同", "status" to "Cancelled") // 历史页仍显示取消的专题
        put("reviews", 1, "type" to "daily", "startDate" to "2026-11-16", "endDate" to "2026-11-16", "brief" to "合同", "sectionsJson" to "[]")
        put("ask_conversations", 1, "title" to "合同")
        put("ask_messages", 1, "conversationId" to 1L, "text" to "合同", "role" to "assistant")
        val result = search("合同").categories
        assertEquals(SearchKind.entries.toSet(), result.keys)
        assertTrue(result.values.all { it.hits.isNotEmpty() })
        assertEquals(6, result.getValue(SearchKind.Other).count)
        val hits = result.values.flatMap { it.hits }.associateBy { it.source }
        assertEquals(1L, hits.getValue(SearchSource.Line).segmentId)
        assertEquals(1L, hits.getValue(SearchSource.Todo).eventId)
        assertTrue(hits.getValue(SearchSource.Todo).completed)
        assertEquals("2026-11-16", hits.getValue(SearchSource.Review).reviewDate)
        assertEquals(1L, hits.getValue(SearchSource.AskMessage).conversationId)
        assertEquals(1L, hits.getValue(SearchSource.ThingRecord).thingId)
    }
    @Test fun actualTodoCategoriesAndHiddenItemsAreHandled() = runBlocking {
        event(99, deleted = 1)
        listOf("待办", "约定", "备忘", "灵感", "关键信息", "决定", "知识").forEachIndexed { i, c -> item(i + 1L, c) }
        item(20, "待办", "Done")
        item(21, "待办", "Ignored")
        item(22, "知识", "Ignored")
        item(23, "待办", "Pending", "deleted" to 1)
        item(24, "待办", "Pending", "mergedInto" to 1L)
        item(25, "待办", "Pending", "eventId" to 99L)
        val result = search("合同").categories
        assertEquals(setOf(1L, 2L, 3L, 20L), result.getValue(SearchKind.Todo).hits.map { it.id }.toSet())
        assertEquals(4, result.getValue(SearchKind.Memory).count)
        assertEquals(0, result.getValue(SearchKind.Event).count)
    }
    @Test fun independentCapsCountsAndPagination() = runBlocking {
        put("segments", 1)
        for (i in 1L..205L) put("transcript_lines", i, "segmentId" to 1L, "startAt" to 9L, "text" to "合同")
        for (i in 1L..103L) { event(i); item(i, "待办"); item(i + 200, "知识") }
        val first = search("合同").categories
        for (kind in listOf(SearchKind.Line, SearchKind.Event, SearchKind.Todo, SearchKind.Memory)) {
            assertEquals(100, first.getValue(kind).hits.size)
            assertEquals(101, first.getValue(kind).count)
            assertTrue(first.getValue(kind).countIsLowerBound)
        }
        val second = search("合同", SearchKind.Line, offset = 100).categories.getValue(SearchKind.Line)
        val third = search("合同", SearchKind.Line, offset = second.nextOffset!!).categories.getValue(SearchKind.Line)
        val ids = (first.getValue(SearchKind.Line).hits + second.hits + third.hits).map { it.id }
        assertEquals((205L downTo 1L).toList(), ids)
        assertNull(third.nextOffset)
        assertEquals(101, third.count)
    }
    @Test fun timeRangeAndKindFilter() = runBlocking {
        for (i in 1L..5L) item(i, "待办")
        val result = search("合同", SearchKind.Todo, start = 2, end = 4)
        assertEquals(setOf(SearchKind.Todo), result.categories.keys)
        assertEquals(listOf(3L, 2L), result.categories.getValue(SearchKind.Todo).hits.map { it.id })
    }
    @Test fun eventPointsAndSingleHanSurviveFalsePositiveBatches() = runBlocking {
        for (i in 1L..305L) put("memory_events", i, "startAt" to i, "title" to "合同后来同签", "pointsJson" to "[]")
        put("memory_events", 400, "startAt" to 0L, "title" to "标题", "pointsJson" to "[\"合同签字会\"]")
        assertEquals(listOf(400L), search("合同签", SearchKind.Event).categories.getValue(SearchKind.Event).hits.map { it.id })
        assertEquals(1, search("会", SearchKind.Event).categories.getValue(SearchKind.Event).count)
    }
    @Test fun reviewAndAskSpecialCharactersAndJsonMetadata() = runBlocking {
        val word = "50%_O'Reilly\\会"
        put("ask_conversations", 1, "title" to word)
        put("ask_messages", 1, "conversationId" to 1L, "text" to word, "role" to "user")
        put("ask_messages", 2, "conversationId" to 1L, "text" to "50xxO'Reilly会", "role" to "assistant")
        put("reviews", 1, "type" to "daily", "startDate" to "2026-11-16", "endDate" to "2026-11-16", "brief" to word, "sectionsJson" to """[{"title":"标题","lines":["\u5408\u540c"],"eventIds":[9876]}]""")
        assertEquals(3, search(word, SearchKind.Other).categories.getValue(SearchKind.Other).count)
        assertEquals(1, search("合同", SearchKind.Other).categories.getValue(SearchKind.Other).count)
        assertEquals(0, search("eventIds", SearchKind.Other).categories.getValue(SearchKind.Other).count)
        assertEquals(0, search("9876", SearchKind.Other).categories.getValue(SearchKind.Other).count)
    }
    @Test fun thingsRequireVisibleStateAndConversationRequiresMessages() = runBlocking {
        put("things", 1, "name" to "合同", "aliasesJson" to "[]", "deleted" to 1)
        put("things", 2, "name" to "合同", "aliasesJson" to "[]", "deleted" to 0)
        put("thing_records", 1, "thingId" to 1L, "name" to "合同", "status" to "Accepted")
        put("thing_records", 2, "name" to "合同", "status" to "Ignored")
        put("thing_records", 3, "name" to "合同", "status" to "Pending")
        put("ask_conversations", 1, "title" to "合同")
        val hits = search("合同", SearchKind.Other).categories.getValue(SearchKind.Other).hits
        assertEquals(listOf(SearchSource.ThingRecord), hits.map { it.source })
        assertEquals(3L, hits.single().id)
    }
}
