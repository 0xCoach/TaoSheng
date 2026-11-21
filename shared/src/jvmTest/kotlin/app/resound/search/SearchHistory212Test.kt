package app.resound.search

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SearchHistory212Test {
    @Test fun trimsDeduplicatesCapsRemovesClearsAndReloads() = runBlocking {
        var stored: String? = null
        val history = SearchHistory({ stored }, { stored = it })
        history.add("  "); assertTrue(history.items.value.isEmpty())
        (1..12).forEach { history.add("词$it") }; history.add(" 词6 ")
        assertEquals(10, history.items.value.size); assertEquals("词6", history.items.value.first())
        assertEquals(1, history.items.value.count { it == "词6" }); assertFalse(history.items.value.contains("词2"))
        history.remove("词6"); assertEquals(9, history.items.value.size)
        assertEquals(history.items.value, SearchHistory({ stored }, {}).items.value)
        history.clear(); assertEquals("[]", stored); assertTrue(history.items.value.isEmpty())
    }
}
