package app.resound.followup

import android.app.Application
import androidx.room.Room
import app.resound.data.CaptureDatabase
import app.resound.data.MemoryEventEntity
import app.resound.data.MemoryItemEntity
import app.resound.data.testSql
import app.resound.inbox.IdeaRow
import app.resound.inbox.IdeaStatus
import app.resound.platform.NowMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class FollowupWritebackTest {
    private lateinit var db: CaptureDatabase
    private lateinit var engine: FollowupEngine
    private val now = 2_000_000_000_000L
    private val notified = mutableListOf<String>()

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
            .setDriver(androidx.sqlite.driver.AndroidSQLiteDriver())
            .allowMainThreadQueries().build()
        engine = FollowupEngine(db.followups(), db.ideas(), db.memory(),
            Dispatchers.Unconfined, CoroutineScope(Dispatchers.Unconfined),
            NowMillis { now }, notify = { title, _, _ -> notified += title })
    }
    @After fun close() = db.close()

    private suspend fun todo(id: Long, eventStart: Long = now) {
        db.memory().insertEvent(MemoryEventEntity(id = id, startAt = eventStart, endAt = eventStart + 1,
            scene = "其他", title = "事件", summary = "", pointsJson = "[]", peopleJson = "[]",
            place = null, emotion = null, intensity = null, important = false, createdAt = eventStart, model = "test"))
        db.memory().insertItems(listOf(MemoryItemEntity(id = id, eventId = id, category = "待办", kind = null,
            content = "交报告", dueText = null, dueAt = null, who = null, lineId = null, status = "Accepted", createdAt = eventStart)))
    }
    private suspend fun idea(status: String = "Research"): Long =
        db.ideas().insert(IdeaRow(title = "做个睡眠产品", content = "详情", status = status, createdAt = now, updatedAt = now))
    private fun followup(refKind: String, refId: Long) = FollowupRow(kind = FollowupKind.Todo.name,
        refKind = refKind, refId = refId, title = "提醒", createdAt = now, nextCheckAt = now, updatedAt = now)
    private fun itemRow(id: Long): Pair<String?, Long?> =
        db.testSql.query("SELECT status, doneAt FROM memory_items WHERE id=$id").use {
            assertTrue(it.moveToFirst()); it.getString(0) to (if (it.isNull(1)) null else it.getLong(1)) }

    @Test fun doneWritesBackTodo() = runBlocking {
        todo(7)
        val fid = db.followups().insert(followup("item", 7))
        engine.markDone(fid)
        assertEquals("Done" to now, itemRow(7))
        assertEquals(FollowupStatus.Done.name, db.followups().followup(fid)!!.status)
    }

    @Test fun doneWritesBackIdea() = runBlocking {
        val ideaId = idea()
        val fid = db.followups().insert(followup("idea", ideaId))
        engine.markDone(fid)
        assertEquals(IdeaStatus.Done.name, db.ideas().idea(ideaId)!!.status)
    }

    @Test fun dismissKeepsSourceUntouched() = runBlocking {
        todo(9)
        val fid = db.followups().insert(followup("item", 9))
        engine.dismiss(fid)
        assertEquals(FollowupStatus.Dismissed.name, db.followups().followup(fid)!!.status)
        assertEquals("Accepted" to null, itemRow(9))
    }

    @Test fun scanFindsStaleTodoOnce() = runBlocking {
        todo(3, eventStart = now - FollowupEngine.TODO_STALE_MS - 1)
        engine.scan()
        val open = db.followups().observeOpen().first()
        assertEquals(1, open.size)
        assertEquals("item", open.single().refKind)
        engine.scan()
        assertEquals(1, db.followups().observeOpen().first().size)
    }

    @Test fun stalledListPicksRepeatedlyRemindedOpenItems() = runBlocking {
        db.followups().insert(followup("item", 1).copy(remindCount = 4))
        db.followups().insert(followup("item", 2).copy(remindCount = 1))
        db.followups().insert(followup("item", 3).copy(remindCount = 5, status = FollowupStatus.Done.name))
        val stalled = db.followups().observeStalled(3).first()
        assertEquals(1, stalled.size)
        assertEquals(4, stalled.single().remindCount)
    }
}
