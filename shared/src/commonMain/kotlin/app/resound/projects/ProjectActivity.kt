package app.resound.projects

import app.resound.data.MemoryItemEntity
import app.resound.decision.DecisionRow
import app.resound.followup.FollowupRow
import app.resound.inbox.IdeaEntryRow
import app.resound.inbox.IdeaRow

/** 事实卡上的一处来源：回答“这条结论从哪来”。 */
data class ActivitySource(val kind: String, val id: Long, val label: String)

/** 项目统一时间线的一条事实卡：同一事实跨来源只出现一次，多来源合并展示。 */
data class ActivityEntry(
    val at: Long,
    val key: String,
    val kind: Kind,
    val title: String,
    val detail: String,
    /** 条目当前状态（待确认/进行中/已完成/生效中……），为空表示无状态概念 */
    val status: String? = null,
    val sources: List<ActivitySource> = emptyList(),
) {
    enum class Kind(val label: String) {
        Event("会议/事件"), Decision("决定"), Item("待办"), Idea("灵感"), IdeaEntry("灵感补充"),
        Mark("记忆"), Followup("跟进"), Line("原话"), Segment("录音"), Analysis("分析"), Task("任务");
    }
}

/** 一条 project_link 解析成真实记录后的样子；at 是事实实际发生时间，不是关联写入时间。 */
data class ResolvedRef(
    val link: ProjectLinkRow,
    val title: String,
    val detail: String = "",
    val at: Long,
    val status: String? = null,
)

/** 行动与承诺区的一行：待办与跟进同指一件事时合并。 */
data class ActionRow(
    val key: String,
    val title: String,
    val detail: String,
    val at: Long,
    val dueAt: Long? = null,
    val doneAt: Long? = null,
    /** Pending / Accepted / Done（沿用 memory_items 状态口径，与待办页一致） */
    val status: String,
    /** 合并上来的跟进类别（Commitment 承诺等）；无跟进为 null */
    val followupKind: String? = null,
    /** 合并上来的跟进状态；Open 表示承诺还没闭环 */
    val followupStatus: String? = null,
    val remindCount: Int = 0,
    val overdue: Boolean = false,
    val sources: List<ActivitySource> = emptyList(),
)

data class ProjectActions(
    /** 待确认（Pending，还没被接受） */
    val pending: List<ActionRow>,
    /** 进行中 + 未闭环承诺 */
    val open: List<ActionRow>,
    /** 已完成 */
    val done: List<ActionRow>,
    /** 卡住：提醒多次未闭环或已超期未完成（open 的子集，单独标出） */
    val stalled: List<ActionRow>,
)

/**
 * 项目聚合纯规则：时间线合流去重、行动分桶、跟进归属。
 * 数据全部由调用方按 projectId 取好传入——隔离在取数层完成，这里只负责合并。
 */
object ProjectActivity {

    data class Input(
        val marks: List<ProjectMarkRow> = emptyList(),
        val refs: List<ResolvedRef> = emptyList(),
        val decisions: List<DecisionRow> = emptyList(),
        val ideas: List<IdeaRow> = emptyList(),
        val ideaEntries: List<IdeaEntryRow> = emptyList(),
        /** 归属本项目的提取项：直接挂 Item 链接的 + 挂在已关联事件上的（调用方合并） */
        val items: List<MemoryItemEntity> = emptyList(),
        /** 归属本项目的跟进（attributedFollowups 过滤后的） */
        val followups: List<FollowupRow> = emptyList(),
    )

    // ---------- 归属 ----------

    /** 跟进是否属于本项目：其 ref 落在项目的事实集（条目/灵感/决定）里。line/capture 无项目归属，不挂。 */
    fun attributedFollowups(
        followups: List<FollowupRow>,
        itemIds: Set<Long>, ideaIds: Set<Long>, decisionIds: Set<Long>,
    ): List<FollowupRow> = followups.filter { f ->
        !f.deleted && f.refId != null && when (f.refKind) {
            "item" -> f.refId in itemIds
            "idea" -> f.refId in ideaIds
            "decision" -> f.refId in decisionIds
            else -> false
        }
    }

    // ---------- 时间线 ----------

    private class Acc(val entry: ActivityEntry)

