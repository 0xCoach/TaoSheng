package app.resound.analysis

import androidx.room.*
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.contentOrNull

/** 分析目标：单条录音 / 一条原话 / 一件整理出的事 / 一条灵感 / 一个项目 / 一天全部记录。 */
enum class AnalysisTarget(val label: String) {
    Segment("一段录音"), Line("一条原话"), Event("一件事"), Idea("一条灵感"), Project("一个项目"), Day("一天的记录");
    companion object { fun of(name: String?) = entries.firstOrNull { it.name == name } }
}

/** 分析类型。 */
enum class AnalysisType(val label: String) {
    DeepResearch("深度研究"), Brainstorm("头脑风暴整理"), Business("商业机会分析"),
    Product("产品需求拆解"), ActionPlan("行动计划"), Summary("信息总结"), Compare("历史观点对比"),
    PhaseReport("阶段报告");
    companion object { fun of(name: String?) = entries.firstOrNull { it.name == name } ?: Summary }
}

enum class AnalysisStatus { Pending, Running, Succeeded, Failed;
    companion object { fun of(name: String?) = entries.firstOrNull { it.name == name } ?: Pending } }

/** 可编辑的分析模板：system 负责角色与输出结构，userTemplate 用 {{content}} 注入材料。 */
@Entity(tableName = "analysis_templates", indices = [Index("type")])
data class AnalysisTemplateRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val name: String,
    val systemPrompt: String,
    val userTemplate: String,
    @ColumnInfo(defaultValue = "0") val builtin: Boolean = false,
    @ColumnInfo(defaultValue = "0") val position: Int = 0,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

/** 一次分析运行的结果存档；promptText 保留当时实际发出的提示词快照。 */
@Entity(tableName = "analysis_runs", indices = [Index(value = ["targetKind", "targetId"]), Index("createdAt"), Index("status"), Index("projectId")])
data class AnalysisRunRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val targetKind: String,
    val targetId: Long,
    val type: String,
    val templateId: Long? = null,
    val templateName: String = "",
    val promptText: String = "",
    /** Pending / Running / Succeeded / Failed */
    val status: String = AnalysisStatus.Pending.name,
    val resultText: String = "",
    /** 这次分析引用的原始材料（句子/事件编号）JSON */
    val sourcesJson: String = "[]",
    val projectId: Long? = null,
    val model: String = "",
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val error: String? = null,
    val createdAt: Long,
    val finishedAt: Long? = null,
)

@Dao
abstract class AnalysisDao {
    @Transaction open suspend fun <T> transaction(block: suspend () -> T): T = block()

    // ---------- 模板 ----------
    @Insert abstract suspend fun insertTemplate(row: AnalysisTemplateRow): Long
    @Update abstract suspend fun updateTemplate(row: AnalysisTemplateRow)
    @Query("UPDATE analysis_templates SET deleted = 1, updatedAt = :at WHERE id = :id AND builtin = 0")
    abstract suspend fun deleteTemplate(id: Long, at: Long): Int
    @Query("SELECT * FROM analysis_templates WHERE deleted = 0 ORDER BY type, position, id")
    abstract fun observeTemplates(): Flow<List<AnalysisTemplateRow>>
    @Query("SELECT * FROM analysis_templates WHERE deleted = 0 AND type = :type ORDER BY position, id")
    abstract suspend fun templatesOf(type: String): List<AnalysisTemplateRow>
    @Query("SELECT * FROM analysis_templates WHERE id = :id") abstract suspend fun template(id: Long): AnalysisTemplateRow?
    @Query("SELECT COUNT(*) FROM analysis_templates WHERE builtin = 1")
    abstract suspend fun builtinCount(): Int
    @Query("SELECT name FROM analysis_templates WHERE builtin = 1 AND deleted = 0")
    abstract suspend fun builtinNames(): List<String>

