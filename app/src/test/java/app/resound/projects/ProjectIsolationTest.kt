package app.resound.projects

import android.app.Application
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import app.resound.data.CaptureDatabase
import app.resound.data.MemoryEventEntity
import app.resound.data.MemoryItemEntity
import app.resound.decision.DecisionRow
import app.resound.followup.FollowupRow
import app.resound.platform.NowMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 项目隔离与归属：挂会议带出其待办/决定、跨项目不串、跟进归属正确。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class ProjectIsolationTest {
    private lateinit var db: CaptureDatabase
    private lateinit var repo: ProjectRepository
    private val now = 2_000_000_000_000L

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CaptureDatabase::class.java)
            .setDriver(AndroidSQLiteDriver()).allowMainThreadQueries().build()
        repo = ProjectRepository(db, CoroutineScope(Dispatchers.Unconfined), NowMillis { now }, Dispatchers.Unconfined)
    }
    @After fun close() = db.close()

    private suspend fun project(name: String) =
        db.projects().insert(ProjectRow(name = name, createdAt = now, updatedAt = now))
    private suspend fun event(title: String, startAt: Long) = db.memory().insertEvent(
        MemoryEventEntity(startAt = startAt, endAt = startAt + 3_600_000, scene = "会议", title = title,
            summary = "", pointsJson = "[]", peopleJson = "[]", place = null, emotion = null, intensity = null,
            important = false, createdAt = startAt, model = "test"))
    private suspend fun item(eventId: Long?, status: String = "Accepted", content: String = "任务", at: Long = now): Long {
        val id = db.memory().addManualItem(MemoryItemEntity(eventId = eventId, category = "待办", kind = null, content = content,
            dueText = null, dueAt = null, who = null, lineId = null, status = "Accepted", createdAt = at))
        if (status != "Accepted") db.memory().setItemStatus(id, status, at)
        return id
    }

    @Test fun linkingEventPullsInItsItemsAndAdoptsDecisions() = runBlocking {
        val pid = project("声声不息")
        val eid = event("产品周例会", now - 86_400_000)
        val iid = item(eid, content = "跟进录音降噪")
        // 关联前已导入的决定：projectId 为 null；挂上会议后回填进来
        val did = db.decisions().insert(DecisionRow(title = "用 SenseVoice", detail = "",
            sourceEventId = eid, decidedAt = now - 80_000_000, createdAt = now - 80_000_000, updatedAt = now - 80_000_000))
        repo.link(pid, ProjectRefKind.Event, eid)

        assertEquals(pid, db.decisions().decision(did)!!.projectId)
        val acts = repo.actions(pid)
        assertTrue(acts.open.any { it.key == "item:$iid" })
        val tl = repo.activity(pid)
        assertTrue(tl.any { it.key == "event:$eid" })
        assertTrue(tl.any { it.key == "item:$iid" })
        assertTrue(tl.any { it.key == "decision:$did" })
    }

    @Test fun differentProjectsDoNotMix() = runBlocking {
        val pa = project("项目A"); val pb = project("项目B")
        val ea = event("A的会", now - 100_000); val eb = event("B的会", now - 200_000)
        val ia = item(ea, content = "A的任务")
        repo.link(pa, ProjectRefKind.Event, ea)
        repo.link(pb, ProjectRefKind.Event, eb)
        // B 项目看不到 A 的会议和任务
        val tlB = repo.activity(pb)
        assertTrue(tlB.none { it.key == "event:$ea" || it.key == "item:$ia" })
        val actsB = repo.actions(pb)
        assertTrue(actsB.open.none { it.key == "item:$ia" })
    }

    @Test fun followupOnProjectItemIsAttributedOnlyToThatProject() = runBlocking {
        val pa = project("项目A"); val pb = project("项目B")
        val ea = event("A的会", now - 100_000)
        val ia = item(ea, content = "A的任务")
        repo.link(pa, ProjectRefKind.Event, ea)
        db.followups().insert(FollowupRow(kind = "Commitment", refKind = "item", refId = ia,
            title = "A的任务承诺", status = "Open", createdAt = now, updatedAt = now))
        assertTrue(repo.actions(pa).open.any { it.key == "item:$ia" && it.followupKind == "Commitment" })
        assertTrue(repo.actions(pb).open.isEmpty() && repo.actions(pb).done.isEmpty() && repo.actions(pb).pending.isEmpty())
    }

    @Test fun adoptBackfillNeverOverwritesExistingOwner() = runBlocking {
        val pa = project("项目A"); val pb = project("项目B")
        val ea = event("会", now - 100_000)
        // 决定已经属于 A：后来把同一事件挂到 B，不能改它的归属
        val did = db.decisions().insert(DecisionRow(title = "已定给A", detail = "", projectId = pa,
            sourceEventId = ea, decidedAt = now, createdAt = now, updatedAt = now))
        repo.link(pb, ProjectRefKind.Event, ea)
        assertEquals(pa, db.decisions().decision(did)!!.projectId)
    }

    @Test fun importDoesNotAutoSupersedeSameTopicDecision() = runBlocking {
        // AI 导入一条同主题新决定：旧决定仍是 Active——冲突留给用户，系统不自动废止
        val repo2 = app.resound.decision.DecisionRepository(db.decisions(), db.memory(), db.revisions(),
            Dispatchers.Unconfined, NowMillis { now }, db.projects())
        val (oldId, _) = repo2.create("发布安卓版", "本月上线", confidence = app.resound.decision.DecisionConfidence.Sure)
        val iid = item(null, content = "发布安卓版 本月上线")
        repo2.importFromItem(db.memory().itemIncludingDeleted(iid)!!)
        val old = db.decisions().decision(oldId)!!
        assertEquals("Active", old.status)
        assertNull(old.supersededBy)
        // 冲突可被查询到（旧+新两条 Active 并存，供界面"待确认"提示），但系统没有自动改状态
        assertEquals(2, db.decisions().activeSameTopic(old.topicKey, 0).size)
    }
}
