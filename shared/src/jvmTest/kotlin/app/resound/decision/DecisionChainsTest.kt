package app.resound.decision

import org.junit.Assert.assertEquals
import org.junit.Test

/** 决策演变链：同主题并组、supersededBy 指针串链、冲突只标不合。 */
class DecisionChainsTest {

    private fun d(id: Long, status: String, topicKey: String, decidedAt: Long, supersededBy: Long? = null) =
        DecisionRow(id = id, title = "决定$id", detail = "", status = status, topicKey = topicKey,
            supersededBy = supersededBy, decidedAt = decidedAt, createdAt = decidedAt, updatedAt = decidedAt)

    @Test fun supersedesChainGroupsOldAndNew() {
        // v1 被 v2 替代：同一链，v2 是 current，v1 进 history
        val chains = DecisionChains.build(listOf(
            d(1, "Superseded", "k1", 1000L, supersededBy = 2),
            d(2, "Active", "k1", 2000L),
        ))
        assertEquals(1, chains.size)
        assertEquals(2L, chains[0].current.id)
        assertEquals(listOf(1L), chains[0].history.map { it.id })
    }

    @Test fun pointerLinksAcrossDifferentTopicKeys() {
        // 措辞变了导致 key 漂移：靠 supersededBy 指针仍然串成一条链
        val chains = DecisionChains.build(listOf(
            d(1, "Superseded", "keyA", 1000L, supersededBy = 2),
            d(2, "Active", "keyB", 2000L),
        ))
        assertEquals(1, chains.size)
        assertEquals(2L, chains[0].current.id)
    }

    @Test fun rejectedStaysInHistory() {
        // 被否决的方案不消失：留在链的历史里
        val chains = DecisionChains.build(listOf(
            d(1, "Rejected", "k1", 1000L),
            d(2, "Active", "k1", 2000L),
        ))
        assertEquals(1, chains.size)
        assertEquals(listOf(1L), chains[0].history.map { it.id })
    }

    @Test fun multipleActiveSameTopicAreConflictsNotMerged() {
        // 同主题两条生效：数据层不并掉，各成链首，conflicts() 报出来给用户确认
        val ds = listOf(d(1, "Active", "k1", 1000L), d(2, "Active", "k1", 2000L))
        assertEquals(1, DecisionChains.conflicts(ds).size)
        val chains = DecisionChains.build(ds)
        assertEquals(1, chains.size) // 并组但两条都 Active → current 取较新者，另一条进 history 仍可见
        assertEquals(2L, chains[0].current.id)
        assertEquals("Active", chains[0].history[0].status)
    }
}