    /** 统一时间线：全部来源按事实键合并，按实际发生时间倒序。before 为分页游标（上一页最末条的 at）。 */
    fun timeline(input: Input, limit: Int = 60, before: Long = Long.MAX_VALUE): List<ActivityEntry> {
        val acc = LinkedHashMap<String, Acc>()

        fun add(key: String, at: Long, kind: ActivityEntry.Kind, title: String, detail: String,
            status: String? = null, source: ActivitySource? = null) {
            val old = acc[key]
            if (old == null) {
                acc[key] = Acc(ActivityEntry(at, key, kind, title, detail, status, listOfNotNull(source)))
            } else {
                val e = old.entry
                // 同一事实再出现：保留更早（真实）的发生时间与更完整的标题，来源累加
                val merged = e.copy(
                    at = minOf(e.at, at),
                    title = if (e.title.isBlank() || title.length > e.title.length) title.ifBlank { e.title } else e.title,
                    detail = if (e.detail.isBlank()) detail else if (detail.isBlank() || detail in e.detail) e.detail else "${e.detail}；$detail",
                    status = status ?: e.status,
                    sources = e.sources + listOfNotNull(source).filter { s -> e.sources.none { it.kind == s.kind && it.id == s.id } },
                )
                acc[key] = Acc(merged)
            }
        }

        // 关联记录解析结果：会议/待办/灵感/原话等，at 是事实发生时间
        input.refs.forEach { r ->
            val refKind = ProjectRefKind.of(r.link.refKind) ?: return@forEach
            val kind = when (refKind) {
                ProjectRefKind.Event -> ActivityEntry.Kind.Event
                ProjectRefKind.Item -> ActivityEntry.Kind.Item
                ProjectRefKind.Idea -> ActivityEntry.Kind.Idea
                ProjectRefKind.Line -> ActivityEntry.Kind.Line
                ProjectRefKind.Segment -> ActivityEntry.Kind.Segment
                ProjectRefKind.Analysis -> ActivityEntry.Kind.Analysis
                ProjectRefKind.Task -> ActivityEntry.Kind.Task
            }
            add("${refKind.name.lowercase()}:${r.link.refId}", r.at, kind, r.title, r.detail, r.status,
                ActivitySource("link", r.link.id, "关联记录"))
        }

        // 决定：decidedAt 是决定做出的时刻
        input.decisions.forEach { d ->
            val status = app.resound.decision.DecisionStatus.of(d.status).label
            add("decision:${d.id}", d.decidedAt, ActivityEntry.Kind.Decision, d.title,
                listOfNotNull(d.detail.ifBlank { null }, d.rejectedReason?.let { "否决原因：$it" },
                    d.supersededBy?.let { "已被决定 #$it 替代" }).joinToString(" · "),
                status, ActivitySource("decision", d.id, "决定"))
        }

        // 灵感：projectId 归属的与 Idea 链接的同键合并
        input.ideas.forEach { i ->
            add("idea:${i.id}", i.createdAt, ActivityEntry.Kind.Idea, i.title.ifBlank { i.content.take(30) },
                i.content.take(200), app.resound.inbox.IdeaStatus.of(i.status).label,
                ActivitySource("idea", i.id, "灵感"))
        }

        // 灵感轨迹：Seed 与灵感创建重复，跳过；Todo 转化的并到那条待办上
        val ideaById = input.ideas.associateBy { it.id }
        input.ideaEntries.forEach { e ->
            if (e.kind == "Seed") return@forEach
            val idea = ideaById[e.ideaId]
            if (e.kind == "Todo" && idea?.convertedKind == "Todo" && idea.convertedId != null &&
                input.items.any { it.id == idea.convertedId }) {
                // 转化事件不单独成行：作为那条待办的来源之一
                val key = "item:${idea.convertedId}"
                val old = acc[key]
                if (old != null) acc[key] = Acc(old.entry.copy(sources = old.entry.sources +
                    ActivitySource("idea_entry", e.id, "由灵感转化").let { s ->
                        if (old.entry.sources.none { it.kind == s.kind && it.id == s.id }) listOf(s) else emptyList()
                    }))
                return@forEach
            }
            val action = when (e.kind) {
                "Note" -> "补充"; "Line" -> "原话补充"; "Merged" -> "并入相关灵感"
                "Status" -> "状态更新"; "Todo" -> "转为待办"; "Project" -> "转为项目"; else -> e.kind
            }
            add("entry:${e.id}", e.at, ActivityEntry.Kind.IdeaEntry,
                "${idea?.title ?: "灵感 #$e.ideaId"} · $action", e.text, null,
                ActivitySource("idea_entry", e.id, "灵感轨迹"))
        }

        // 项目归属的提取项（直接挂的 + 已关联会议上的）
        input.items.forEach { it ->
            val status = when (it.status) {
                "Pending" -> "待确认"; "Accepted" -> "进行中"; "Done" -> "已完成"; "Ignored" -> "已忽略"; else -> it.status
            }
            add("item:${it.id}", it.recordedAt ?: it.createdAt, ActivityEntry.Kind.Item, it.content,
                listOfNotNull(it.category, it.dueText?.let { d -> "期限：$d" }, it.who?.let { w -> "谁：$w" }).joinToString(" · "),
                status, ActivitySource("item", it.id, "提取项"))
        }

        // 跟进：ref 落在已有事实上则并进去（承诺只是这件事的一个视角），否则独立成行
        input.followups.forEach { f ->
            val refKey = when (f.refKind) {
                "item" -> "item:${f.refId}"; "idea" -> "idea:${f.refId}"; "decision" -> "decision:${f.refId}"; else -> null
            }
            val fKind = app.resound.followup.FollowupKind.entries.firstOrNull { it.name == f.kind }?.label ?: f.kind
            if (refKey != null && acc.containsKey(refKey)) {
                val old = acc[refKey]!!.entry
                acc[refKey] = Acc(old.copy(
                    detail = (listOf(old.detail) + "$fKind（${app.resound.followup.FollowupStatus.of(f.status).label}）：${f.title}")
                        .filter { it.isNotBlank() }.joinToString("；"),
                    sources = old.sources + ActivitySource("followup", f.id, fKind)
                        .let { s -> if (old.sources.none { it.kind == s.kind && it.id == s.id }) listOf(s) else emptyList() },
                ))
            } else {
                add("followup:${f.id}", f.createdAt, ActivityEntry.Kind.Followup, f.title,
                    listOfNotNull(fKind, f.detail.ifBlank { null }, f.sourceText?.let { "“$it”" }).joinToString(" · "),
                    app.resound.followup.FollowupStatus.of(f.status).label,
                    ActivitySource("followup", f.id, fKind))
            }
        }

        // 人工记忆：带 ref 的并到那条事实上，独立的自成一行
        input.marks.forEach { m ->
            if (m.deleted) return@forEach
            val refKey = m.refId?.let { "${m.refKind?.lowercase()}:$it" }
            val label = ProjectMarkKind.of(m.kind).label
            val detail = listOfNotNull(m.content.ifBlank { null }, m.reason?.let { "原因：$it" }).joinToString(" · ")
            if (refKey != null && acc.containsKey(refKey)) {
                val old = acc[refKey]!!.entry
                acc[refKey] = Acc(old.copy(
                    detail = (listOf(old.detail) + "【$label】${m.title}" + if (detail.isNotBlank()) "：$detail" else "")
                        .filter { it.isNotBlank() }.joinToString("；"),
                    sources = old.sources + ActivitySource("mark", m.id, label)
                        .let { s -> if (old.sources.none { it.kind == s.kind && it.id == s.id }) listOf(s) else emptyList() },
                ))
            } else {
                add("mark:${m.id}", m.at, ActivityEntry.Kind.Mark, "【$label】${m.title}", detail, null,
                    ActivitySource("mark", m.id, label))
            }
        }

        return acc.values.map { it.entry }
            .filter { it.at < before }
            .sortedWith(compareByDescending<ActivityEntry> { it.at }.thenBy { it.key })
            .take(limit)
    }

