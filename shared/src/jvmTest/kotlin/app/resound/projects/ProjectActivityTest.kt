package app.resound.projects

import app.resound.data.MemoryItemEntity
import app.resound.decision.DecisionRow
import app.resound.followup.FollowupRow
import app.resound.inbox.IdeaEntryRow
import app.resound.inbox.IdeaRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 项目统一时间线/行动聚合：排序、跨来源去重、跟进合并、分桶。 */
class ProjectActivityTest {

    private fun link(kind: ProjectRefKind, refId: Long, at: Long = 0) =
        ProjectLinkRow(id = refId + 1000, projectId = 1, refKind = kind.name, refId = refId, createdAt = at)
    private fun item(id: Long, status: String = "Accepted", at: Long, eventId: Long? = null, dueAt: Long? = null) =
        MemoryItemEntity(id = id, eventId = eventId, category = "待办", kind = null, content = "待办$id",
            dueText = null, dueAt = dueAt, who = null, lineId = null, status = status, createdAt = at)

    @Test fun timelineSortsDescAndDedupsSameFact() {
        // 同一事实两个来源：Item 链接 + 该条目本身（挂在已关联会议上）→ 只出现一次，带来源合并
        val input = ProjectActivity.Input(
            refs = listOf(
                ResolvedRef(link(ProjectRefKind.Event, 5), "周例会", "纪要", at = 3_000L, status = "会议"),
                ResolvedRef(link(ProjectRefKind.Item, 9), "跟进方案 A", "待办", at = 2_000L, status = "Accepted"),
            ),
            items = listOf(item(9, "Accepted", at = 2_000L, eventId = 5)),
            marks = listOf(ProjectMarkRow(id = 1, projectId = 1, kind = "Status", title = "启动", at = 1_000L)),
        )
        val t = ProjectActivity.timeline(input)
        // 排序：事件(3000) > 待办(2000) > 记忆(1000)
        assertEquals(listOf("event:5", "item:9", "mark:1"), t.map { it.key })
        // 待办只有一个条目，两个来源
        val itemEntry = t.single { it.key == "item:9" }
        assertEquals(2, itemEntry.sources.size)
        assertEquals("进行中", itemEntry.status)
    }

    @Test fun timelineUsesRealOccurredTimeNotLinkTime() {
        // 会议是周一开的、周五才挂进项目：条目按会议时间排，不按关联写入时间
        val input = ProjectActivity.Input(
            refs = listOf(ResolvedRef(link(ProjectRefKind.Event, 5, at = 9_999L), "周例会", "", at = 1_000L)),
            marks = listOf(ProjectMarkRow(id = 1, projectId = 1, kind = "Note", title = "今天记的", at = 5_000L)),
        )
        val t = ProjectActivity.timeline(input)
        assertEquals("mark:1", t[0].key)
        assertEquals("event:5", t[1].key)
    }

    @Test fun followupMergesIntoReferencedItem() {
        val input = ProjectActivity.Input(
            items = listOf(item(9, "Accepted", at = 2_000L)),
            followups = listOf(FollowupRow(id = 3, kind = "Commitment", refKind = "item", refId = 9,
                title = "跟进方案 A", status = "Open", createdAt = 2_500L, updatedAt = 2_500L)),
        )
        val t = ProjectActivity.timeline(input)
        assertEquals(1, t.size)
        assertEquals("item:9", t[0].key)
        assertTrue(t[0].detail.contains("承诺"))
        assertTrue(t[0].sources.any { it.kind == "followup" && it.id == 3L })
    }

    @Test fun decisionAndIdeaEntryKeepRealTimes() {
        val input = ProjectActivity.Input(
            decisions = listOf(DecisionRow(id = 7, title = "用方案 B", detail = "", projectId = 1,
                status = "Active", decidedAt = 4_000L, createdAt = 4_000L, updatedAt = 4_000L)),
            ideas = listOf(IdeaRow(id = 4, title = "降噪耳机", content = "做个降噪耳机", createdAt = 1_000L, updatedAt = 1_000L)),
            ideaEntries = listOf(
                IdeaEntryRow(id = 1, ideaId = 4, kind = "Seed", text = "做个降噪耳机", origin = "User", at = 1_000L),
                IdeaEntryRow(id = 2, ideaId = 4, kind = "Note", text = "竞品是 Bose", origin = "User", at = 2_000L),
            ),
        )
        val t = ProjectActivity.timeline(input)
        // Seed 不重复成行；决定排在最前
        assertEquals(listOf("decision:7", "entry:2", "idea:4"), t.map { it.key })
        assertEquals("生效中", t[0].status)
    }

    @Test fun attributedFollowupsOnlyCountsProjectRefs() {
        val fups = listOf(
            FollowupRow(id = 1, kind = "Commitment", refKind = "item", refId = 9, title = "a", createdAt = 0, updatedAt = 0),
            FollowupRow(id = 2, kind = "Todo", refKind = "item", refId = 99, title = "别的项目的", createdAt = 0, updatedAt = 0),
            FollowupRow(id = 3, kind = "Commitment", refKind = "line", refId = 5, title = "原话上的", createdAt = 0, updatedAt = 0),
        )
        val got = ProjectActivity.attributedFollowups(fups, itemIds = setOf(9L), ideaIds = emptySet(), decisionIds = emptySet())
        assertEquals(listOf(1L), got.map { it.id })
    }

    @Test fun actionsBucketsAndMergesFollowup() {
        val input = ProjectActivity.Input(
            items = listOf(
                item(1, "Pending", at = 100L),
                item(2, "Accepted", at = 200L, dueAt = 50L), // 已超期
                item(3, "Done", at = 300L),
            ),
            followups = listOf(
                FollowupRow(id = 5, kind = "Commitment", refKind = "item", refId = 2, title = "跟进方案", status = "Open",
                    createdAt = 250L, updatedAt = 250L, remindCount = 4),
            ),
        )
        val a = ProjectActivity.actions(input, now = 400L)
        assertEquals(listOf("item:1"), a.pending.map { it.key })
        assertEquals(listOf("item:3"), a.done.map { it.key })
        // 超期 + 高提醒次数 → 卡住；承诺并到待办一行
        assertEquals(listOf("item:2"), a.stalled.map { it.key })
        assertEquals("Commitment", a.open.single().followupKind)
    }
}
