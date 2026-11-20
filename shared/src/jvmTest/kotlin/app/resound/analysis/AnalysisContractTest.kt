package app.resound.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisContractTest {
    @Test fun templateMustContainContentSlot() {
        assertTrue(AnalysisContract.templateValid("分析：{{content}}"))
        assertFalse(AnalysisContract.templateValid("没有占位符"))
    }
    @Test fun renderInjectsContentAndPrependsContext() {
        val out = AnalysisContract.render("总结：{{content}}", "材料A", "项目X")
        assertTrue(out.contains("材料A"))
        assertTrue(out.contains("项目X"))
        val withSlot = AnalysisContract.render("背景{{context}}正文{{content}}", "材料", "背景B")
        assertEquals("背景背景B正文材料", withSlot)
    }
    @Test fun builtinTemplatesCoverAllSevenTypes() {
        val templates = AnalysisContract.builtinTemplates(0L)
        assertEquals(AnalysisType.entries.size, templates.size)
        templates.forEach { assertTrue(AnalysisContract.templateValid(it.userTemplate)) }
    }
    @Test fun displayTextUnwrapsModelEnvelopes() {
        // 报告形
        assertEquals("正文一", AnalysisContract.displayText("""{"report":"正文一"}"""))
        // messages 信封形
        assertEquals("正文二", AnalysisContract.displayText("""{"messages":[{"content":"正文二"}]}"""))
        // 任意嵌套里取第一个字符串叶
        assertEquals("正文三", AnalysisContract.displayText("""{"data":{"content":"正文三"}}"""))
        // 非 JSON 原样返回
        val plain = "就是一段正文"
        assertEquals(plain, AnalysisContract.displayText(plain))
        // 无正文键的 JSON 原样返回
        val meta = """{"a":1,"b":2}"""
        assertEquals(meta, AnalysisContract.displayText(meta))
    }
}
