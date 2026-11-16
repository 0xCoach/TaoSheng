package app.resound.search

import org.junit.Assert.*
import org.junit.Test

class ExpandedSearch194Test {
    @Test fun singleHanAndSpecialCharactersAreBoundLiterally() {
        for (source in listOf(SearchSource.Line, SearchSource.Review, SearchSource.AskMessage)) {
            val q = ExpandedSearchRules.query(source, listOf("会%_'\\"), ExpandedSearchRequest(""), null)
            assertTrue(q.sql.contains("LIKE ? ESCAPE"))
            assertEquals(listOf("%会\\%\\_'\\\\%"), q.args)
            assertFalse(q.sql.contains("会"))
        }
    }
    @Test fun reviewOnlySearchesVisibleFieldsIncludingEscapedUnicode() {
        val text = ExpandedSearchRules.reviewText("""[{"title":"标题","lines":["\u5408\u540c"],"eventIds":[9876]}]""")
        assertEquals("标题 合同", text)
        assertFalse(text.contains("9876"))
        assertFalse(text.contains("eventIds"))
    }
    @Test fun otherSourcesWithSameIdAndTimeHaveStablePages() {
        val rows = SearchSource.entries.filter { it.kind == SearchKind.Other }.map {
            ExpandedSearchHit(it, 1, 123, "", "")
        }
        val first = ExpandedSearchRules.page(rows.reversed(), 0, 3)
        val second = ExpandedSearchRules.page(rows, first.nextOffset!!, 3)
        assertEquals(rows, first.hits + second.hits)
        assertNull(second.nextOffset)
    }
}
