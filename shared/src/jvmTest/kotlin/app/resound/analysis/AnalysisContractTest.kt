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
}