    // ---------- 行动与承诺 ----------

    /** 项目行动全貌：待确认 / 进行中+承诺 / 已完成 / 卡住。一个事实只占一行，跟进并到其 ref 上。 */
    fun actions(input: Input, now: Long): ProjectActions {
        val rows = LinkedHashMap<String, ActionRow>()
        fun key(refKind: String?, refId: Long?): String? = when (refKind) {
            "item" -> "item:$refId"; "idea" -> "idea:$refId"; "decision" -> "decision:$refId"; else -> null
        }

        input.items.forEach { it ->
            if (it.status == "Ignored") return@forEach
            rows["item:${it.id}"] = ActionRow(
                key = "item:${it.id}", title = it.content,
                detail = listOfNotNull(it.category, it.dueText, it.who?.let { w -> "谁：$w" }).joinToString(" · "),
                at = it.recordedAt ?: it.createdAt, dueAt = it.dueAt, doneAt = it.doneAt, status = it.status,
                overdue = it.status != "Done" && it.dueAt != null && it.dueAt < now,
                sources = listOf(ActivitySource("item", it.id, it.category)))
        }
        // 直接挂上的决定/灵感也算行动面的一员吗？——不算：决定是决策区的事，灵感是自己的区。
        input.followups.forEach { f ->
            if (f.status == "Dismissed") return@forEach
            val fKind = app.resound.followup.FollowupKind.entries.firstOrNull { it.name == f.kind }?.label ?: f.kind
            val fStatus = app.resound.followup.FollowupStatus.of(f.status)
            val k = key(f.refKind, f.refId)
            val existing = k?.let { rows[it] }
            if (existing != null) {
                rows[k!!] = existing.copy(
                    followupKind = f.kind, followupStatus = f.status, remindCount = maxOf(existing.remindCount, f.remindCount),
                    detail = (listOf(existing.detail) + "$fKind：${f.title}").filter { it.isNotBlank() }.joinToString(" · "),
                    sources = existing.sources + ActivitySource("followup", f.id, fKind),
                )
            } else {
                rows["followup:${f.id}"] = ActionRow(
                    key = "followup:${f.id}", title = f.title,
                    detail = listOfNotNull(fKind, f.detail.ifBlank { null }).joinToString(" · "),
                    at = f.createdAt, doneAt = f.resolvedAt,
                    status = if (fStatus == app.resound.followup.FollowupStatus.Done) "Done" else "Accepted",
                    followupKind = f.kind, followupStatus = f.status, remindCount = f.remindCount,
                    sources = listOf(ActivitySource("followup", f.id, fKind)))
            }
        }

        val all = rows.values.sortedWith(compareBy<ActionRow> { it.status != "Pending" }.thenBy { it.dueAt ?: Long.MAX_VALUE })
        val pending = all.filter { it.status == "Pending" }
        val done = all.filter { it.status == "Done" }.sortedByDescending { it.doneAt ?: it.at }
        val open = all.filter { it.status == "Accepted" }
        val stalled = open.filter { it.overdue || it.remindCount >= 3 }
        return ProjectActions(pending, open, done, stalled)
    }
}
