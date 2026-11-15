package app.resound.search

import org.junit.Assert.*
import org.junit.Test

class RecordSearch34Test {
    @Test fun multipleWordsMustAllMatchAndUseIndex() {
        val (sql, args) = RecordSearchRules.lineQuery(RecordSearchRules.words("  合同  张三 合同 "))
        assertEquals(2, args.size)
        assertEquals(2, Regex("lines_fts MATCH \\?").findAll(sql).count())
        assertTrue(sql.contains(" AND "))
        assertEquals("\"合同\"", args[0])
    }
    @Test fun singleHanCharFallsBackToLike() {
        val (sql, args) = RecordSearchRules.lineQuery(listOf("会"))
        assertTrue(sql.contains("text LIKE ? ESCAPE"))
        assertEquals("%会%", args.single())
    }
    @Test fun postFilterDropsNonContiguousBigramHits() {
        // “合同签”切成 合同、同签；“合同后来同签”两词都在但原文不连续，不算命中
        assertFalse(RecordSearchRules.contains("合同后来同签", listOf("合同签")))
        assertTrue(RecordSearchRules.contains("明天合同签字", listOf("合同签")))
        assertTrue(RecordSearchRules.contains("Meeting at ten", listOf("meeting")))
    }
    @Test fun snippetCentersOnKeyword() {
        val text = "开头".repeat(60) + "关键内容" + "结尾".repeat(60)
        val s = RecordSearchRules.snippet(text, listOf("关键"))
        assertTrue(s.startsWith("…") && s.endsWith("…") && s.contains("关键内容"))
    }
    @Test fun mergeSortsNewestFirstAndCaps() {
        val lines = (1..90).map { SearchHit(SearchHit.Kind.Line, it.toLong(), it.toLong(), "l") }
        val events = (1..30).map { SearchHit(SearchHit.Kind.Event, it.toLong(), it * 10L, "e") }
        val merged = RecordSearchRules.merge(lines, events)
        assertEquals(RecordSearchRules.LIMIT, merged.size)
        assertEquals(300L, merged.first().at)
    }
}