    // ---------- 运行 ----------
    @Insert abstract suspend fun insertRun(row: AnalysisRunRow): Long
    @Update abstract suspend fun updateRun(row: AnalysisRunRow)
    @Query("SELECT * FROM analysis_runs WHERE id = :id") abstract suspend fun run(id: Long): AnalysisRunRow?
    @Query("SELECT * FROM analysis_runs WHERE id = :id") abstract fun observeRun(id: Long): Flow<AnalysisRunRow?>
    @Query("SELECT * FROM analysis_runs ORDER BY createdAt DESC, id DESC LIMIT 200")
    abstract fun observeRuns(): Flow<List<AnalysisRunRow>>
    @Query("SELECT * FROM analysis_runs WHERE targetKind = :kind AND targetId = :targetId ORDER BY createdAt DESC, id DESC")
    abstract fun observeRunsFor(kind: String, targetId: Long): Flow<List<AnalysisRunRow>>
    @Query("SELECT * FROM analysis_runs WHERE projectId = :projectId ORDER BY createdAt DESC, id DESC")
    abstract fun observeRunsForProject(projectId: Long): Flow<List<AnalysisRunRow>>
    @Query("SELECT * FROM analysis_runs WHERE status = 'Running'")
    abstract suspend fun running(): List<AnalysisRunRow>
    /** 同目标同类型的在途运行：防止连点重复发起付费模型调用 */
    @Query("SELECT * FROM analysis_runs WHERE targetKind = :kind AND targetId = :targetId AND type = :type AND status IN ('Pending','Running') ORDER BY createdAt DESC LIMIT 1")
    abstract suspend fun activeRun(kind: String, targetId: Long, type: String): AnalysisRunRow?
    /** 进程被杀留下的 Running 假活：启动时统一判失败 */
    @Query("UPDATE analysis_runs SET status = 'Failed', error = :error, finishedAt = :at WHERE status = 'Running'")
    abstract suspend fun failStaleRunning(error: String, at: Long): Int
    @Query("UPDATE analysis_runs SET status = 'Failed', error = :error, finishedAt = :at WHERE id = :id AND status IN ('Pending','Running')")
    abstract suspend fun failRun(id: Long, error: String, at: Long): Int
    @Query("DELETE FROM analysis_runs WHERE id = :id") abstract suspend fun deleteRun(id: Long)
}

