package app.resound.devices.plugins.d3200

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class D3200HistoryTest {
    @Test fun savedSyncMustPrecedeAutoStartAndCannotRunDuringRecording() {
        assertEquals(listOf(D3200HistoryCore.Startup.SyncSaved, D3200HistoryCore.Startup.StartRecording),
            D3200HistoryCore.startup(0, sync = true, autoStart = true))
        assertEquals(listOf(D3200HistoryCore.Startup.WaitForSave),
            D3200HistoryCore.startup(1, sync = true, autoStart = true))
        assertTrue(D3200HistoryCore.startup(0, sync = false, autoStart = false).isEmpty())
    }
    @Test fun unsolicitedCurrentFileHeaderCannotSatisfyOfflineRequest() {
        val requested = 1730000000L
        assertFalse(D3200Protocol.headerMatches(D3200Protocol.le32(requested + 1) + ByteArray(83), requested))
        assertTrue(D3200Protocol.headerMatches(D3200Protocol.le32(requested) + ByteArray(83), requested))
        assertFalse(D3200Protocol.headerMatches(ByteArray(3), requested))
    }
    @Test fun paginatedListSeparatesOpenFileAndUsesDeviceEpoch() {
        val payload = byteArrayOf(2, 0) + D3200Protocol.le32(1730000000) + D3200Protocol.le32(1730000010) +
            D3200Protocol.le32(10000) + D3200Protocol.le32(1730000030) + D3200Protocol.le32(0) +
            D3200Protocol.le32(2000) + D3200Protocol.le32(1730000030) + D3200Protocol.le32(2000)
        val page = D3200HistoryCore.page(payload)
        assertEquals(2, page.count); assertEquals(1730000030L, page.current)
        assertEquals(10000L, page.files[0].durationMillis)
        assertEquals(1730000000000L, D3200HistoryCore.epoch(page.files[0].id, 1731000000000))
        try { D3200HistoryCore.page(payload.dropLast(1).toByteArray()); fail("truncated list") } catch (_: IllegalArgumentException) {}
    }
    @Test fun unknownLengthsAndInvalidClocksAreRejected() {
        assertEquals(500L, D3200HistoryCore.packets(83000))
        for (size in listOf(0L, 167, 0xffffffffL)) {
            try { D3200HistoryCore.packets(size); fail("invalid length") } catch (_: IllegalArgumentException) {}
        }
        try { D3200HistoryCore.epoch(10, 1731000000000); fail("invalid clock") } catch (_: IllegalArgumentException) {}
        val offline = D3200Protocol.offlineRequest(1730000000, 123)
        assertEquals(123 * 166L, D3200Protocol.u32(D3200Protocol.payload(offline), 0))
        assertEquals(0, D3200Protocol.payload(offline).last().toInt())
    }
    @Test fun liveIntervalsAndOverlapsLeaveOnlyGaps() {
        val r = D3200HistoryCore.Range(100, 200)
        val missing = D3200HistoryCore.missing(500, listOf(r, D3200HistoryCore.Range(180, 300), D3200HistoryCore.Range(400, 600)))
        assertEquals(listOf(D3200HistoryCore.Range(0, 100), D3200HistoryCore.Range(300, 400)), missing)
        assertTrue(D3200HistoryCore.missing(100, listOf(D3200HistoryCore.Range(0, 200))).isEmpty())
    }
    @Test fun boundedImportPlanIsStableAcrossRetries() {
        val plan = D3200HistoryCore.pieces(listOf(D3200HistoryCore.Range(50, 30051)))
        assertEquals(listOf(D3200HistoryCore.Range(50, 15050), D3200HistoryCore.Range(15050, 30050), D3200HistoryCore.Range(30050, 30051)), plan)
        assertEquals(plan, D3200HistoryCore.pieces(D3200HistoryCore.missing(30051, listOf(D3200HistoryCore.Range(0, 50)))))
    }
    @Test fun oggKeepsPacketsAndValidatesCrcGranulesAndStereo() {
        val raw = File.createTempFile("bean-opus", ".part")
        val ogg = File.createTempFile("bean-opus", ".ogg")
        try {
            val packet = ByteArray(160).apply { this[0] = 0xfc.toByte() } // 20 ms stereo; zeros are real packet bytes
            raw.writeBytes(packet + packet + packet)
            D3200Ogg.write(raw, ogg, D3200HistoryCore.Range(1, 3))
            val bytes = ogg.readBytes(); var at = 0; var pages = 0
            while (at < bytes.size) {
                assertEquals("OggS", String(bytes, at, 4))
                val laces = bytes[at + 26].toInt() and 255
                val body = (0 until laces).sumOf { bytes[at + 27 + it].toInt() and 255 }
                val page = bytes.copyOfRange(at, at + 27 + laces + body)
                val expected = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN).getInt(22)
                repeat(4) { page[22 + it] = 0 }
                assertEquals(expected, D3200Ogg.crc(page))
                if (pages == 0) assertEquals(2, bytes[at + 28 + 9].toInt())
                if (pages == 2) {
                    assertEquals(1920L, ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN).getLong(6))
                    assertEquals(4, page[5].toInt()); assertEquals(320, body)
                    assertArrayEquals(packet + packet, page.copyOfRange(27 + laces, page.size))
                }
                pages++; at += page.size
            }
            assertEquals(3, pages)
        } finally { raw.delete(); ogg.delete() }
    }
    @Test fun unsupportedPacketTimingIsNotFalselyTimestamped() {
        val packet = ByteArray(160).apply { this[0] = 0xf8.toByte() }
        assertEquals(960, D3200Ogg.packetSamples(packet))
        packet[0] = 0x80.toByte() // 2.5 ms, not the D3200 20 ms format
        try { D3200Ogg.packetSamples(packet); fail("incorrect packet timing") } catch (_: IllegalArgumentException) {}
    }
}
