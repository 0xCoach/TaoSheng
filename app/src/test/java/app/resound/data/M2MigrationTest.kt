package app.resound.data

import android.app.Application
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import app.resound.agent.AgentTaskRow
import app.resound.inbox.IdeaRow
import app.resound.projects.ProjectLinkRow
import app.resound.projects.ProjectRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

/** M2 新增四组表：迁移幂等、Room 校验结构、灵感去重墓碑、Agent 任务唯一号与 CAS、项目隔离。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class M2MigrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val name = "m2-test.db"
    private var db: CaptureDatabase? = null

    private fun open(): CaptureDatabase = CaptureDatabase.open(context, name).also { db = it }

    @After fun tearDown() {
        db?.close(); db = null; context.deleteDatabase(name)
    }

    @Test fun migration34ToLatestIsIdempotentAndCreatesTables() = runBlocking<Unit>(Dispatchers.IO) {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        // 直接对原始连接跑 34→最新 迁移两遍：幂等（IF NOT EXISTS），不经过 Room 校验
        AndroidSQLiteDriver().open(file.path).use { conn ->
            conn.userVersion = 34
            val migration = CaptureDatabase.MIGRATIONS.single { it.startVersion == 34 }
            repeat(2) { migration.migrate(conn) }
            conn.prepare("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name IN " +
                "('ideas','projects','project_links','project_marks','analysis_templates','analysis_runs','agent_endpoints','agent_tasks')").use {
                assertTrue(it.step()); assertEquals(8L, it.getLong(0))
            }
        }
        file.delete()
    }

    @Test fun freshDbAcceptsAllNewModuleRows() = runBlocking<Unit>(Dispatchers.IO) {
        val d = open()
        try {
            val version = d.readSql("PRAGMA user_version") { assertTrue(it.step()); it.getLong(0).toInt() }
            assertEquals(CaptureDatabase.VERSION, version)
            // 新表可写：四模块各插一行
            val ideaId = d.ideas().insert(IdeaRow(title = "t", content = "c", createdAt = 1, updatedAt = 1))
            assertTrue(ideaId > 0)
            val projectId = d.projects().insert(ProjectRow(name = "p", createdAt = 1, updatedAt = 1))
            d.projects().insertLink(ProjectLinkRow(projectId = projectId, refKind = "Idea", refId = ideaId, createdAt = 1))
            d.agent().insertTask(AgentTaskRow(taskId = "task-m2-1", type = "DeepResearch", title = "t", payloadJson = "{}", createdAt = 1, updatedAt = 1))
            d.analysis().insertRun(app.resound.analysis.AnalysisRunRow(targetKind = "Idea", targetId = ideaId, type = "Summary", createdAt = 1))
        } finally { }
    }

    @Test fun ideaTombstoneBlocksResyncOfMergedSource() = runBlocking<Unit>(Dispatchers.IO) {
        val d = open()
        try {
            val a = d.ideas().insert(IdeaRow(title = "a", content = "同一句话", sourceItemId = 7, createdAt = 1, updatedAt = 1))
            val b = d.ideas().insert(IdeaRow(title = "b", content = "同一句话", fingerprint = "fp", createdAt = 1, updatedAt = 1))
            d.ideas().mergeInto(a, b, 2)
            // sourceItemId=7 的墓碑仍在：anyBySourceItem 命中，整理同步不会复活
            assertNotNull(d.ideas().anyBySourceItem(7))
            assertNull(d.ideas().bySourceItem(7))
        } finally { }
    }

    @Test fun agentTaskIdUniqueAndClaimIsAtomic() = runBlocking<Unit>(Dispatchers.IO) {
        val d = open()
        try {
            val id = d.agent().insertTask(AgentTaskRow(taskId = "task-uniq", type = "DeepResearch", title = "t",
                payloadJson = "{}", status = "Confirmed", createdAt = 1, updatedAt = 1))
            val dup = runCatching {
                d.agent().insertTask(AgentTaskRow(taskId = "task-uniq", type = "Custom", title = "x", payloadJson = "{}", createdAt = 2, updatedAt = 2))
            }
            assertTrue("taskId 唯一索引应拒绝重复", dup.isFailure)
            // CAS 认领：第一次成功，第二次返回 0（不会重发）
            assertEquals(1, d.agent().claimForSend(id, 10))
            assertEquals(0, d.agent().claimForSend(id, 20))
        } finally { }
    }

    @Test fun projectLinksAndMarksAreIsolatedPerProject() = runBlocking<Unit>(Dispatchers.IO) {
        val d = open()
        try {
            val p1 = d.projects().insert(ProjectRow(name = "p1", createdAt = 1, updatedAt = 1))
            val p2 = d.projects().insert(ProjectRow(name = "p2", createdAt = 1, updatedAt = 1))
            d.projects().insertLink(ProjectLinkRow(projectId = p1, refKind = "Idea", refId = 99, createdAt = 1))
            assertEquals(1, d.projects().links(p1).size)
            assertEquals(0, d.projects().links(p2).size)
            // 同一灵感挂两个项目互不冲突
            d.projects().insertLink(ProjectLinkRow(projectId = p2, refKind = "Idea", refId = 99, createdAt = 2))
            assertEquals(1, d.projects().links(p2).size)
            assertEquals(1, d.projects().links(p1).size)
        } finally { }
    }
}
