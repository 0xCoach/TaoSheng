package app.resound.topic

import android.app.Application
import androidx.room.Room
import app.resound.data.*
import app.resound.skills.SkillCardRow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneId

/** 卡 177：夹具先由 app 中的旧仓储录出，迁移后仅重放。全部数据库为合成内存库。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class TopicRepository177Test {
    private lateinit var db: CaptureDatabase
    private lateinit var scope: CoroutineScope
    private val at = 1_794_532_800_000L
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CaptureDatabase::class.java)
            .setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
    @After fun close() = runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin(); db.close() }
    private fun event(id: Long, start: Long = at - 60_000) = MemoryEventEntity(id, start, start + 30_000,
        "会议", "合成专题$id", "同输入", "[\"要点\"]", "[]", null, null, null, false, false, at, "test")
    private suspend fun line(id: Long, start: Long, event: Long? = null) {
        db.capture().segment(SegmentEntity(id, start, start + 1000, "mic", "/synthetic-$id", 1, "Failed", attempts = 1, nextRetryAt = at + 9999))
        db.transcribe().insertLines(listOf(TranscriptLineEntity(id, id, start, start + 1000, "合成原话$id", "0", "我", eventId = event, digestedAt = event?.let { at })))
    }
    private fun fixture(name: String, text: String) {
        val file = File("src/test/resources/repo177/$name/outputs.txt")
        assertEquals(file.readText(), text)
    }

    @Test fun repositoryMethodsMissingFromTopic135MatchRecordedBytes() = runBlocking {
        var clock = at
        val repo = RoomTopicRepository(db, scope, { clock }, { "Asia/Shanghai" })
        val out = StringBuilder()
        fun record(label: String, value: Any?) { out.appendLine("$label=$value") }
        record("current.empty", repo.current.value)
        val id = repo.start(TopicKind.Normal, "  原名  ", 15).topicId!!
        record("current.started", repo.current.first { it?.id == id })
        record("progress", repo.observeProgress(id).first())
        val day = LocalDate.parse(java.time.Instant.ofEpochMilli(at).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString())
        val dayRows = repo.observeDay(day).first(); assertEquals(id, dayRows.single().id); record("day", dayRows)
        repo.update(id, TopicKind.Meeting, " 新名 ")
        record("updated", repo.observe(id).first())
        clock += 1000; repo.end()
        repo.delete(id)
        record("deleted", repo.observe(id).first())
        record("deleted.history", repo.observeHistory().first())
        db.memory().insertEvent(event(30, at - 120_000))
        record("preview.range", repo.previewFromRange(at - 110_000, at - 100_000))
        val created = repo.createFromEvents(listOf(30), TopicKind.Normal, " 回收 ").getOrThrow()
        record("created.events", repo.observe(created).first())
        record("created.current", repo.current.value)
        fixture("RoomTopicRepository", out.toString())
    }

    @Test fun storeEveryMethodAndFlowMatchRecordedBytes() = runBlocking {
        val store = RoomTopicStore(db)
        val out = StringBuilder()
        fun record(label: String, value: Any?) { out.appendLine("$label=$value") }
        record("current.empty", store.observeCurrent().first())
        record("all.empty", store.observeAll().first())
        val id = store.insert(app.resound.topic.core.TopicRow(kind = "Meeting", name = "合成", status = "Running", createdAt = at,
            plannedStartAt = at - 60_000, plannedEndAt = at + 60_000, repeatRule = 5, revision = 3, quietOverride = true,
            retainedItemIds = "[5]", starred = true, quietNotifiedAfter = at - 1, endingNotifiedAt = at - 2))
        record("insert", id)
        record("span", store.span(app.resound.topic.core.TopicSpanRow(topicId = id, startAt = at - 60_000)))
        val mark = store.mark(app.resound.topic.core.TopicMarkRow(topicId = id, at = at)); record("mark", mark)
        record("current", store.observeCurrent().first())
        record("all", store.all())
        store.update(store.get(id)!!.row.copy(name = "改名", error = "合成错误"))
        record("get.updated", store.get(id))
        store.unmark(id, mark); store.closeSpan(id, at + 1)
        record("closed.unmarked", store.get(id))
        db.memory().insertEvent(event(30)); db.memory().insertEvent(event(31, at - 500_000))
        db.memory().insertItems(listOf(MemoryItemEntity(5, 30, "待办", null, "合成条目", null, null, "我", null, "Accepted", at)))
        line(1, at - 50_000, 30); line(2, at - 500_000, 31)
        record("digestedUntil", store.digestedUntil(at - 60_000, at))
        record("segments", store.segmentIds(id, at))
        record("ordinary", store.ordinaryEvents(at - 60_000, at))
        record("items", store.eventItems(listOf(30)))
        record("event", store.event(30))
        store.attachItems(listOf(5), 31); record("attached", store.eventItems(listOf(31)))
        store.releaseEvents(listOf(30)); record("released.events", db.memory().eventLines(30).first())
        store.releaseRange(at - 500_000, at - 400_000); record("released.range", db.memory().eventLines(31).first())
        record("lastRecording", store.lastRecording(at - 60_000, at))
        store.prioritize(listOf(1)); record("priority", db.transcribe().segment(1).nextRetryAt)
        store.repairMergedItems(); record("repair", store.eventItems(listOf(31)))
        db.skills().save(SkillCardRow(id = 7, skillId = "meeting", skillVersion = 1, skillName = "纪要", icon = "", date = 0,
            entity = "合成", at = at, endAt = null, title = "标题", summary = "摘要", fieldsJson = "[]", valuesJson = "{}",
            evidenceJson = "[]", generatedAt = at, manual = false))
        store.deleteCard(7); record("card.deleted", db.skills().cards(0).first())
        store.attachItems(listOf(5), null); store.deleteEvents(listOf(30, 31)); record("events.deleted", store.event(30))
        store.transaction { store.update(store.get(id)!!.row.copy(name = "事务提交")) }
        record("transaction.commit", store.get(id))
        try { store.transaction { store.update(store.get(id)!!.row.copy(name = "不应保留")); error("rollback") } } catch (_: IllegalStateException) { }
        record("transaction.rollback", store.get(id))
        store.deleteSpans(id); record("spans.deleted", store.get(id))
        store.deleteTopic(id); record("topic.deleted", store.get(id)); record("all.deleted", store.observeAll().first())
        fixture("RoomTopicStore", out.toString())
    }
}
