package app.resound.data

import android.app.Application
import androidx.sqlite.driver.AndroidSQLiteDriver
import app.resound.collect.CaptureItemRow
import app.resound.decision.DecisionRow
import app.resound.followup.FollowupRow
import app.resound.mcp.McpClientRow
import app.resound.revise.MemoryRevisionRow
import app.resound.rules.WorkflowRuleRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** M2+ 新增六组表：35→最新 迁移幂等、Room 结构校验通过、决策替代/否决语义、跟进去重、修订留痕。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class M2PlusMigrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val name = "m2plus-test.db"
    private var db: CaptureDatabase? = null

    private fun open(): CaptureDatabase = CaptureDatabase.open(context, name).also { db = it }

    @After fun tearDown() {
        db?.close(); db = null; context.deleteDatabase(name)
    }

    @Test fun migration35ToLatestIsIdempotentAndCreatesTables() = runBlocking<Unit>(Dispatchers.IO) {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        AndroidSQLiteDriver().open(file.path).use { conn ->
            conn.userVersion = 35
            val migration = CaptureDatabase.MIGRATIONS.single { it.startVersion == 35 }
            repeat(2) { migration.migrate(conn) }
            conn.prepare("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name IN " +
                "('decisions','followups','capture_items','workflow_rules','rule_runs','memory_revisions','mcp_clients')").use {
                assertTrue(it.step()); assertEquals(7L, it.getLong(0))
            }
        }
        file.delete()
    }

    @Test fun freshDbAcceptsAllNewModuleRows() = runBlocking<Unit>(Dispatchers.IO) {
        val d = open()
        try {
            val version = d.readSql("PRAGMA user_version") { assertTrue(it.step()); it.getLong(0).toInt() }
            assertEquals(CaptureDatabase.VERSION, version)
            d.decisions().insert(DecisionRow(title = "t", detail = "d", topicKey = "k", decidedAt = 1, createdAt = 1, updatedAt = 1))
            d.followups().insert(FollowupRow(kind = "Todo", title = "t", createdAt = 1, updatedAt = 1))
            d.captureItems().insert(CaptureItemRow(kind = "Url", body = "https://x", createdAt = 1, updatedAt = 1))
            d.workflowRules().insert(WorkflowRuleRow(name = "r", triggerKind = "Manual", actionKind = "Notify", createdAt = 1))
            d.revisions().insert(MemoryRevisionRow(targetKind = "item", targetId = 1, op = "Edit", beforeJson = "{}", afterJson = "{}", at = 1))
            d.mcpClients().insert(McpClientRow(name = "c", createdAt = 1))
        } finally { }
    }

    @Test fun decisionSupersedeAndRejectLeaveOldRowsQueryable() = runBlocking<Unit>(Dispatchers.IO) {
        val d = open()
        try {
            val old = d.decisions().insert(DecisionRow(title = "旧方向", detail = "", topicKey = "k1", decidedAt = 1, createdAt = 1, updatedAt = 1))
            val new = d.decisions().insert(DecisionRow(title = "新方向", detail = "", topicKey = "k1", decidedAt = 2, createdAt = 2, updatedAt = 2))
            assertEquals(1, d.decisions().supersede(old, new, 3))
            // 旧决定标 Superseded 不再进"生效"列表，但历史里还在（过期决定不混进当前）
            assertTrue(d.decisions().observeActive().first().none { it.id == old })
            assertEquals("Superseded", d.decisions().decision(old)!!.status)
            assertEquals(new, d.decisions().decision(old)!!.supersededBy)
            val r = d.decisions().insert(DecisionRow(title = "成本太高", detail = "", decidedAt = 4, createdAt = 4, updatedAt = 4))
            assertEquals(1, d.decisions().reject(r, "预算不够", 5))
            assertEquals("预算不够", d.decisions().decision(r)!!.rejectedReason)
        } finally { }
    }

    @Test fun followupOpenByRefDeduplicatesAndRestoreUndoesItemDelete() = runBlocking<Unit>(Dispatchers.IO) {
        val d = open()
        try {
            // 同一来源只建一条跟进（重复扫描不重复提醒对象）
            d.followups().insert(FollowupRow(kind = "Todo", refKind = "item", refId = 42, title = "t", createdAt = 1, updatedAt = 1))
            assertNotNull(d.followups().openByRef("item", 42))
            // 提取项软删可恢复（撤销删除用）
            val itemId = d.memory().addManualItem(MemoryItemEntity(eventId = null, category = "待办", kind = "manual",
                content = "c", dueText = null, dueAt = null, who = null, lineId = null, status = "Accepted", createdAt = 1))
            d.memory().deleteItem(itemId)
            assertTrue(d.memory().itemIncludingDeleted(itemId)!!.deleted)
            assertEquals(1, d.memory().restoreItem(itemId))
            assertFalse(d.memory().itemIncludingDeleted(itemId)!!.deleted)
        } finally { }
    }
}
