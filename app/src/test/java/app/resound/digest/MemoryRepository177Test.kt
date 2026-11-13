package app.resound.digest

import android.app.Application
import androidx.room.Room
import app.resound.data.*
import app.resound.platform.ZoneProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class MemoryRepository177Test {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var db: CaptureDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var gate: StartGate
    private class StartGate : CoroutineDispatcher() {
        private val queued = mutableListOf<Pair<CoroutineContext, Runnable>>()
        private var started = false
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (started) Dispatchers.IO.dispatch(context, block) else queued.add(context to block)
        }
        fun start() { started = true; queued.toList().also { queued.clear() }.forEach { Dispatchers.IO.dispatch(it.first, it.second) } }
    }
    @Before fun setup() {
        context.getSharedPreferences("digest-state", 0).edit().clear().putLong("lastRunAt", 123).putString("lastError", "旧错误").commit()
        context.getSharedPreferences("digest", 0).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).setDriver(androidx.sqlite.driver.AndroidSQLiteDriver()).build()
        gate = StartGate(); scope = CoroutineScope(SupervisorJob() + gate)
    }
    @After fun close() = runBlocking { gate.start(); scope.coroutineContext[Job]!!.cancelAndJoin(); db.close() }
    private fun fixture(name: String, text: String) {
        val file = File("src/test/resources/repo177/RoomMemoryRepository/$name.txt")
        assertEquals(file.readText(), text)
    }
    @Test fun queriesAndCommandsMatchOldRepository() = runBlocking {
        val client = LlmClient()
        val runner = DigestRunner(context, db.memory(), client, AndroidDigestSettings(context, client), { "" }, { false })
        var runs = 0; var changed = 0
        val repo = RoomMemoryRepository(db.memory(), runner, { runs++ }, scope, AndroidAppointmentSettings(context), { changed++ }, ZoneProvider { "America/New_York" }, app.resound.platform.SystemNow, Dispatchers.IO)
        val out = StringBuilder(); fun save(key: String, value: Any?) { out.appendLine("$key=$value") }
        val day = LocalDate(2026, 11, 1)
        val at = java.time.LocalDate.of(2026, 11, 1).atStartOfDay(java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli()
        val dao = db.memory()
        save("initial", repo.state.value)
        dao.insertEvent(MemoryEventEntity(1, at, at + 2000, "会议", "旧标题", "旧摘要", "[\"一/二\",3,null,{\"a\":1}]", "['宽松旧数据']", null, "平静", 1, true, createdAt = at, model = "test", starred = true, starredAt = at))
        dao.insertEvent(MemoryEventEntity(2, at + 90_000_000, at + 90_001_000, "独白", "次日", "", "坏JSON", "[]", null, null, null, false, createdAt = at, model = "test"))
        db.capture().segment(SegmentEntity(1, at, at + 3000, "mic", "synthetic-memory", 1, "Done"))
        db.transcribe().insertLines(listOf(TranscriptLineEntity(1, 1, at, at + 1000, "合成原话", "0", eventId = 1), TranscriptLineEntity(2, 1, at + 1000, at + 2000, "做完了", "0", eventId = 1)))
        dao.insertItems(listOf(
            MemoryItemEntity(1, 1, "待办", null, "待确认", null, at + 1000, "甲", 1, "Pending", at, doneHintLineId = 2, doneHintAt = at + 1000, origin = "MANUAL", sourceText = "来源", strongReminder = true),
            MemoryItemEntity(2, 1, "约定", null, "约见", null, at + 2000, null, 1, "Accepted", at),
            MemoryItemEntity(3, 1, "备忘", null, "已做", null, null, null, 1, "Done", at, doneAt = at + 3000),
            MemoryItemEntity(4, 1, "灵感", null, "根灵感", null, null, null, 1, "UnknownLegacyStatus", at),
            MemoryItemEntity(5, 1, "灵感", null, "子灵感", null, null, null, 2, "Pending", at + 1, mergedInto = 4)))
        dao.addUsage(java.time.LocalDate.now().toString(), 20, 3)
        gate.start()
        save("state.loaded", withTimeout(5000) { repo.state.first { it.tokensToday == 23L && it.pendingLines == 2 } })
        save("events", repo.events(day).first()); save("important", repo.importantEvents().first()); save("starred", repo.starredEvents().first())
        save("byId", repo.eventById(1)); save("missing", repo.eventById(999)); save("badJson", repo.eventById(2))
        save("eventLines", repo.eventLines(1).first()); save("sourceLines", repo.sourceLines(1)); save("missingLines", repo.sourceLines(999))
        save("pending", repo.pendingItems().first()); save("items", repo.items(setOf("待办", "灵感"), setOf(ItemStatus.Pending)).first())
        save("item", repo.item(1).first()); save("inProgress", repo.inProgress().first()); save("appointments", repo.appointments().first()); save("ongoing", repo.ongoing().first())
        save("done", repo.doneItems(day).first()); save("day", repo.dayItems(day).first()); save("days", repo.activeDays(YearMonth(2026, 11)).first().sorted())
        save("merged", repo.mergedItems(4).first())
        suspend fun command(block: () -> Unit) {
            val before = scope.coroutineContext[Job]!!.children.toSet(); block()
            withTimeout(5000) { scope.coroutineContext[Job]!!.children.filter { it !in before }.toList().forEach { it.join() } }
        }
        command { repo.unmerge(5) }; save("unmerge", repo.mergedItems(4).first())
        command { repo.editEvent(1, "修改", "新摘要", listOf("引号\"与/斜杠", "反\\线")) }
        val edited = repo.eventById(1)!!; save("edited", edited.copy(editedAt = null)); save("edit.clock", edited.editedAt!! >= at)
        command { repo.restoreEvent(1) }; save("restored", repo.eventById(1)); save("changed", changed)
        command { repo.setStarred(1, false) }; save("unstar", repo.starredEvents().first())
        command { repo.setImportant(1, true) }; save("star", repo.starredEvents().first().map { it.id }); save("star.accepts", repo.item(1).first()!!.status)
        command { repo.updateItem(1, " 修改条目 ", at + 12345, false, " 甲 ") }
        save("updated", dao.itemIncludingDeleted(1)!!.let { listOf(it.content, it.dueAt, it.dueText, it.who, it.editedAt != null) })
        command { repo.setStrongReminder(1, false) }; save("strong", repo.item(1).first()!!.strongReminder)
        command { repo.setItemsStatus(listOf(1, 2), ItemStatus.Done) }; save("batch", listOf(repo.item(1).first()!!.status, repo.item(2).first()!!.status))
        command { repo.setItemStatus(1, ItemStatus.Accepted) }; save("status", repo.item(1).first()!!.let { it.status to it.doneAt })
        command { repo.dismissDoneHint(1) }; save("dismissed", dao.itemIncludingDeleted(1)!!.dismissedDoneLinesJson)
        command { repo.reorder(listOf(2, 1)) }; save("reorder", dao.itemIncludingDeleted(1)!!.sortOrder)
        save("reminders.initial", repo.appointmentReminders.value); repo.setAppointmentReminders(AppointmentReminders(false, 3000, 0)); save("reminders", repo.appointmentReminders.value)
        repo.runNow(); save("run", runs)
        command { repo.deleteItem(1) }; save("delete.item", repo.item(1).first())
        command { repo.deleteEvent(1) }; save("delete.event", repo.eventById(1))
        fixture("all", out.toString())
    }
}
