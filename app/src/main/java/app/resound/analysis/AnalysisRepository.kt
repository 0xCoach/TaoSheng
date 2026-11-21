package app.resound.analysis

import app.resound.data.CaptureDatabase
import app.resound.digest.LlmPreset
import app.resound.digest.LlmReply
import app.resound.digest.OFFICIAL_PROVIDER_ID
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
        return preset.id == OFFICIAL_PROVIDER_ID || key.isNotBlank() || canUseWithoutKey(preset) || hasBackup(preset)
    }

    /** 种下内置模板：首次全种，之后只补缺的（升级新增的类型）。同名内置行不重复插。 */
    suspend fun ensureSeeded() = withContext(io) {
        val existing = dao.builtinNames().toSet()
        AnalysisContract.builtinTemplates(now.now()).filter { it.name !in existing }.forEach { dao.insertTemplate(it) }
        Unit
    }

    /** 进程被杀会留下永远是 Running 的假活运行：启动时统一判失败，界面不再卡“分析中”。 */
    suspend fun recoverStaleRunning() = withContext(io) { dao.failStaleRunning("应用重启，分析中断", now.now()) }

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

    /**
     * 项目材料：记忆/决定/灵感/关联记录/待办与跟进 全量取证。
     * windowStart 非空时只收该时点之后发生的（阶段报告"本周/本月"窗口）；
     * 当前仍生效的决定与未闭环事项不受窗口限制——报告需要看到当下状态。
     */
    private suspend fun projectMaterial(projectId: Long, windowStart: Long? = null): AnalysisMaterial {
        val projects = db.projects()
        val project = projects.project(projectId) ?: return AnalysisMaterial("（项目不存在或已删除）", "", "[]")
        val marks = projects.marks(projectId).filter { !it.deleted && (windowStart == null || it.at >= windowStart) }
        val links = projects.links(projectId)
        val decisions = db.decisions().forProject(projectId)
        val inWindowDecisions = decisions.filter { windowStart == null || it.decidedAt >= windowStart || it.updatedAt >= windowStart }
        val linkedIdeas = links.filter { it.refKind == "Idea" }.mapNotNull { db.ideas().idea(it.refId) }
        val ideas = (db.ideas().forProject(projectId) + linkedIdeas).distinctBy { it.id }
            .filter { !it.deleted && it.mergedInto == null }
        val ideaIds = ideas.map { it.id }
        val ideaEntries = if (ideaIds.isEmpty()) emptyList()
            else db.ideas().entriesFor(ideaIds).filter { windowStart == null || it.at >= windowStart }
        val eventIds = links.filter { it.refKind == "Event" }.map { it.refId }
        val linkedItems = links.filter { it.refKind == "Item" }
            .mapNotNull { memory.itemIncludingDeleted(it.refId) }.filter { !it.deleted && it.mergedInto == null }
        val viaEvents = if (eventIds.isEmpty()) emptyList() else memory.itemsForEvents(eventIds)
        val items = (linkedItems + viaEvents).distinctBy { it.id }
            .filter { windowStart == null || it.createdAt >= windowStart || it.status != "Done" && it.status != "Ignored" }
        val fups = db.followups().observeOpen().first() + db.followups().observeClosed().first()
        val projectFollowups = app.resound.projects.ProjectActivity.attributedFollowups(fups,
            items.map { it.id }.toSet(), ideaIds.toSet(), decisions.map { it.id }.toSet())
        val sources = mutableListOf<String>()
        val body = buildString {
            appendLine("项目 #${project.id}《${project.name}》（${if (project.status == "Archived") "已归档" else "进行中"}）")
            if (project.note.isNotBlank()) appendLine("说明：${project.note}")
            appendLine("数据范围：${if (windowStart != null) "${stamp(windowStart)} 至今" else "全部记录"}")

            if (marks.isNotEmpty()) {
                appendLine("--- 项目记忆 ---")
                marks.take(40).forEach { m ->
                    sources += "{\"kind\":\"Mark\",\"id\":${m.id}}"
                    appendLine("[${stamp(m.at)}] 【${app.resound.projects.ProjectMarkKind.of(m.kind).label}】${m.title}" +
                        (if (m.content.isNotBlank()) "：${m.content}" else "") +
                        (m.reason?.let { "（原因：$it）" } ?: ""))
                }
            }
            if (decisions.isNotEmpty()) {
                appendLine("--- 决定（含历史演变） ---")
                decisions.take(30).forEach { d ->
                    if (d in inWindowDecisions || d.status == "Active") {
                        sources += "{\"kind\":\"Decision\",\"id\":${d.id}}"
                        appendLine("[${stamp(d.decidedAt)}] 【${app.resound.decision.DecisionStatus.of(d.status).label}/${app.resound.decision.DecisionConfidence.of(d.confidence).label}】${d.title}" +
                            (if (d.detail.isNotBlank()) "：${d.detail}" else "") +
                            (d.supersededBy?.let { "（已被决定 #$it 替代）" } ?: "") +
                            (d.rejectedReason?.let { "（否决原因：$it）" } ?: "") +
                            (d.sourceText?.let { "\n  原话：“${it.take(120)}”" } ?: ""))
                    }
                }
            }
            if (ideas.isNotEmpty()) {
                appendLine("--- 关联灵感及发展 ---")
                ideas.take(20).forEach { i ->
                    sources += "{\"kind\":\"Idea\",\"id\":${i.id}}"
                    appendLine("[${stamp(i.updatedAt)}] ${i.title.ifBlank { i.content.take(40) }}（${app.resound.inbox.IdeaStatus.of(i.status).label}）")
                }
                ideaEntries.filter { it.kind != "Seed" }.take(20).forEach { e ->
                    sources += "{\"kind\":\"IdeaEntry\",\"id\":${e.id}}"
                    appendLine("  [${stamp(e.at)}] 轨迹·${e.kind}：${e.text.take(120)}")
                }
            }
            if (links.isNotEmpty()) {
                appendLine("--- 关联记录 ---")
                links.take(30).forEach { l ->
                    sources += "{\"kind\":\"${l.refKind}\",\"id\":${l.refId}}"
                    appendLine("· ${app.resound.projects.ProjectRefKind.of(l.refKind)?.label ?: l.refKind} #${l.refId}" +
                        (l.note?.let { "（$it）" } ?: ""))
                    appendLine("  ${resolveRef(l.refKind, l.refId).take(300)}")
                }
            }
            if (items.isNotEmpty()) {
                appendLine("--- 待办与约定 ---")
                items.take(40).forEach { i ->
                    sources += "{\"kind\":\"Item\",\"id\":${i.id}}"
                    appendLine("· [${i.status}] ${i.content}${i.dueText?.let { "（$it）" } ?: ""}${i.doneAt?.let { "（完成于 ${stamp(it)}）" } ?: ""}")
                }
            }
            if (projectFollowups.isNotEmpty()) {
                appendLine("--- 跟进与承诺 ---")
                projectFollowups.take(20).forEach { f ->
                    sources += "{\"kind\":\"Followup\",\"id\":${f.id}}"
                    appendLine("· [${f.status}] ${f.title}${f.remindCount.let { if (it > 0) "（提醒过 $it 次仍未闭环）" else "" }}")
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
            AnalysisTarget.Project -> projectMaterial(targetId, null)
            AnalysisTarget.Day -> dayMaterial(targetId)
        }
        if (m.content.length <= cap) m else m.copy(content = m.content.take(cap) + "\n（材料过长已截断）")
    }

    private suspend fun material(target: AnalysisTarget, targetId: Long, windowStart: Long?): AnalysisMaterial =
        if (target == AnalysisTarget.Project && windowStart != null) withContext(io) {
            val m = projectMaterial(targetId, windowStart)
            if (m.content.length <= 60_000) m else m.copy(content = m.content.take(60_000) + "\n（材料过长已截断）")
        } else material(target, targetId)

    // ---------- 运行 ----------

    /**
     * 创建运行并入队执行；未配置模型时运行落为 Failed（错误提示“未配置模型服务”），材料与提示词仍落库可导出。
     * 防重复付费：同目标同类型有在途运行（Pending/Running）时直接返回那条，不再发起新调用。
     * windowStart 仅对项目生效：阶段报告的数据窗口起点，并写入模板名留痕（“阶段报告·近7天”）。
     */
    suspend fun start(target: AnalysisTarget, targetId: Long, type: AnalysisType,
        templateId: Long? = null, projectId: Long? = null, windowStart: Long? = null): Long = withContext(io) {
        dao.activeRun(target.name, targetId, type.name)?.let { return@withContext it.id }
        val template = (templateId?.let { dao.template(it) } ?: dao.templatesOf(type.name).firstOrNull())
        val material = material(target, targetId, windowStart)
        val userPrompt = template?.let { AnalysisContract.render(it.userTemplate, material.content, material.context) }
            ?: material.content
        val windowLabel = windowStart?.let { "·自${stamp(it)}" } ?: ""
        val run = dao.insertRun(
            AnalysisRunRow(
                targetKind = target.name, targetId = targetId, type = type.name,
                templateId = template?.id, templateName = (template?.name ?: "默认") + windowLabel,
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
            if (preset.id != OFFICIAL_PROVIDER_ID && key.isBlank() && !canUseWithoutKey(preset) && !hasBackup(preset)) {
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
