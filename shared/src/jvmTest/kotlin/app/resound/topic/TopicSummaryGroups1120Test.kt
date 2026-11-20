package app.resound.topic

import app.resound.topic.core.TopicBlocks
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class TopicSummaryGroups1120Test {
    /** 模拟一层合并：每组变成一份约 1500 字的结果，统计到收敛为止共调几次模型。 */
    private fun calls(parts: List<String>): Int {
        var level = parts; var n = 0
        while (level.size > 6 && level.sumOf { it.length } > TopicBlocks.SUMMARY_CHARS) {
            level = TopicBlocks.summaryGroups(level) { it }.map { if (it.size == 1) it.single() else { n++; "x".repeat(1500) } }
        }
        return n + 1
    }

    @Test fun `两小时培训几十张卡按字数装满分组，调用次数大幅减少`() {
        val cards = List(65) { "y".repeat(450) } // 13 块 × 5 张，约 2.9 万字
        val groups = TopicBlocks.summaryGroups(cards) { it }
        assertTrue(groups.all { g -> g.sumOf { it.length } <= TopicBlocks.SUMMARY_CHARS })
        assertEquals(65, groups.sumOf { it.size })
        assertEquals(3, groups.size)
        assertEquals(4, calls(cards)) // 旧算法固定 6 份一组：11 + 2 + 1 = 14 次
    }

    @Test fun `短内容一组；超长单份也能收敛`() {
        assertEquals(1, TopicBlocks.summaryGroups(List(5) { "a".repeat(100) }) { it }.size)
        val huge = List(9) { "b".repeat(13_000) }
        assertTrue(TopicBlocks.summaryGroups(huge) { it }.dropLast(1).all { it.size >= 2 })
        assertTrue(calls(huge) < 10)
    }
}
