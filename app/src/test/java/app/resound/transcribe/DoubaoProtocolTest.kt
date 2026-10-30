package app.resound.transcribe

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPOutputStream

class DoubaoProtocolTest {
    private fun fixture(name: String) = JSONObject(javaClass.getResource("/asr/$name.json")!!.readText())
    @Test fun configAndAudioFrameMatchProbe() {
        val config = DoubaoProtocol.config("ogg")
        val frame = DoubaoProtocol.frame(1, 0, config, true)
        assertArrayEquals(byteArrayOf(0x11, 0x10, 0x10, 0), frame.copyOfRange(0, 4))
        assertEquals(config.size, ByteBuffer.wrap(frame, 4, 4).int)
        val request = JSONObject(config.toString(Charsets.UTF_8)).getJSONObject("request")
        assertEquals("200", request.getString("ssd_version"))
        assertTrue(request.getBoolean("enable_speaker_info"))
        assertTrue(request.getBoolean("show_utterances"))
        assertEquals("single", request.getString("result_type"))
        assertEquals(0x22.toByte(), DoubaoProtocol.frame(2, 2, byteArrayOf(1))[1])
    }
    @Test fun decompressSequenceAndFinalAndError() {
        val payload = fixture("B_ogg_fast").getJSONObject("payload").toString().toByteArray()
        val gzip = ByteArrayOutputStream().apply { GZIPOutputStream(this).use { it.write(payload) } }.toByteArray()
        val wire = ByteBuffer.allocate(12 + gzip.size).put(byteArrayOf(0x11, 0x93.toByte(), 0x11, 0))
            .putInt(-202).putInt(gzip.size).put(gzip).array()
        val packet = DoubaoProtocol.parse(wire)
        assertTrue(packet.final); assertEquals(-202, packet.sequence)
        assertEquals(6, DoubaoProtocol.result(packet.payload, 40_000, null).sentences.size)
        val err = ByteBuffer.allocate(10).put(byteArrayOf(0x11, 0xf0.toByte(), 0, 0)).putInt(45000030).putShort(0).array()
        assertThrows(AsrException::class.java) { DoubaoProtocol.parse(err) }
        val valid = ByteBuffer.allocate(12).put(byteArrayOf(0x11, 0xf0.toByte(), 0, 0)).putInt(45000030).putInt(0).array()
        assertEquals(AsrError.Auth, DoubaoProtocol.error(DoubaoProtocol.parse(valid).code))
    }
    @Test fun missingTimeKeepsLineAndSpeakerIsTemporary() {
        val result = DoubaoProtocol.result(fixture("B_async_fast").getJSONObject("payload"), 40_000, "log")
        assertEquals(0, result.sentences.first().startMillis)
        assertEquals(3, result.sentences.size)
        assertEquals("0", result.sentences.first().speakerTag)
        val minimal = JSONObject("""{"result":{"utterances":[{"text":"原话"},{"text":"后一句","start_time":-1,"end_time":99999}]}}""")
        val lines = DoubaoProtocol.result(minimal, 3000, null).sentences
        assertEquals("原话", lines[0].text)
        assertEquals(3000, lines[1].endMillis)
        assertNull(lines[0].speakerTag)
        assertTrue(DoubaoProtocol.result(JSONObject("{}"), 3000, null).sentences.isEmpty())
    }
    @Test fun rejectsInvalidPackets() {
        for (bad in listOf(byteArrayOf(), byteArrayOf(0x10, 0x90.toByte(), 0x10, 0, 0, 0, 0, 0),
            DoubaoProtocol.frame(9, 0, "{}".toByteArray(), true).dropLast(1).toByteArray())) {
            assertThrows(AsrException::class.java) { DoubaoProtocol.parse(bad) }
        }
    }
    @Test fun retryAndRetentionBoundaries() {
        assertEquals(60_000L, TranscribePolicy.retryAt(AsrError.Network, 1, 0))
        assertEquals(480_000L, TranscribePolicy.retryAt(AsrError.Timeout, 4, 0))
        assertNull(TranscribePolicy.retryAt(AsrError.Network, 5, 0))
        assertNull(TranscribePolicy.retryAt(AsrError.Auth, 1, 0))
        val day = TranscribePolicy.RETENTION_MILLIS
        for (status in listOf("Pending", "Running", "Failed")) assertFalse(TranscribePolicy.canDelete(status, 0, day * 2))
        assertFalse(TranscribePolicy.canDelete("Done", null, day * 2))
        assertFalse(TranscribePolicy.canDelete("Done", 100, day + 99))
        assertTrue(TranscribePolicy.canDelete("Done", 100, day + 100))
        assertTrue(TranscribePolicy.canDelete("Empty", 100, day + 100))
    }
    @Test fun logIdNeverContainsKeyOrKeyParts() {
        assertNull(TranscribePolicy.safeLogId("prefix-secret-suffix", "secret-value"))
        assertNull(TranscribePolicy.safeLogId("header\nvalue", "secret"))
        assertEquals("20261023ABCD", TranscribePolicy.safeLogId("20261023ABCD", "secret-value"))
    }
    @Test fun resamplerKeepsPhaseAcrossInputAndUpsamples() {
        val output = mutableListOf<Int>()
        val converter = PcmResampler(48_000)
        repeat(48000) { converter.accept(it % 100) { sample -> output.add(sample) } }
        assertEquals(16000, output.size)
        assertEquals(listOf(0, 3, 6, 9), output.take(4))
        val up = mutableListOf<Int>()
        val low = PcmResampler(8000)
        listOf(0, 100, 200).forEach { low.accept(it) { sample -> up.add(sample) } }
        low.finish { up.add(it) }
        assertEquals(listOf(0, 50, 100, 150, 200, 200), up)
    }
}
