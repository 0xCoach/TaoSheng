package app.resound.voiceprint

import app.resound.data.TranscriptLineEntity
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class VoiceprintMathTest {
    @Test fun kaldiFbankMatchesPythonEveryValueAndNormalizesEachBin() {
        val fixtures = File("../tools/voiceprint-probe/fixtures")
        for (name in listOf("silence", "noise", "speech")) {
            val input = ByteBuffer.wrap(File(fixtures, "$name.pcm").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val pcm = ShortArray(input.remaining()).also { input.get(it) }
            val reference = ByteBuffer.wrap(File(fixtures, "$name.fbank").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val raw = KaldiFbank().compute(pcm, false)
            assertEquals(reference.remaining(), raw.size * 80)
            for (frame in raw) for (value in frame) assertTrue("$name fbank 偏差", abs(value - reference.get()) < 0.002f)
            val normalized = KaldiFbank().compute(pcm)
            for (bin in 0 until 80) assertEquals(0.0, normalized.sumOf { it[bin].toDouble() } / normalized.size, 1e-5)
        }
        assertTrue(KaldiFbank().compute(shortArrayOf()).isEmpty())
    }
    @Test fun cosineAndThresholdBoundariesRejectInvalidVectors() {
        assertEquals(1f, VoiceprintMath.cosine(floatArrayOf(1f, 2f), floatArrayOf(2f, 4f)), 1e-6f)
        assertEquals(0f, VoiceprintMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 0f)
        assertEquals(0f, VoiceprintMath.cosine(floatArrayOf(1f), floatArrayOf(-1f)), 0f)
        // “可能是我”区间不写名字（只在界面显示问号），确定才写“我”
        assertNull(VoiceprintMath.name(VoiceprintThresholds.MAYBE))
        assertNull(VoiceprintMath.name(VoiceprintThresholds.SURE - 0.0001f))
        assertEquals("我", VoiceprintMath.name(VoiceprintThresholds.SURE))
        assertNull(VoiceprintMath.name(null))
        assertThrows(IllegalArgumentException::class.java) { VoiceprintMath.unit(floatArrayOf(0f)) }
        assertThrows(IllegalArgumentException::class.java) { VoiceprintMath.unit(floatArrayOf(Float.NaN)) }
        val v = FloatArray(192) { it.toFloat() }
        assertArrayEquals(v, VoiceprintMath.decode(VoiceprintMath.encode(v)), 0f)
        val mean = VoiceprintMath.representative(listOf(floatArrayOf(10f, 0f), floatArrayOf(0f, 1f)))
        assertEquals(mean[0], mean[1], 1e-6f)
    }
    @Test fun groupClipsMergesOverlapsExcludesOtherVoicesAndDropsSilence() {
        val pcm = ShortArray(16000 * 6) { i -> when (i / 16000) { 0 -> 1000; 1 -> 0; 2 -> 2000; 3 -> 3000; else -> 4000 }.toShort() }
        fun line(id: Long, from: Long, to: Long, tag: String? = "0") = TranscriptLineEntity(id, 1, 10_000 + from, 10_000 + to, "测试", tag)
        val parts = listOf(line(1, -100, 2000), line(2, 2500, 3000), line(3, 2750, 3000), line(4, 5000, 8000))
        val group = VoiceprintAudio.group(pcm, 10_000, parts)
        assertEquals(40_000, group.size) // 1 秒 + 0.5 秒 + 1 秒，重叠不重复、静音不入样本。
        assertTrue(group.none { it == 0.toShort() || it == 3000.toShort() })
        assertEquals(8000, group.count { it == 2000.toShort() })
        assertNotEquals(VoiceprintAudio.groupKey(line(5, 0, 1000, null)), VoiceprintAudio.groupKey(line(6, 0, 1000, null)))
        assertTrue(VoiceprintAudio.group(pcm, 10_000, listOf(line(7, 3000, 2000))).isEmpty())
    }
}
