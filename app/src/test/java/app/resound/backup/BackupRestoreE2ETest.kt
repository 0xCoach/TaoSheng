package app.resound.backup

import android.app.Application
import androidx.sqlite.driver.AndroidSQLiteDriver
import app.resound.agent.AgentEndpointRow
import app.resound.agent.AgentTaskRow
import app.resound.analysis.AnalysisRunRow
import app.resound.analysis.AnalysisTemplateRow
import app.resound.collect.CaptureItemRow
import app.resound.data.CaptureDatabase
import app.resound.data.SceneSessionRow
import app.resound.data.SegmentEntity
import app.resound.data.open
import app.resound.decision.DecisionRow
import app.resound.envsound.EnvSoundRow
import app.resound.followup.FollowupRow
import app.resound.inbox.IdeaRow
import app.resound.mcp.McpClientRow
import app.resound.projects.ProjectLinkRow
import app.resound.projects.ProjectMarkRow
import app.resound.projects.ProjectRow
import app.resound.revise.MemoryRevisionRow
import app.resound.rules.RuleRunRow
import app.resound.rules.WorkflowRuleRow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.UUID

/**
 * R4 备份恢复完整性 E2E：
 * - 真实测试库覆盖本轮补齐的 16 张表 + 经典表，跨两天 → 分卷 → 重建 → 再分卷，逐字节比对。
 * - 缺失分卷、损坏分卷、旧备份缺新表、密钥不混入明文备份、悬空外键回滚。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BackupRestoreE2ETest {
    private lateinit var context: android.content.Context
    private lateinit var root: File
    private val day1 = 1763251200000L // 2025-11-16
    private val day2 = 1763337600000L // 2025-11-17

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        root = File(context.cacheDir, "backup-e2e-${UUID.randomUUID()}").apply { deleteRecursively(); mkdirs() }
    }
    @After fun cleanup() { root.deleteRecursively() }

    private fun newDb(name: String): CaptureDatabase =
        CaptureDatabase.open(context, File(root, name).absolutePath)

    private fun populated(name: String): File {
        val db = newDb(name)
        try { runBlocking { populate(db) } } finally { db.close() }
        return File(root, name)
    }

    /** 覆盖 16 张补齐表 + segments/env_sounds；day1/day2 各一半验证跨日分卷；含 中文 字段验 UTF-8 */
    private suspend fun populate(db: CaptureDatabase) {
        db.capture().segment(SegmentEntity(id = 1, startAt = day1, endAt = day1 + 1000,
            sourceId = "mic", path = "/sdcard/audio/seg-1.m4a", sizeBytes = 4096, status = "Done"))
        db.capture().segment(SegmentEntity(id = 2, startAt = day2, endAt = day2 + 1000,
            sourceId = "mic", path = "/sdcard/audio/seg-2.m4a", sizeBytes = 8192, status = "Done"))
        db.envSound().insert(EnvSoundRow(1, day1, day1 + 500, "/sdcard/audio/seg-1.m4a", "Keep",
            null, "[]", "[]", "[]", false, false, restored = true, reportedWrong = true))
        db.projects().insert(ProjectRow(id = 1, name = "alpha-项目", note = "note", createdAt = day1, updatedAt = day1))
        db.projects().insertLink(ProjectLinkRow(projectId = 1, refKind = "Idea", refId = 1, note = "link", createdAt = day1))
        db.projects().insertMark(ProjectMarkRow(projectId = 1, kind = "Decision", title = "milestone", at = day2))
        db.ideas().insert(IdeaRow(title = "ideaA-灵感", content = "content-A", projectId = 1, createdAt = day1, updatedAt = day1))
        db.ideas().insert(IdeaRow(title = "ideaB", content = "content-B", createdAt = day2, updatedAt = day2))
        db.decisions().insert(DecisionRow(title = "decisionA-决策", detail = "plan one", projectId = 1, decidedAt = day1, createdAt = day1, updatedAt = day1))
        db.decisions().insert(DecisionRow(title = "decisionB", detail = "defer", decidedAt = day2, createdAt = day2, updatedAt = day2))
        db.followups().insert(FollowupRow(kind = "Commitment", refKind = "decision", refId = 1,
            title = "followup-A", sourceText = "原话证据", createdAt = day1, updatedAt = day1, nextCheckAt = day2))
        db.captureItems().insert(CaptureItemRow(kind = "Url", body = "https://example.com/a",
            title = "capture-A", projectId = 1, createdAt = day1, updatedAt = day1))
        db.captureItems().insert(CaptureItemRow(kind = "Image", body = "/inbox/pic.png",
            localPath = "/files/inbox/pic.png", ocrText = "图里文字", createdAt = day2, updatedAt = day2))
        db.analysis().insertTemplate(AnalysisTemplateRow(type = "Research", name = "tpl-A",
            systemPrompt = "sys", userTemplate = "u", updatedAt = day1))
        db.analysis().insertRun(AnalysisRunRow(targetKind = "Idea", targetId = 1, type = "Research",
            templateId = 1, status = "Done", resultText = "result", createdAt = day1))
        db.agent().insertEndpoint(AgentEndpointRow(name = "endpoint-A", url = "https://agent.example.com",
            createdAt = day1, updatedAt = day1))
        db.agent().insertTask(AgentTaskRow(taskId = "task-uuid-1", endpointId = 1, type = "Research",
            title = "task-A", payloadJson = """{"q":"x"}""", status = "Done",
            confirmedAt = day1, dispatchedAt = day1, finishedAt = day2, createdAt = day1, updatedAt = day2))
        db.workflowRules().insert(WorkflowRuleRow(name = "rule-A", triggerKind = "EveningNote",
            actionKind = "CreateFollowup", createdAt = day1))
        db.workflowRules().insertRun(RuleRunRow(ruleId = 1, at = day2, trigger = "EveningNote",
            status = "Fired", detail = "fired once"))
        db.revisions().insert(MemoryRevisionRow(targetKind = "Idea", targetId = 1, op = "Edit",
            beforeJson = """{"title":"ideaA"}""", afterJson = """{"title":"ideaA2"}""", at = day2))
        db.mcpClients().insert(McpClientRow(name = "client-A", tokenHint = "abcd1234",
            projectScope = 1, createdAt = day1, lastSeenAt = day2))
        db.sceneSessions().insert(SceneSessionRow(kind = "PrivacyPaused", source = "Manual",
            startedAt = day1, endedAt = day1 + 3600000, reason = "privacy pause"))
        db.sceneSessions().insert(SceneSessionRow(kind = "QuietHours", source = "Rule",
            startedAt = day2, reason = "sleep 23:00-7:00"))
    }

    private fun volumesOf(dbFile: File, dir: File): Pair<Int, Map<String, File>> =
        // populated 已关闭库：冷文件直接读，等价于一致性快照
        DayVolumeRows.write(dbFile, dir)

    private fun counts(dbFile: File): Map<String, Int> = buildMap {
        AndroidSQLiteDriver().open(dbFile.path).use { db ->
            (DayVolumeRows.dated.keys + DayVolumeRows.global).forEach { table ->
                db.prepare("SELECT COUNT(*) FROM `$table`").use { c ->
                    check(c.step()); put(table, c.getLong(0).toInt())
                }
            }
        }
    }

    @Test fun allClassifiedTablesRoundTripByteIdentical() {
        val original = populated("original.db")
        val (version, files) = volumesOf(original, File(root, "vol-orig"))
        // 分卷确实跨两天 + 全局卷
        assertTrue(files.containsKey("2025-11-16")); assertTrue(files.containsKey("2025-11-17"))
        assertTrue(files.containsKey("state"))

        val restored = File(root, "restored.db")
        DayVolumeRows.rebuild(context, restored, version, files.getValue("state"),
            files.filterKeys { it != "state" }.values.toList())

        // 逐表行数一致
        assertEquals(counts(original), counts(restored))
        // 主键/外键/内容逐字节一致：对重建库再跑一次分卷，两个分卷目录必须完全相同
        val (_, reFiles) = volumesOf(restored, File(root, "vol-restored"))
        assertEquals(files.keys, reFiles.keys)
        files.forEach { (day, f) ->
            assertEquals("分卷 $day 内容不一致", f.readBytes().toList(), reFiles.getValue(day).readBytes().toList())
        }
        // 文件引用字段（录音路径）确实保留
        AndroidSQLiteDriver().open(restored.path).use { db ->
            db.prepare("SELECT path FROM segments ORDER BY id").use { c ->
                check(c.step()); assertEquals("/sdcard/audio/seg-1.m4a", c.getText(0))
                check(c.step()); assertEquals("/sdcard/audio/seg-2.m4a", c.getText(0))
            }
        }
    }

    @Test fun missingDayVolumeLosesOnlyThatDay() {
        val original = populated("original.db")
        val (version, files) = volumesOf(original, File(root, "vol"))
        files.getValue("2025-11-17").delete()
        val restored = File(root, "restored.db")
        DayVolumeRows.rebuild(context, restored, version, files.getValue("state"),
            files.filterKeys { it != "state" && it != "2025-11-17" }.values.toList())
        val counts = counts(restored)
        assertEquals(1, counts["segments"]) // day1 的 segment 还在
        assertEquals(1, counts["ideas"])
        assertEquals(1, counts["decisions"]) // day2 的 decisionB 丢了
        assertEquals(1, counts["scene_sessions"]) // day2 的 QuietHours 会话丢了
        assertEquals(0, counts["project_marks"]) // project_marks 记在 day2
        // 全局卷无损
        assertEquals(1, counts["projects"]); assertEquals(1, counts["project_links"])
        assertEquals(1, counts["analysis_templates"]); assertEquals(1, counts["agent_endpoints"])
        assertEquals(1, counts["workflow_rules"]); assertEquals(1, counts["mcp_clients"])
    }

    @Test fun corruptedVolumeFailsRestoreAndLeavesNoMixedState() {
        val original = populated("original.db")
        val (version, files) = volumesOf(original, File(root, "vol"))
        // 篡改分卷：破坏一行 JSON
        files.getValue("2025-11-16").appendText("{broken json line")
        val restored = File(root, "restored.db")
        try {
            DayVolumeRows.rebuild(context, restored, version, files.getValue("state"),
                files.filterKeys { it != "state" }.values.toList())
            fail("损坏分卷必须恢复失败")
        } catch (expected: Exception) { }
        // 失败回滚：恢复库不存在或为空表，不留混合态；原库行数不变
        val after = counts(restored)
        assertTrue("恢复失败后不得残留部分数据", after.values.all { it == 0 })
        assertEquals(2, counts(original)["segments"])
    }

    @Test fun danglingForeignKeyRollsBackWholeRestore() {
        val original = populated("original.db")
        val (version, files) = volumesOf(original, File(root, "vol"))
        // 注入一行指向不存在 segment 的 env_sounds（外键悬空）
        files.getValue("2025-11-16").appendText(JSONObject()
            .put("table", "env_sounds")
            .put("values", JSONObject().put("segmentId", 999).put("startAt", day1).put("endAt", day1 + 1)
                .put("path", "/x").put("verdict", "Keep"))
            .toString() + "\n")
        val restored = File(root, "restored.db")
        try {
            DayVolumeRows.rebuild(context, restored, version, files.getValue("state"),
                files.filterKeys { it != "state" }.values.toList())
            fail("悬空外键必须回滚")
        } catch (expected: Exception) { }
        assertTrue(counts(restored).values.all { it == 0 })
    }

    @Test fun legacyBackupWithoutNewTablesRestoresAndLeavesThemEmpty() {
        // 模拟"修复前生成的旧备份"：只有旧表数据（segments + projects），16 张新表完全不在分卷里
        val original = populated("original.db")
        val (version, files) = volumesOf(original, File(root, "vol"))
        // 从分卷中剔除所有新表行——等价于旧版本备份从未写过它们
        files.values.forEach { f ->
            val kept = f.readLines().filter {
                it.isBlank() || JSONObject(it).getString("table") !in NEW_TABLES
            }
            f.writeText(kept.joinToString("\n", postfix = "\n"))
        }
        val restored = File(root, "restored.db")
        DayVolumeRows.rebuild(context, restored, version, files.getValue("state"),
            files.filterKeys { it != "state" }.values.toList())
        val counts = counts(restored)
        assertEquals(2, counts["segments"]); assertEquals(1, counts["projects"])
        // 旧备份从未包含的表明确列为不可恢复
        NEW_TABLES.forEach { assertEquals("旧备份不应凭空恢复 $it", 0, counts[it]) }
    }

    @Test fun secretsExportToSecretsJsonNeverToPlainSettings() {
        listOf("mcp-secrets" to "mcp_1", "agent-secrets" to "endpoint-9", "transcribe" to "encryptedKey")
            .forEach { (store, key) ->
                context.getSharedPreferences(store, 0).edit().putString(key, "ENC_BLOB_$key").commit()
            }
        context.getSharedPreferences("assistant", 0).edit().putBoolean("mcpEnabled", true).commit()
        try {
            val out = File(root, "prefs").apply { mkdirs() }
            BackupSettings.capture(context, out) { _, key -> "PLAINTEXT_$key" }
            val settings = File(out, "settings.json").readText()
            val secrets = File(out, "secrets.json").readText()
            // 明文密钥值只进 secrets.json；settings.json 不含密文值也不含密钥键名
            assertTrue(secrets.contains("PLAINTEXT_mcp_1"))
            assertTrue(secrets.contains("PLAINTEXT_endpoint-9"))
            assertTrue(secrets.contains("PLAINTEXT_encryptedKey"))
            assertFalse(settings.contains("PLAINTEXT_"))
            assertFalse(settings.contains("ENC_BLOB_"))
            assertFalse(settings.contains("encryptedKey"))
            assertTrue(settings.contains("mcpEnabled"))
        } finally {
            listOf("mcp-secrets", "agent-secrets", "transcribe", "assistant").forEach {
                context.getSharedPreferences(it, 0).edit().clear().commit()
            }
        }
    }

    @Test fun encryptedBackupMagicAndRoundTrip() {
        val plain = File(root, "backup.zip").apply { writeText("secret-backup-payload") }
        val encrypted = File(root, "backup.zip.enc")
        BackupEncryption.encrypt(plain, encrypted, "测试口令-123")
        assertTrue(BackupEncryption.isEncrypted(encrypted))
        assertFalse(BackupEncryption.isEncrypted(plain))
        val decrypted = File(root, "decrypted.zip")
        BackupEncryption.decrypt(encrypted, decrypted, stored = null, supplied = "测试口令-123")
        assertEquals(plain.readText(), decrypted.readText())
        try { BackupEncryption.decrypt(encrypted, File(root, "bad.zip"), null, "wrong-pass"); fail("错误口令必须解密失败") }
        catch (expected: BackupFailure) { assertEquals("口令不对", expected.human) }
        assertFalse(File(root, "bad.zip").exists()) // 解密失败不留半成品
    }

    companion object {
        /** 本轮补齐备份分类的表：旧备份没有它们的任何数据，不可恢复。 */
        val NEW_TABLES = setOf("ideas", "project_links", "project_marks", "analysis_templates",
            "analysis_runs", "agent_endpoints", "agent_tasks", "decisions", "followups",
            "capture_items", "workflow_rules", "rule_runs", "memory_revisions", "mcp_clients",
            "scene_sessions")
    }
}
