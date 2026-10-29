package app.resound.ui

import app.resound.digest.MemoryEvent
import app.resound.ui.transcript.exportTitle
import app.resound.ui.transcript.safeName
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportTitleTest {
    private val day = LocalDate.of(2026, 10, 29)
    private fun at(h: Int, m: Int = 0) = day.atTime(h, m).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun ev(id: Long, s: Long, e: Long, title: String) = MemoryEvent(id, s, e, "会议", title, "", emptyList(), emptyList(), null, null, null, false, emptyList())

    @Test fun oneEventUsesItsTitleWithSortableDate() =
        assertEquals("2026-10-29 14.05 项目进度讨论", exportTitle(listOf(ev(1, at(14, 5), at(14, 40), "项目进度讨论")), at(14, 6), at(14, 30)))

    @Test fun severalEventsUseLongestAndCount() =
        assertEquals("2026-10-29 15.10 客户需求沟通 等 2 件事", exportTitle(listOf(ev(1, at(15), at(15, 5), "寒暄"), ev(2, at(15, 10), at(15, 50), "客户需求沟通")), at(15), at(15, 50)))

    @Test fun noEventFallsBackToTimeRange() = assertEquals("2026-10-29 18.30-18.45 录音", exportTitle(emptyList(), at(18, 30), at(18, 45)))

    @Test fun unsafeCharactersRemoved() = assertEquals("a b c", safeName("a/b:c"))
}
