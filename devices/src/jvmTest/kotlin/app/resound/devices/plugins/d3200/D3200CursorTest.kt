package app.resound.devices.plugins.d3200

import app.resound.audio.*
import app.resound.devices.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class D3200CursorTest {
    private fun packet() = ByteArray(160).apply { this[0] = 0xf8.toByte() }
    @Test fun rawResumeReplaysOneBoundaryPacketAndEmitsOnlyMissingBytes() {
        val cursor = D3200DownloadCursor(320, 4)
        assertEquals(1L, cursor.requested)
        assertNull(cursor.accept(1, packet()))
        assertArrayEquals(packet(), cursor.accept(2, packet()))
        assertNull(cursor.accept(2, packet()))
        assertArrayEquals(packet(), cursor.accept(3, packet()))
        assertTrue(cursor.complete)
        val eof = D3200DownloadCursor(640, 4)
        assertFalse(eof.complete); assertNull(eof.accept(3, packet())); assertTrue(eof.complete)
    }
    @Test fun missingPacketsAndPartialByteOffsetsAreRejected() {
        try { D3200DownloadCursor(161, 4); fail("unaligned queue offset") } catch (_: IllegalArgumentException) {}
        val cursor = D3200DownloadCursor(0, 4)
        cursor.accept(0, packet())
        try { cursor.accept(2, packet()); fail("sequence hole") } catch (_: IllegalArgumentException) {}
        assertFalse(cursor.complete)
    }
}
