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

/** 议题 #34：真实数据库 + 全文索引触发器下，原话和事件都能按关键词查到。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RecordSearchDb34Test {
    private lateinit var db: CaptureDatabase
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CaptureDatabase::class.java)
            .setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).allowMainThreadQueries().addCallback(SearchIndex.callback).build()
    }
    @After fun cleanup() { db.close() }

    @Test fun findsLinesAndEventsNewestFirst() = runBlocking {
        db.capture().segment(SegmentEntity(1, 1000, 9000, "mic", "/synthetic/1", 10))
        db.transcribe().insertLines(listOf(
            TranscriptLineEntity(segmentId = 1, startAt = 2000, endAt = 3000, text = "明天把合同签了", speakerTag = null, speakerName = "老王"),
            TranscriptLineEntity(segmentId = 1, startAt = 4000, endAt = 5000, text = "合同后来同签都没提", speakerTag = null),
            TranscriptLineEntity(segmentId = 1, startAt = 6000, endAt = 7000, text = "今天开会讨论预算", speakerTag = null)))
        db.memory().insertEvent(MemoryEventEntity(startAt = 8000, endAt = 9000, scene = "工作", title = "合同签约安排",
            summary = "约好明天签", pointsJson = "[]", peopleJson = "[]", place = null, emotion = null, intensity = null,
            important = false, createdAt = 9000, model = "fixture"))
        suspend fun find(q: String) = RecordSearch(db).searchAll(ExpandedSearchRequest(q)).categories
        val hits = find("合同签")
        assertEquals(1, hits.getValue(SearchKind.Event).hits.size)
        val line = hits.getValue(SearchKind.Line).hits.single()
        assertEquals("老王", line.speaker); assertEquals(1L, line.segmentId)
        assertTrue(line.text.contains("合同签"))
        // 单字走兜底也能查到；两个词要同时出现
        assertEquals(1, find("会").getValue(SearchKind.Line).hits.size)
        assertEquals(1, find("今天 预算").getValue(SearchKind.Line).hits.size)
        assertTrue(find("   ").values.all { it.hits.isEmpty() })
    }
}
