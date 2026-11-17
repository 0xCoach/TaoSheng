@file:OptIn(kotlin.time.ExperimentalTime::class)
package app.resound.platform

import kotlinx.datetime.*
import kotlin.time.Instant
import org.junit.Test
import org.junit.Assert.*

class ZoneHistory200Test {
    @Test fun travelKeepsMidnightBoundaryAndCapsHistory() {
        val a=Instant.parse("2026-11-16T00:00:00+08:00").toEpochMilliseconds()
        val move=a+14*3_600_000L
        val h=ZoneHistory(listOf(ZoneChange(a-1,"Asia/Shanghai"),ZoneChange(move,"Europe/London")),"Europe/London")
        val spans=h.zonesOn(LocalDate(2026,11,16))
        assertEquals(a,spans.first().from); assertEquals(a+86_400_000L,spans.last().until)
        assertEquals(2,spans.size); assertEquals("UTC+8，06:00 起为 UTC+0",h.caption(LocalDate(2026,11,16)))
        assertEquals(500,ZoneHistory((0L..500L).map { ZoneChange(it,"UTC") },"UTC").record(1000,"Asia/Kathmandu").size)
    }
    @Test fun halfQuarterOffsetsAndDstDay() {
        val at=Instant.parse("2026-03-08T12:00:00Z").toEpochMilliseconds()
        assertEquals("UTC+5:30",ZoneHistory(emptyList(),"Asia/Kolkata").label(at))
        assertEquals("UTC+5:45",ZoneHistory(emptyList(),"Asia/Kathmandu").label(at))
        val h=ZoneHistory(emptyList(),"America/New_York")
        val spans=h.zonesOn(LocalDate(2026,3,8))
        assertEquals(23*3_600_000L,spans.last().until-spans.first().from)
        assertEquals("UTC-5，03:00 起为 UTC-4",h.caption(LocalDate(2026,3,8)))
    }
}
