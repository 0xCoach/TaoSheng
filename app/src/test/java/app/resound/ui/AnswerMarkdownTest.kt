package app.resound.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import app.resound.ui.ask.AnswerMarkdown
import app.resound.ui.ask.MdBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerMarkdownTest {
    @Test fun blocksSplitHeadingsListsAndParagraphs() {
        val b = AnswerMarkdown.blocks("## 结论\n你答应了**周五**交报告[^1]。\n\n- 第一条\n  - 子项\n2. 第二步\n3.5 小时不是列表\n> 引用\n---")
        assertEquals(MdBlock.Heading(2, "结论"), b[0])
        assertEquals(MdBlock.Paragraph("你答应了**周五**交报告[^1]。"), b[1])
        assertEquals(MdBlock.Item("•", "第一条", 0), b[2])
        assertEquals(MdBlock.Item("•", "子项", 1), b[3])
        assertEquals(MdBlock.Item("2.", "第二步", 0), b[4])
        assertEquals(MdBlock.Paragraph("3.5 小时不是列表"), b[5])
        assertEquals(MdBlock.Quote("引用"), b[6])
        assertEquals(MdBlock.Rule, b[7])
    }

    @Test fun inlineBoldCitationAndUnclosedMarkersDuringStreaming() {
        val s = AnswerMarkdown.inline("你答应了**周五**交报告[^1]，还有**没写完", Color.Red, Color.Gray)
        assertEquals("你答应了周五交报告1，还有没写完", s.text)
        val bold = s.spanStyles.first { it.item.fontWeight == FontWeight.SemiBold }
        assertEquals("周五", s.text.substring(bold.start, bold.end))
        assertTrue(AnswerMarkdown.inline("末尾半截[^", Color.Red, Color.Gray).text == "末尾半截")
    }
}