object AnalysisSchema {
    fun create(db: SQLiteConnection) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS analysis_templates (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            type TEXT NOT NULL, name TEXT NOT NULL, systemPrompt TEXT NOT NULL, userTemplate TEXT NOT NULL,
            builtin INTEGER NOT NULL DEFAULT 0, position INTEGER NOT NULL DEFAULT 0,
            updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_analysis_templates_type ON analysis_templates(type)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS analysis_runs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            targetKind TEXT NOT NULL, targetId INTEGER NOT NULL, type TEXT NOT NULL, templateId INTEGER,
            templateName TEXT NOT NULL, promptText TEXT NOT NULL, status TEXT NOT NULL, resultText TEXT NOT NULL,
            sourcesJson TEXT NOT NULL, projectId INTEGER, model TEXT NOT NULL,
            promptTokens INTEGER NOT NULL, completionTokens INTEGER NOT NULL, error TEXT,
            createdAt INTEGER NOT NULL, finishedAt INTEGER)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_analysis_runs_targetKind_targetId ON analysis_runs(targetKind, targetId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_analysis_runs_createdAt ON analysis_runs(createdAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_analysis_runs_status ON analysis_runs(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_analysis_runs_projectId ON analysis_runs(projectId)")
    }
}

object AnalysisContract {
    const val CONTENT_SLOT = "{{content}}"
    const val CONTEXT_SLOT = "{{context}}"

    /** 模板是否可用：必须含 {{content}} 占位。安卓的正则对 } 更严格，这里手写查找不用正则。 */
    fun templateValid(template: String): Boolean = template.contains(CONTENT_SLOT)

    /** 渲染用户提示词；缺 {{context}} 时上下文拼在材料前。 */
    fun render(template: String, content: String, context: String): String {
        var text = template.replace(CONTENT_SLOT, content)
        text = if (text.contains(CONTEXT_SLOT)) text.replace(CONTEXT_SLOT, context)
        else if (context.isNotBlank()) "背景：$context\n\n$text" else text
        return text
    }

    /** 内置模板（首次建库/升级时写入，用户可复制修改，不能直接删）。 */
    fun builtinTemplates(now: Long): List<AnalysisTemplateRow> {
        fun t(type: AnalysisType, name: String, system: String, user: String, pos: Int) =
            AnalysisTemplateRow(type = type.name, name = name, systemPrompt = system, userTemplate = user, builtin = true, position = pos, updatedAt = now)
        val baseSystem = "你是用户的个人 AI 助手，正在分析他的语音记录。用中文回答，分条列出，结论要具体、可执行，不要复述原材料，不要编造材料里没有的事实。"
        return listOf(
            t(AnalysisType.DeepResearch, "默认·深度研究", baseSystem,
                "请对以下内容做深度研究：拆解核心问题、列出需要进一步查证的事实、给出研究路径和可能的结论方向。\n\n{{content}}", 0),
            t(AnalysisType.Brainstorm, "默认·头脑风暴整理", baseSystem,
                "这是一次头脑风暴的记录。请整理出：1) 提出的所有点子清单；2) 每个点子的亮点与风险；3) 最值得先做的三个建议及理由。\n\n{{content}}", 0),
            t(AnalysisType.Business, "默认·商业机会", baseSystem,
                "请做商业机会分析：目标用户是谁、痛点是否真实、可能的变现方式、竞争与差异化、最小验证步骤。\n\n{{content}}", 0),
            t(AnalysisType.Product, "默认·产品需求拆解", baseSystem,
                "请把以下内容拆解成产品需求：用户故事、功能点、优先级建议、关键交互与边界情况、验收标准。\n\n{{content}}", 0),
            t(AnalysisType.ActionPlan, "默认·行动计划", baseSystem,
                "请把以下内容整理成可执行的行动计划：按顺序列出步骤、每步产出物、预计耗时、依赖与风险、第一步是什么。\n\n{{content}}", 0),
            t(AnalysisType.Summary, "默认·信息总结", baseSystem,
                "请总结以下内容：要点列表、关键结论、提到的待办与约定、值得记住的事实。控制在 600 字以内。\n\n{{content}}", 0),
            t(AnalysisType.Compare, "默认·观点对比", baseSystem,
                "请对比材料中不同时期的观点：哪些一致、哪些变了、变化的可能原因、当前倾向是什么。引用原话注明时间。\n\n{{content}}", 0),
            t(AnalysisType.PhaseReport, "默认·项目阶段报告", baseSystem,
                "请根据材料生成本项目的阶段报告，按这个结构分节：1) 项目总体情况；2) 本期已完成事项；3) 新增决定与变更（旧方案→新方案要注明依据）；4) 未完成任务和承诺；5) 风险与待确认问题；6) 下一阶段建议行动；7) 来源依据（列出材料中的编号）。只写材料里有的事实，材料不足的小节写“暂无记录”。输出 JSON：{\"report\":\"报告正文，分节用 ## 标题和 - 列表。\"}\n\n{{content}}", 0),
        )
    }

    /**
     * 报告显示文本：模型调用统一要求 JSON 输出，模型可能把正文包进
     * {"report":"…"} / {"messages":[{"content":"…"}]} 这类信封——展示时剥掉，剥不了就原样显示。
     * resultText 仍按原始返回落库留档，这里只处理呈现。
     */
    fun displayText(raw: String): String {
        val t = raw.trim()
        if (!t.startsWith("{") && !t.startsWith("[")) return raw
        val root = runCatching { app.resound.platform.SharedJson.parseToJsonElement(t) }.getOrNull() ?: return raw
        fun leaf(el: kotlinx.serialization.json.JsonElement?, depth: Int, longOnly: Boolean): String? {
            if (el == null || depth > 4) return null
            return when (el) {
                is kotlinx.serialization.json.JsonPrimitive ->
                    el.contentOrNull?.takeIf { it.isNotBlank() && (!longOnly || it.length >= 60) }
                is kotlinx.serialization.json.JsonArray -> el.firstOrNull()?.let { leaf(it, depth + 1, longOnly) }
                is kotlinx.serialization.json.JsonObject -> {
                    for (k in arrayOf("report", "content", "text", "result", "answer", "summary", "output", "body", "reply")) {
                        leaf(el[k], depth + 1, longOnly = false)?.let { return it }
                    }
                    el.values.firstNotNullOfOrNull { leaf(it, depth + 1, longOnly) }
                }
                else -> null
            }
        }
        return leaf(root, 0, longOnly = true) ?: raw
    }
}
