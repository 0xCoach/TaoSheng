package app.resound.devices.plugins.d3200

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class D3200StatusTest {
    private fun response(command: Int, p: ByteArray, success: Int = 1): ByteArray =
        byteArrayOf(9, 0xff.toByte(), 0, 0, success.toByte(), (command ushr 8).toByte(), command.toByte(),
            (p.size + 10).toByte(), 0) + p + byteArrayOf(0)

    @Test fun batteryBucketsLiteralPercentagesAndUnknownAreDistinct() {
        assertEquals(10, D3200DeviceInfo.battery(0)); assertEquals(100, D3200DeviceInfo.battery(9))
        assertEquals(65, D3200DeviceInfo.battery(65)); assertNull(D3200DeviceInfo.battery(255))
    }
    @Test fun deviceInfoUsesPayloadOffsetsAndKilobytes() {
        val p = ByteArray(56)
        p[1] = 7; p[2] = 1; "04.92".toByteArray().copyInto(p, 3)
        D3200Protocol.le32(64 * 1048576L).copyInto(p, 24)
        D3200Protocol.le32(16 * 1048576L).copyInto(p, 28)
        p[32] = 0; "01.01".toByteArray().copyInto(p, 33); p[38] = 9; p[50] = 2
        val info = D3200DeviceInfo.parse(p)
        assertEquals(80, info.battery); assertEquals(100, info.boxBattery)
        assertEquals(true, info.charging); assertEquals(false, info.boxCharging)
        assertEquals("04.92", info.firmware); assertEquals("01.01", info.boxFirmware)
        assertEquals(64 * 1048576L, info.totalKb); assertEquals(16 * 1048576L, info.freeKb)
        assertEquals(2, info.recording); assertTrue(info.storage.contains("64"))
    }
    @Test fun truncatedOrInvalidInfoDoesNotInventZeroBatteryOrFreeCapacity() {
        val empty = D3200DeviceInfo.parse(byteArrayOf())
        assertNull(empty.battery); assertNull(empty.recording); assertEquals("未知", empty.storage)
        val p = ByteArray(51) { 0xff.toByte() }
        D3200Protocol.le32(10).copyInto(p, 24); D3200Protocol.le32(20).copyInto(p, 28)
        val invalid = D3200DeviceInfo.parse(p)
        assertNull(invalid.battery); assertNull(invalid.charging); assertNull(invalid.recording); assertNull(invalid.freeKb)
    }
    @Test fun pushesUpdateOnlyTheirOwnFieldsAndRejectUnknownRecordStatus() {
        val old = D3200DeviceInfo(firmware = "04.92", totalKb = 1000, freeKb = 500, recording = 1)
        val battery = D3200DeviceInfo.pushed(0x0103, byteArrayOf(4, 8), old)
        val charging = D3200DeviceInfo.pushed(0x0104, byteArrayOf(1, 0), battery)
        val paused = D3200DeviceInfo.pushed(0x1882, byteArrayOf(2), charging)
        assertEquals(50, paused.battery); assertEquals(90, paused.boxBattery)
        assertEquals(true, paused.charging); assertEquals(500L, paused.freeKb); assertEquals(2, paused.recording)
        assertEquals(paused, D3200DeviceInfo.pushed(0x1882, byteArrayOf(9), paused))
        assertEquals(1, D3200DeviceInfo.pushed(0x1a06, D3200Protocol.le32(123), paused).recording)
    }
    @Test fun queryReplyDoesNotConsumeAudioOrUnrelatedPushes() = runBlocking {
        val replies = D3200Replies(); val waiting = replies.begin(0x0101, null)
        assertFalse(replies.dispatch(response(0x1a08, ByteArray(166))))
        assertFalse(replies.dispatch(response(0x0103, byteArrayOf(3, 9))))
        assertFalse(waiting.reply.isCompleted)
        val p = ByteArray(51)
        assertTrue(replies.dispatch(response(0x0101, p))); assertArrayEquals(p, waiting.reply.await())
    }
    @Test fun fileRepliesRequireIdentityAndDisconnectFailsPendingCommand() {
        val replies = D3200Replies(); var boundaries = 0
        val header = replies.begin(0x1a07, 123) { boundaries++ }
        assertFalse(replies.dispatch(response(0x1a07, D3200Protocol.le32(124))))
        assertEquals(0, boundaries)
        assertTrue(replies.dispatch(response(0x1a07, D3200Protocol.le32(123))))
        assertTrue(header.reply.isCompleted); assertEquals(1, boundaries)
        val waiting = replies.begin(0x0101, null)
        replies.fail(java.io.IOException("disconnected")); assertTrue(waiting.reply.isCancelled)
        val next = replies.begin(0x0101, null); replies.clear(next)
        assertFalse(replies.dispatch(response(0x0101, ByteArray(51))))
    }
    @Test fun pausedHoldBlocksAutoRestartAndUnknownStateCannotConfirmControl() {
        assertFalse(D3200ControlPolicy.autoStart(configured = true, held = true))
        assertTrue(D3200ControlPolicy.autoStart(configured = true, held = false))
        assertFalse(D3200ControlPolicy.confirmed(null, false)); assertFalse(D3200ControlPolicy.confirmed(1, false))
        assertTrue(D3200ControlPolicy.confirmed(2, false)); assertTrue(D3200ControlPolicy.confirmed(1, true))
    }
    @Test fun pauseCommandIsAllowedWithoutOpeningStopDeleteOrResetCommands() {
        assertArrayEquals(byteArrayOf(2), D3200Protocol.payload(D3200Protocol.frame(0x18, 0x82, byteArrayOf(2))))
        for ((type, id, payload) in listOf(Triple(0x18, 0x82, byteArrayOf(0)), Triple(0x1a, 0x10, byteArrayOf()), Triple(1, 0xb8, byteArrayOf()))) {
            try { D3200Protocol.frame(type, id, payload); fail("unsupported control") } catch (_: IllegalArgumentException) {}
        }
    }
}
