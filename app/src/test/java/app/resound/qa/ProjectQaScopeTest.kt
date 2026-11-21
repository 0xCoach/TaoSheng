package app.resound.qa

import android.app.Application
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import app.resound.data.CaptureDatabase
import app.resound.decision.DecisionRow
import app.resound.platform.NowMillis
import app.resound.projects.ProjectMarkRow
import app.resound.projects.ProjectRow
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

/** 项目范围问答：证据严格限定本项目、可追溯到来源、无证据不编造。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class ProjectQaScopeTest {
    private lateinit var db: CaptureDatabase
    private lateinit var qa: GroundedQA
    private val now = 2_000_000_000_000L

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), CaptureDatabase::class.java)
            .setDriver(AndroidSQLiteDriver()).allowMainThreadQueries().build()
        qa = GroundedQA(app.resound.search.RecordSearch(db), db.decisions(), db.ideas(),
            db.projects(), db.memory(), db.followups(), Dispatchers.Unconfined, NowMillis { now },
            { app.resound.digest.LlmPreset("", "", "", "") to "" }, { false }, { false },
            { _, _, _, _ -> error("不应被调用") })
    }
    @After fun close() = db.close()

    private suspend fun project(name: String) =
        db.projects().insert(ProjectRow(name = name, createdAt = now, updatedAt = now))

    @Test fun scopedAskReturnsOnlyThisProjectsSources() = runBlocking {
        val pa = project("项目A"); val pb = project("项目B")
        db.decisions().insert(DecisionRow(title = "用方案A做降噪", detail = "", projectId = pa,
            decidedAt = now, createdAt = now, updatedAt = now))
        db.decisions().insert(DecisionRow(title = "B项目的别的决定", detail = "", projectId = pb,
            decidedAt = now, createdAt = now, updatedAt = now))
        db.projects().insertMark(ProjectMarkRow(projectId = pa, kind = "Status", title = "A在收尾", at = now))
        // 未配模型：返回 needsService + 命中的来源清单
        val ans = qa.ask("方案A", pa)
        assertTrue(ans.needsService)
        assertTrue(ans.sources.isNotEmpty())
        // 来源全部可追溯到本项目事实：decision/mark 属于 A；B 的决定不在场
        assertTrue(ans.sources.none { it.title.contains("B项目") })
        assertTrue(ans.sources.any { it.kind == "decision" })
        assertTrue(ans.sources.any { it.kind == "mark" })
    }

    @Test fun emptyProjectSaysNoEvidence() = runBlocking {
        val p = project("空项目")
        val ans = qa.ask("推进到什么程度了", p)
        // RecordsOnly 默认模式 + 无证据 → 直接明说没找到，不调用模型
        assertTrue(ans.noEvidence)
        assertTrue(ans.sources.isEmpty())
    }
}
