package app.resound.analysis

import app.resound.data.CaptureDatabase
import app.resound.digest.LlmPreset
import app.resound.digest.LlmReply
import kotlinx.coroutines.CoroutineDispatcher
import app.resound.platform.NowMillis
import app.resound.platform.ZoneProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive

/** 一次分析要喂给模型的材料：正文 + 背景 + 可追溯的编号清单。 */
data class AnalysisMaterial(val content: String, val context: String, val sourcesJson: String)

/**
 * AI 智能分析：对录音/原话/事件/灵感/项目/整天发起分析。
 * 模型未配置时：材料组装、模板管理、导出分享、结果导入全部可用，运行显示“等待配置服务”。
 */
class AnalysisRepository(
    private val db: CaptureDatabase,
    private val scope: CoroutineScope,
    private val now: NowMillis,
    private val zone: ZoneProvider,
    private val io: CoroutineDispatcher,
    /** 当前模型凭证（preset+key）；由 AndroidDigestSettings.skillCredentials 提供 */
    private val credentials: () -> Pair<LlmPreset, String>,
    /** 模型未配置判定（含允许空密钥的本地服务与请求头鉴权） */
    private val canUseWithoutKey: (LlmPreset) -> Boolean,
    private val hasBackup: (LlmPreset) -> Boolean,
    /** failover 包装后的 chat 调用：(preset, key, system, user) -> reply */
    private val chat: suspend (LlmPreset, String, String, String) -> LlmReply,
) {
    private val dao get() = db.analysis()
    private val memory get() = db.memory()

    val templates: Flow<List<AnalysisTemplateRow>> get() = dao.observeTemplates()
    val runs: Flow<List<AnalysisRunRow>> get() = dao.observeRuns()
    fun runsFor(kind: AnalysisTarget, id: Long) = dao.observeRunsFor(kind.name, id)
    fun runsForProject(projectId: Long) = dao.observeRunsForProject(projectId)
    fun observeRun(id: Long) = dao.observeRun(id)

    /** 模型是否可用（含备用链路与免密钥端点），驱动“等待配置服务”提示。 */
    fun llmReady(): Boolean {
        val (preset, key) = credentials()
        return key.isNotBlank() || canUseWithoutKey(preset) || hasBackup(preset)
    }

    /** 首次装配时种下内置模板；用户可另存副本修改，内置的不可删。 */
    suspend fun ensureSeeded() = withContext(io) {
        if (dao.builtinCount() == 0) AnalysisContract.builtinTemplates(now.now()).forEach { dao.insertTemplate(it) }
        Unit
    }

    suspend fun saveTemplate(id: Long?, type: AnalysisType, name: String, system: String, user: String): Result<Long> =
        withContext(io) {
            runCatching {
                require(name.isNotBlank()) { "模板名不能为空" }
                require(AnalysisContract.templateValid(user)) { "模板里要有 {{content}}，分析时会替换成材料" }
                when (val old = id?.let { dao.template(it) }) {
                    null -> dao.insertTemplate(AnalysisTemplateRow(type = type.name, name = name.trim(),
                        systemPrompt = system, userTemplate = user, updatedAt = now.now()))
                    else -> dao.updateTemplate(old.copy(name = name.trim(), systemPrompt = system,
                        userTemplate = user, updatedAt = now.now())).let { old.id }
                }
            }
        }

    suspend fun deleteTemplate(id: Long) = withContext(io) { dao.deleteTemplate(id, now.now()) }

    suspend fun template(id: Long?) = if (id == null) null else dao.template(id)
    suspend fun templatesOf(type: AnalysisType) = withContext(io) { dao.templatesOf(type.name) }

    // ---------- 材料组装 ----------

    private suspend fun segmentMaterial(segmentId: Long): AnalysisMaterial {
        val pack = db.transcribe().exportSegments(listOf(segmentId)).firstOrNull()
            ?: return AnalysisMaterial("（录音不存在或已删除）", "", "[]")
        val s = pack.segment
        val body = buildString {
            appendLine("录音段 #${s.id}：${stamp(s.startAt)} ~ ${stamp(s.endAt)}")
            pack.lines.forEach { l ->
                append("[${clock(l.startAt)}] ")
                l.speakerName?.let { append("$it: ") }
                appendLine(l.text)
            }
        }
        return AnalysisMaterial(body, "", "[{\"kind\":\"Segment\",\"id\":$segmentId}]")
    }

    private suspend fun eventMaterial(eventId: Long): AnalysisMaterial {
        val event = memory.event(eventId) ?: return AnalysisMaterial("（事件不存在或已删除）", "", "[]")
        val lines = memory.eventLines(eventId).first()
        val body = buildString {
            appendLine("事件 #${event.id}《${event.title}》（${event.scene}，${stamp(event.startAt)} ~ ${stamp(event.endAt)}）")
            if (event.summary.isNotBlank()) appendLine("摘要：${event.summary}")
            runCatching {
                (app.resound.platform.SharedJson.parseToJsonElement(event.pointsJson) as? JsonArray)
                    ?.forEach { appendLine("· ${it.jsonPrimitive.content}") }
            }
            appendLine("--- 原话 ---")
            lines.forEach { l -> append("[${clock(l.startAt)}] "); l.speakerName?.let { append("$it: ") }; appendLine(l.text) }
        }
        return AnalysisMaterial(body, "", "[{\"kind\":\"Event\",\"id\":$eventId}]")
    }

    private suspend fun lineMaterial(lineId: Long): AnalysisMaterial {
        val line = memory.lineById(lineId) ?: return AnalysisMaterial("（原话不存在或已删除）", "", "[]")
        val context = memory.segmentLinesOf(lineId).joinToString("\n") { l ->
            "[${clock(l.startAt)}] ${l.speakerName?.let { "$it: " } ?: ""}${l.text}"
        }
        return AnalysisMaterial(
            "聚焦这句：${line.text}\n\n所在录音段全文：\n$context", "",
            "[{\"kind\":\"Line\",\"id\":$lineId}]")
    }

    private suspend fun ideaMaterial(ideaId: Long): AnalysisMaterial {
        val idea = db.ideas().idea(ideaId) ?: return AnalysisMaterial("（灵感不存在或已删除）", "", "[]")
        val body = buildString {
            appendLine("灵感 #${idea.id}《${idea.title}》")
            appendLine("类别：${idea.category}；状态：${app.resound.inbox.IdeaStatus.of(idea.status).label}")
            appendLine(idea.content)
            idea.sourceText?.let { appendLine("--- 原话出处 ---\n$it") }
        }
        return AnalysisMaterial(body, "", "[{\"kind\":\"Idea\",\"id\":$ideaId}]")
    }

    private suspend fun projectMaterial(projectId: Long): AnalysisMaterial {
        val projects = db.projects()
        val project = projects.project(projectId) ?: return AnalysisMaterial("（项目不存在或已删除）", "", "[]")
        val marks = projects.marks(projectId)
        val links = projects.links(projectId)
        val sources = mutableListOf<String>()
        val body = buildString {
            appendLine("项目 #${project.id}《${project.name}》（${if (project.status == "Archived") "已归档" else "进行中"}）")
            if (project.note.isNotBlank()) appendLine("说明：${project.note}")
            if (marks.isNotEmpty()) {
                appendLine("--- 项目记忆 ---")
                marks.take(40).forEach { m ->
                    sources += "{\"kind\":\"Mark\",\"id\":${m.id}}"
                    appendLine("[${stamp(m.at)}] 【${app.resound.projects.ProjectMarkKind.of(m.kind).label}】${m.title}" +
                        (if (m.content.isNotBlank()) "：${m.content}" else "") +
                        (m.reason?.let { "（原因：$it）" } ?: ""))
                }
            }
            if (links.isNotEmpty()) {
                appendLine("--- 关联记录 ---")
                links.take(30).forEach { l ->
                    appendLine("· ${app.resound.projects.ProjectRefKind.of(l.refKind)?.label ?: l.refKind} #${l.refId}" +
                        (l.note?.let { "（$it）" } ?: ""))
                    appendLine("  ${resolveRef(l.refKind, l.refId).take(300)}")
                }
            }
        }
        return AnalysisMaterial(body, "", sources.joinToString(",", "[", "]"))
    }

    private suspend fun dayMaterial(dayStart: Long): AnalysisMaterial {
        val dayEnd = dayStart + 86_400_000
        val events = memory.events(dayStart, dayEnd).first()
        val items = memory.itemsBetween(dayStart, dayEnd).first()
        val sources = mutableListOf<String>()
        val body = buildString {
            val date = java.time.Instant.ofEpochMilli(dayStart).atZone(java.time.ZoneId.of(zone.zoneId())).toLocalDate()
            appendLine("$date 全天记录")
            events.forEach { e ->
                sources += "{\"kind\":\"Event\",\"id\":${e.id}}"
                appendLine("【${e.scene}】《${e.title}》${stamp(e.startAt)} ~ ${stamp(e.endAt)}")
                appendLine("  ${e.summary}")
            }
            val todos = items.filter { it.category in setOf("待办", "约定") }
            if (todos.isNotEmpty()) {
                appendLine("--- 待办与约定 ---")
                todos.forEach { appendLine("· [${it.status}] ${it.content}${it.dueText?.let { d -> "（$d）" } ?: ""}") }
            }
            val ideas = items.filter { it.category == "灵感" }
            if (ideas.isNotEmpty()) {
                appendLine("--- 灵感 ---")
                ideas.forEach { appendLine("· ${it.content}") }
            }
        }
        return AnalysisMaterial(body, "", sources.joinToString(",", "[", "]"))
    }

    /** 解析关联记录为一段可读文字；取不到给占位说明。 */
    private suspend fun resolveRef(kind: String, refId: Long): String = runCatching {
        when (app.resound.projects.ProjectRefKind.of(kind)) {
            app.resound.projects.ProjectRefKind.Event -> memory.event(refId)?.let { "${it.title}：${it.summary}" }
            app.resound.projects.ProjectRefKind.Item -> memory.itemIncludingDeleted(refId)?.content
            app.resound.projects.ProjectRefKind.Line -> memory.lineById(refId)?.text
            app.resound.projects.ProjectRefKind.Segment -> db.transcribe().exportSegments(listOf(refId)).firstOrNull()
                ?.let { "录音段（${it.lines.size} 句）：" + it.lines.take(5).joinToString(" ") { l -> l.text } }
            app.resound.projects.ProjectRefKind.Idea -> db.ideas().idea(refId)?.let { "${it.title}：${it.content.take(200)}" }
            app.resound.projects.ProjectRefKind.Analysis -> dao.run(refId)?.resultText?.take(300)
            app.resound.projects.ProjectRefKind.Task -> db.agent().task(refId)?.let { "${it.title}：${it.status}" }
            null -> null
        } ?: "（内容不可用）"
    }.getOrDefault("（内容不可用）")

    suspend fun material(target: AnalysisTarget, targetId: Long): AnalysisMaterial = withContext(io) {
        val cap = 60_000
        val m = when (target) {
            AnalysisTarget.Segment -> segmentMaterial(targetId)
            AnalysisTarget.Line -> lineMaterial(targetId)
            AnalysisTarget.Event -> eventMaterial(targetId)
            AnalysisTarget.Idea -> ideaMaterial(targetId)
            AnalysisTarget.Project -> projectMaterial(targetId)
            AnalysisTarget.Day -> dayMaterial(targetId)
        }
        if (m.content.length <= cap) m else m.copy(content = m.content.take(cap) + "\n（材料过长已截断）")
    }

    // ---------- 运行 ----------

    /** 创建运行并入队执行；未配置模型时运行落为 Failed（错误提示“未配置模型服务”），材料与提示词仍落库可导出。 */
    suspend fun start(target: AnalysisTarget, targetId: Long, type: AnalysisType,
        templateId: Long? = null, projectId: Long? = null): Long = withContext(io) {
        val template = (templateId?.let { dao.template(it) } ?: dao.templatesOf(type.name).firstOrNull())
        val material = material(target, targetId)
        val userPrompt = template?.let { AnalysisContract.render(it.userTemplate, material.content, material.context) }
            ?: material.content
        val run = dao.insertRun(
            AnalysisRunRow(
                targetKind = target.name, targetId = targetId, type = type.name,
                templateId = template?.id, templateName = template?.name ?: "默认",
                promptText = userPrompt, status = AnalysisStatus.Pending.name,
                sourcesJson = material.sourcesJson, projectId = projectId,
                createdAt = now.now(),
            ),
        )
        execute(run, template?.systemPrompt ?: DEFAULT_SYSTEM)
        run
    }

    private fun execute(runId: Long, system: String) {
        scope.launch {
            val run = dao.run(runId) ?: return@launch
            val (preset, key) = credentials()
            if (key.isBlank() && !canUseWithoutKey(preset) && !hasBackup(preset)) {
                dao.failRun(runId, "未配置模型服务：到“设置 → 模型与整理时间”添加服务商，或导出材料到其他 AI 应用", now.now())
                return@launch
            }
            dao.updateRun(run.copy(status = AnalysisStatus.Running.name))
            try {
                val reply = chat(preset, key, system, run.promptText)
                dao.updateRun(
                    run.copy(
                        status = AnalysisStatus.Succeeded.name, resultText = reply.content,
                        model = reply.actualModel ?: preset.model,
                        promptTokens = reply.promptTokens, completionTokens = reply.completionTokens,
                        finishedAt = now.now(),
                    ),
                )
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                dao.failRun(runId, e.message?.take(120) ?: "分析失败", now.now())
            }
        }
    }

    /** 重跑一次：用当时同样的模板与材料重新生成一条运行（保留历史结果）。 */
    suspend fun rerun(runId: Long): Long = withContext(io) {
        val old = dao.run(runId) ?: return@withContext 0L
        start(AnalysisTarget.of(old.targetKind) ?: AnalysisTarget.Event, old.targetId,
            AnalysisType.of(old.type), old.templateId, old.projectId)
    }

    suspend fun deleteRun(id: Long) = withContext(io) { dao.deleteRun(id) }

    // ---------- 无模型 / 外部 AI 工作流 ----------

    /** 导出给外部 AI 的材料 + 提示词文本（复制/分享）。 */
    suspend fun exportPrompt(runId: Long): String = withContext(io) {
        val run = dao.run(runId) ?: return@withContext ""
        buildString {
            if (run.templateName.isNotBlank()) appendLine("# 分析类型：${AnalysisType.of(run.type).label} · ${run.templateName}")
            appendLine(run.promptText)
        }
    }

    /** 结果导出文本（含来源与模型），供分享或存进外部知识库。 */
    suspend fun exportResult(runId: Long): String = withContext(io) {
        val run = dao.run(runId) ?: return@withContext ""
        buildString {
            appendLine("# ${AnalysisType.of(run.type).label} · ${AnalysisTarget.of(run.targetKind)?.label ?: run.targetKind} #${run.targetId}")
            appendLine("时间：${stamp(run.createdAt)}；模型：${run.model.ifBlank { "未知" }}")
            appendLine()
            appendLine(run.resultText.ifBlank { "（无结果）" })
        }
    }

    /** 把外部 AI 的回答粘回存成一次成功运行（工作流：导出 → 外部分析 → 导入结果）。 */
    suspend fun importResult(runId: Long, text: String) = withContext(io) {
        val run = dao.run(runId) ?: return@withContext
        if (text.isNotBlank()) dao.updateRun(run.copy(status = AnalysisStatus.Succeeded.name,
            resultText = text.trim(), model = "外部导入", finishedAt = now.now()))
        Unit
    }

    private fun stamp(at: Long): String {
        val t = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.of(zone.zoneId()))
        return "${t.toLocalDate()} ${clock(at)}"
    }
    private fun clock(at: Long): String {
        val t = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.of(zone.zoneId()))
        fun Int.p() = toString().padStart(2, '0')
        return "${t.hour.p()}:${t.minute.p()}"
    }

    companion object {
        const val DEFAULT_SYSTEM = "你是用户的个人 AI 助手，正在分析他的语音记录。用中文回答，分条列出，结论要具体、可执行，不要复述原材料，不要编造材料里没有的事实。"
    }
}
