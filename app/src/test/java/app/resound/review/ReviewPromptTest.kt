package app.resound.review

import app.resound.digest.LlmException
import org.junit.Assert.*
import org.junit.Test

class ReviewPromptTest {
    private val valid = """{"brief":"今天记录了方案讨论。","sections":[
        {"title":"发生了什么","lines":[{"text":"讨论方案。","eventId":12}]},
        {"title":"一句话","lines":[{"text":"今天讨论了方案。","eventId":12}]},
        {"title":"灵感","lines":[]}] }"""

    @Test fun normalSortsDropsEmptyAndRoundTrips() {
        val (brief, sections) = ReviewPrompt.parse(valid, setOf(12))
        assertEquals("今天记录了方案讨论。", brief)
        assertEquals(listOf("一句话", "发生了什么"), sections.map { it.title })
        assertEquals(listOf(12L), sections.first().eventIds)
        assertEquals(sections, ReviewPrompt.decode(ReviewPrompt.encode(sections)))
    }

    @Test fun invalidIdsAreDroppedAndLimitsEnforced() {
        val text = valid.replace("\"灵感\",\"lines\":[]", "\"灵感\",\"lines\":[{\"text\":\"不存在\",\"eventId\":999},{\"text\":\"小数\",\"eventId\":12.5}]")
            .replace("今天记录了方案讨论。", "字".repeat(70))
        val (brief, sections) = ReviewPrompt.parse(text, setOf(12))
        assertEquals(60, brief.length)
        assertFalse(sections.any { it.title == "灵感" })
    }

    @Test(expected = LlmException::class) fun missingRequiredSectionFails() {
        ReviewPrompt.parse("""{"brief":"简洁版","sections":[{"title":"灵感","lines":[{"text":"想法","eventId":12}]}]}""", setOf(12))
    }
}
