@file:OptIn(kotlin.time.ExperimentalTime::class)
package app.resound.todo

import app.resound.digest.ItemStatus
import app.resound.digest.MemoryItem
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import org.junit.Assert.*
import org.junit.Test

class TodoAgendaRulesTest {
    private val zone = TimeZone.of("Asia/Shanghai")
    private val today = LocalDate(2026, 11, 19)
    private val now = today.atTime(10, 0).toInstant(zone).toEpochMilliseconds()
    private fun at(d: LocalDate, h: Int = 0, m: Int = 0) = d.atTime(h, m).toInstant(zone).toEpochMilliseconds()
    private fun item(id: Long, due: Long?, timed: Boolean, status: ItemStatus = ItemStatus.Accepted, category: String = "待办") =
        MemoryItem(id, null, category, null, "事 $id", due?.let { if (timed) "2026-11-19 15:00" else "2026-11-19" }, due, null, status, null)

    @Test fun groupsOverdueWindowLaterAndAnytime() {
        val list = listOf(
            item(1, at(LocalDate(2026, 11, 18)), false),          // 昨天的只定日子：过期
            item(2, at(today, 9, 0), true),                        // 今天 9 点已过：过期
            item(3, at(today), false),                             // 今天只定日子：不过期
            item(4, at(LocalDate(2026, 11, 25), 14), true),        // 第 6 天：窗口内
            item(5, at(LocalDate(2026, 11, 27)), false),           // 第 8 天：下周
            item(6, at(LocalDate(2026, 12, 20)), false),           // 12 月
            item(7, null, false))                                  // 随时做
        val pending = listOf(item(8, at(LocalDate(2026, 11, 22), 19), true, ItemStatus.Pending), item(9, at(LocalDate(2026, 11, 1)), false, ItemStatus.Pending))
        val a = TodoAgendaRules.build(list, pending, now, zone)
        assertEquals(listOf(1L, 2L), a.overdue.map { it.id })
        assertEquals(7, a.days.size)
        assertEquals(listOf(3L), a.days[0].items.map { it.id })
        assertEquals(listOf(8L), a.days[3].pending.map { it.id })
        assertEquals(listOf(4L), a.days[6].items.map { it.id })
        assertEquals(listOf("下周", "12 月"), a.later.map { it.title })
        assertEquals("11月26日—12月2日", a.later[0].range)
        assertEquals(listOf(7L), a.anytime.map { it.id })
        assertTrue("过去的待确认不进日程", a.days.none { d -> d.pending.any { it.id == 9L } })
    }

    @Test fun marksCountOverdueAndPending() {
        val m = TodoAgendaRules.marks(listOf(item(1, at(LocalDate(2026, 11, 18)), false), item(2, at(today), false), item(3, at(today, 18), true)),
            listOf(item(4, at(today, 20), true, ItemStatus.Pending)), now, zone)
        assertEquals(TodoAgendaRules.Mark(1, true, false), m[LocalDate(2026, 11, 18)])
        assertEquals(TodoAgendaRules.Mark(2, false, true), m[today])
    }

    @Test fun moveToTodayKeepsFutureClockOtherwiseDateOnly() {
        val keep = TodoAgendaRules.moveToToday(item(1, at(LocalDate(2026, 11, 17), 15), true), now, zone)
        assertEquals(at(today, 15) to true, keep)
        val drop = TodoAgendaRules.moveToToday(item(2, at(LocalDate(2026, 11, 17), 8), true), now, zone)
        assertEquals(at(today) to false, drop)
        assertEquals(at(today) to false, TodoAgendaRules.moveToToday(item(3, at(LocalDate(2026, 11, 10)), false), now, zone))
    }

    @Test fun calendarDraftTimedIsOneHourDateOnlyIsAllDay() {
        val timed = CalendarDraftRules.from(item(1, at(today, 15), true).copy(who = "小林", kind = "我答应别人"), zone)!!
        assertFalse(timed.allDay); assertEquals(3_600_000L, timed.end - timed.begin)
        assertTrue(timed.notes.contains("和谁：小林") && timed.notes.contains("我答应别人"))
        val day = CalendarDraftRules.from(item(2, at(today), false), zone)!!
        assertTrue(day.allDay); assertEquals(at(today), day.begin); assertEquals(at(LocalDate(2026, 11, 20)), day.end)
        assertNull(CalendarDraftRules.from(item(3, null, false), zone))
    }

    @Test fun titlesAndCaptions() {
        assertEquals("今天", TodoAgendaRules.dayTitle(today, today))
        assertEquals("明天", TodoAgendaRules.dayTitle(LocalDate(2026, 11, 20), today))
        assertEquals("22", TodoAgendaRules.dayTitle(LocalDate(2026, 11, 22), today))
        assertEquals("11月22日 周日 · 3 天后", TodoAgendaRules.dayCaption(LocalDate(2026, 11, 22), today))
    }
}
