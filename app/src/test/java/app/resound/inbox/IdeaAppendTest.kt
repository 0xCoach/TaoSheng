package app.resound.inbox

import android.app.Application
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import app.resound.data.CaptureDatabase
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

/** 灵感追加与转化：每条补充独立留痕、转待办/项目幂等、不相关标记持久生效。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class IdeaAppendTest {
    private lateinit var db: CaptureDatabase
    private lateinit var repo: IdeaRepository
    private val now = 2_000_000_000_000L

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CaptureDatabase::class.java)
            .setDriver(AndroidSQLiteDriver()).allowMainThreadQueries().build()
        repo = IdeaRepository(db, CoroutineScope(Dispatchers.Unconfined), NowMillis { now }, Dispatchers.Unconfined)
    }
    @After fun close() = db.close()

    @Test fun appendKeepsIndependentEntriesAndSeedsTimeline() = runBlocking {
        val id = repo.create("睡眠产品", "做个帮人入睡的产品")
        repo.append(id, "先做个白噪音 MVP")
        repo.append(id, "竞品是小睡眠", origin = "User")
        val entries = repo.entries(id).first()
        // 种子 + 两条补充，各自独立成行、按时间排列
        assertEquals(3, entries.size)
        assertEquals("Seed", entries[0].kind)
        assertEquals("Note", entries[1].kind)
        assertEquals("竞品是小睡眠", entries[2].text)
    }

    @Test fun convertToTodoIsIdempotent() = runBlocking {
        val id = repo.create("做个工具", "灵感内容")
        val first = repo.convertToTodo(id)
        val second = repo.convertToTodo(id)
        assertEquals(first, second)
        // 待办只有一条，轨迹记下转化事件
        assertEquals("Todo", db.ideas().idea(id)!!.convertedKind)
        assertTrue(repo.entries(id).first().any { it.kind == "Todo" })
    }

    @Test fun convertToProjectCreatesAndLinks() = runBlocking {
        val id = repo.create("睡眠硬件", "做助眠硬件")
        val pid = repo.convertToProject(id)
        assertTrue(pid > 0)
        val idea = db.ideas().idea(id)!!
        assertEquals(pid, idea.projectId)
        assertEquals("Project", idea.convertedKind)
        // project_links 里挂上了这条灵感
        assertTrue(db.projects().linksForRef(app.resound.projects.ProjectRefKind.Idea.name, id).any { it.projectId == pid })
        // 再转一次不重复建项目
        assertEquals(pid, repo.convertToProject(id))
    }

    @Test fun dismissDuplicateHidesCandidate() = runBlocking {
        val a = repo.create("冥想 App", "做一个冥想练习应用")
        val b = repo.create("冥想 App", "做一个冥想练习应用")
        assertTrue(repo.duplicatesOf(a).any { it.id == b })
        repo.dismissDuplicate(a, b)
        assertTrue(repo.duplicatesOf(a).none { it.id == b })
    }
}
