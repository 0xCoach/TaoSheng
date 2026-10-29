package app.resound

import app.resound.transcribe.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class Card059Test {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val time = OutputTime(zone, LocalDate.parse("2026-10-29"))
    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @Test fun digestTranscribesPendingBeforeOrganizing() = runBlocking {
        val steps = mutableListOf<String>()
        val pending = 60_000L
        val skip = digestTranscribeSkip(false, true, true,
            EarlyTranscribe.shouldRun(true, pending, 80, false, false), pending)
        runPipelineStages(TranscribeWork.MODE_DIGEST, skip,
            { steps += "转写" }, { assertEquals(listOf("转写"), steps); steps += "整理" })
        assertEquals(listOf("转写", "整理"), steps)
        // 本机不受计费网络限制；电量低、偏热沿用现有判断。
        assertNull(digestTranscribeSkip(true, true, false, true, pending))
        assertNotNull(digestTranscribeSkip(true, true, false,
            EarlyTranscribe.shouldRun(true, pending, 10, false, false), pending))
        assertNotNull(digestTranscribeSkip(true, true, false,
            EarlyTranscribe.shouldRun(true, pending, 80, true, true), pending))
    }

    @Test fun wifiOnlyOnMeteredNetworkStillOrganizes() = runBlocking {
        val steps = mutableListOf<String>()
        val skip = digestTranscribeSkip(false, true, false, true, 60_000)
        assertTrue(skip!!.contains("不是不计流量的网络"))
        runPipelineStages(TranscribeWork.MODE_DIGEST, skip,
            { fail("不能上传转写") }, { steps += "整理已有" })
        assertEquals(listOf("整理已有"), steps)
    }

    @Test fun sameDay() {
        assertEquals("10月29日 周四 14:05–14:32", time.event(at("2026-10-29T14:05"), at("2026-10-29T14:32")))
        assertEquals("10月29日 14:05", time.moment(at("2026-10-29T14:05")))
    }

    @Test fun acrossDays() {
        assertEquals("10月29日 周四 23:50–10月30日 周五 00:20", time.event(at("2026-10-29T23:50"), at("2026-10-30T00:20")))
    }

    @Test fun acrossYears() {
        assertEquals("2025年12月31日 周三 23:50–1月1日 周四 00:20", time.event(at("2025-12-31T23:50"), at("2026-01-01T00:20")))
        assertEquals("2025年10月29日 14:05", time.moment(at("2025-10-29T14:05")))
    }
}
