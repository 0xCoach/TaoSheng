package app.resound.search

import org.junit.Assert.*
import org.junit.Test

class RecordSearch34Test {
    @Test fun wordsAreTrimmedAndDeduplicated() {
        assertEquals(listOf("合同", "张三"), RecordSearchRules.words("  合同  张三 合同 "))
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
}
