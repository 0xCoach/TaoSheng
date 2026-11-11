package app.resound.devices.core

import app.resound.devices.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CardCodec118Test {
    private val parser = CardParser("0.2.21")
    private fun json(name: String) = if (name == "synthetic") javaClass.getResource("/devices/synthetic.json")!!.readText() else File(System.getProperty("deviceCardsDir"), "$name.json").readText()
    private fun card(name: String) = parser.parse(json(name)).getOrThrow()
    private fun invalid(name: String, expected: String, edit: (String) -> String) {
        val original = json(name)
        val edited = edit(original)
        assertNotEquals("测试必须修改输入", original, edited)
        val error = parser.parse(edited).exceptionOrNull()
        assertNotNull("应拒绝：$expected", error)
        assertTrue("实际原因：${error?.message}", error!!.message.orEmpty().contains(expected))
    }
    private fun hex(value: String) = value.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    // 厂商协议 §7.3 的真实 TX 抓包，原样保留；不是由被测编码器生成的测试向量。
    private val capture = hex("5a 03 9e 20 1e 00 02 02 00 00 00 00 6e 6f 74 65 32 30 32 36 30 37 31 30 2d 31 36 32 39 33 38 2e 77 61 76 00")

    @Test fun bundledCardsAndReviewSummary() {
        val omi = card("omi"); val synthetic = card("synthetic")
        assertEquals(CardStatus.Trial, omi.status); assertEquals(CardStatus.Trial, synthetic.status)
        val omiAudio = omi.audio!!
        assertEquals(Codec.Pcm16, omiAudio.codecMap[0]); assertEquals(Codec.Pcm8, omiAudio.codecMap[1])
        assertEquals(Codec.Opus10, omiAudio.codecMap[20]); assertEquals(Codec.Opus20, omiAudio.codecMap[21])
        assertEquals(OfflineMode.Files, synthetic.offline); assertEquals("synthetic", synthetic.filePlugin)
        val report = describeCard(synthetic)
        listOf("试用", "0000ae22", "TYPE=1 CMD=0", "len + payload", "命令字节示例", "文件插口 synthetic").forEach { assertTrue(report, report.contains(it)) }
        assertEquals(247, synthetic.link.mtu)
    }

    @Test fun rejectsUnknownFieldsEnumsAndMalformedUuid() {
        invalid("omi", "未知字段") { it.replace("\"audio\": {", "\"audio\": {\"shell\": \"run\",") }
        invalid("omi", "未知取值") { it.replace("\"trial\"", "\"Trial\"") }
        invalid("omi", "UUID") { it.replace("19b10001-e8f2-537e-4f6c-d104768a1214", "19b1") }
        invalid("omi", "未知取值") { it.replace("\"opus10\"", "\"aac\"") }
    }

    @Test fun rejectsBrokenReferencesAndMissingDependencies() {
        invalid("synthetic", "role=crc") { it.replace(Regex(""",\s*"crc": \{[^\}]*\}"""), "") }
        invalid("synthetic", "必须引用存在且 role=len 的字段") { it.replace("\"lengthFrom\": \"len\"", "\"lengthFrom\": \"missing\"") }
        invalid("omi", "keepAlive") { it.replace("\"mtu\": 247", "\"mtu\": 247, \"keepAliveSec\": 10") }
        invalid("synthetic", "filePlugin") { it.replace(",\n  \"filePlugin\": \"synthetic\"", "") }
        invalid("synthetic", "引用不存在") { it.replace("\"len\",\n            \"payload\"", "\"missing\"") }
    }

    @Test fun rejectsBadVersionAndTooOldApp() {
        invalid("omi", "版本格式") { it.replace("\"minApp\": \"0.2.21\"", "\"minApp\": \"0.2\"") }
        invalid("omi", "需要涛声 0.2.22 以上") { it.replace("\"minApp\": \"0.2.21\"", "\"minApp\": \"0.2.22\"") }
        assertTrue(CardParser("0.2.20").parse(json("omi")).exceptionOrNull()!!.message!!.contains("需要涛声 0.2.21 以上"))
        assertTrue(CardParser("0.2.21-preview.1").parse(json("omi")).isSuccess)
    }

    @Test fun strictJsonRejectsDuplicateKeysAndNonJsonSyntax() {
        for (bad in listOf("{'id':'omi'}", "{\"id\":\"a\",\"id\":\"b\"}", "{\"id\":1,}", "{\"id\":01}", json("omi") + " false")) {
            assertTrue(bad, parser.parse(bad).isFailure)
        }
        invalid("omi", "必须为整数") { it.replace("\"mtu\": 247", "\"mtu\": \"247\"") }
        invalid("omi", "hex:") { it.replace("\"mtu\": 247", "\"mtu\": 247, \"keepAlive\": \"hex:A\"") }
    }

    @Test fun crcStandardCheckValues() {
        val bytes = "123456789".toByteArray(Charsets.US_ASCII)
        assertEquals(0x31c3L, Crc.compute(CrcAlgo.Crc16Xmodem, bytes))
        assertEquals(0x4b37L, Crc.compute(CrcAlgo.Crc16Modbus, bytes))
        assertEquals(0xcbf43926L, Crc.compute(CrcAlgo.Crc32, bytes))
    }

    @Test fun realCaptureLocksEndianAndExtractsData() {
        val codec = FrameCodec(card("synthetic").audio!!.frame)
        assertNull(codec.accept(capture))
        assertEquals(Endian.Le, codec.probe(capture))
        val frame = codec.accept(capture)!!
        assertEquals(30L, frame.fields["len"]); assertEquals(0x209eL, frame.fields["crc"])
        assertArrayEquals(capture.copyOfRange(6, 36), frame.payload)
        assertArrayEquals(hex("020200000000"), frame.payload.copyOfRange(0, 6))
        assertEquals("note20260710-162938.wav\u0000", frame.payload.copyOfRange(6, 30).toString(Charsets.US_ASCII))
        val corrupt = capture.copyOf().also { it[it.lastIndex] = 1 }
        assertNull(codec.accept(corrupt)); assertEquals(1L, codec.diagnostics.crcFailures)
        assertNull(codec.accept(capture.copyOf().also { it[0] = 0 }))
        assertNull(codec.accept(capture.copyOf(capture.size - 1)))
    }

    @Test fun probeBigEndianAndAmbiguousResponse() {
        val spec = card("synthetic").audio!!.frame
        val big = capture.copyOf().also { it[2] = 0; it[3] = 0; it[4] = 0; it[5] = 30 }
        val crc = Crc.compute(CrcAlgo.Crc16Xmodem, big.copyOfRange(4, big.size))
        big[2] = (crc shr 8).toByte(); big[3] = crc.toByte()
        val codec = FrameCodec(spec)
        assertEquals(2, codec.probeCommands().size)
        assertNull(codec.probe(hex("5a0000000000"))) // LEN=0、CRC=0，两端序都成立，不能猜。
        assertNull(codec.lockedEndian)
        assertEquals(Endian.Be, codec.probe(big)); assertNotNull(codec.accept(big))
        assertEquals(Endian.Be, codec.probe(capture)) // 锁定后不被后续包改变。
        assertThrows(IllegalStateException::class.java) { FrameCodec(spec).encode(CardCommand.Framed(1, 0)) }
    }

    @Test fun commandEncodingMatchesCaptureAndExplicitFieldLayout() {
        val codec = FrameCodec(card("synthetic").audio!!.frame)
        codec.probe(capture)
        val command = CardCommand.Framed(2, 2, capture.copyOfRange(8, 36))
        repeat(3) { codec.encode(command) }
        assertArrayEquals(capture, codec.encode(command))
        assertArrayEquals(hex("aa55"), codec.encode(CardCommand.Raw(hex("aa55"))))
        val spec = FrameSpec(listOf(FrameField("magic", 2, const = hex("aa55"), role = FieldRole.Magic),
            FrameField("seq", 1, role = FieldRole.Seq), FrameField("len", 2, Endian.Be, role = FieldRole.Len),
            FrameField("type", 1, role = FieldRole.Type), FrameField("cmd", 1, role = FieldRole.Cmd),
            FrameField("crc", 4, Endian.Be, role = FieldRole.Crc, crc = CrcSpec(CrcAlgo.Crc32, listOf("len", "type", "cmd", "payload")))), PayloadSpec.LengthFrom("len"))
        val explicit = FrameCodec(spec)
        val packed = explicit.encode(CardCommand.Framed(1, 7, hex("fffe")))
        val decoded = explicit.accept(packed)!!
        assertEquals(7L, decoded.fields["cmd"]); assertEquals(2L, decoded.fields["len"])
        assertArrayEquals(hex("fffe"), decoded.payload)
    }

    @Test fun fragmentsCompleteOnlyAtNextStartAndDropOnLoss() {
        val codec = FrameCodec(card("omi").audio!!.frame)
        fun packet(seq: Int, frag: Int, data: Int) = byteArrayOf(seq.toByte(), (seq shr 8).toByte(), frag.toByte(), data.toByte())
        assertNull(codec.accept(packet(10, 0, 1))); assertNull(codec.accept(packet(11, 1, 2)))
        assertArrayEquals(byteArrayOf(1, 2), codec.accept(packet(12, 0, 3))!!.payload)
        assertNull(codec.accept(packet(14, 2, 5))) // 包号和分片号均跳号，整帧作废。
        assertNull(codec.accept(packet(15, 0, 6)))
        assertArrayEquals(byteArrayOf(6), codec.accept(packet(16, 0, 7))!!.payload)
        assertEquals(1L, codec.diagnostics.lost)
        assertNull(codec.accept(packet(17, 2, 8))) // 包号连续，但分片号跳号。
        assertNull(codec.accept(packet(18, 0, 9)))
        assertNull(codec.accept(packet(20, 0, 10))) // 缺的可能是上一帧末片，不能交付上一帧。
        codec.discardFragments()
        assertNull(codec.accept(packet(21, 0, 11)))
    }

    @Test fun sequenceWrapDuplicatesAndLatePackets() {
        val codec = FrameCodec(FrameSpec(listOf(FrameField("seq", 1, role = FieldRole.Seq))))
        assertNotNull(codec.accept(hex("fe01"))); assertNotNull(codec.accept(hex("ff02")))
        assertNotNull(codec.accept(hex("0003"))); assertNotNull(codec.accept(hex("0304")))
        assertEquals(2L, codec.diagnostics.lost)
        assertNull(codec.accept(hex("0304"))); assertNull(codec.accept(hex("0204")))
        assertNotNull(codec.accept(hex("0405"))); assertEquals(2L, codec.diagnostics.lost)
    }

    @Test fun pcm16StereoResamplesAcrossOddByteBoundaries() {
        val samples = ShortArray(1440 * 2) { if (it % 2 == 0) 1000 else 3000 }
        val bytes = ByteArray(samples.size * 2) { i -> (samples[i / 2].toInt() shr (8 * (i % 2))).toByte() }
        val decoder = AudioDecoder(Codec.Pcm16, 48000, 2)
        assertTrue(decoder.accept(bytes.copyOfRange(0, 17)).isEmpty())
        val frames = decoder.accept(bytes.copyOfRange(17, bytes.size))
        assertEquals(1, frames.size); assertEquals(480, frames.single().size)
        assertTrue(frames.single().all { it == 2000.toShort() })
        assertTrue(decoder.finish().isEmpty()); decoder.close()
    }

    @Test fun mulawAndUnsignedPcm8UpsampleAndReset() {
        val decoder = AudioDecoder(Codec.Mulaw, 8000)
        val output = decoder.accept(ByteArray(240) { if (it % 2 == 0) 0x80.toByte() else 0 })
        assertEquals(480, output.single().size)
        assertArrayEquals(shortArrayOf(32124, 32124, -32124, -32124), output.single().copyOfRange(0, 4))
        val pcm8 = AudioDecoder(Codec.Pcm8, 8000)
        val unsigned = pcm8.accept(ByteArray(240) { 128.toByte() }).single()
        assertTrue(unsigned.all { it == 0.toShort() })
        pcm8.accept(byteArrayOf(0)); pcm8.reset()
        assertTrue(pcm8.accept(ByteArray(240) { 255.toByte() }).single().all { it == 32512.toShort() })
    }

    @Test fun opusFakeSupportsOneAndLengthPrefixedAndActualOutputRate() {
        class Fake : OpusDecoder {
            val packets = mutableListOf<List<Byte>>()
            override fun decode(packet: ByteArray): List<DecodedPcm> {
                packets += packet.toList()
                return listOf(DecodedPcm(ShortArray(480) { 900 }, 48000, 1))
            }
        }
        val one = AudioDecoder(Codec.Opus10, opus = Fake())
        assertTrue(one.accept(hex("aa")).isEmpty()); assertTrue(one.accept(hex("bb")).isEmpty())
        assertEquals(480, one.accept(hex("cc")).single().size)
        val fake = Fake()
        val multi = AudioDecoder(Codec.Opus10, packing = OpusPacking.LengthPrefixed, opus = fake)
        val frames = multi.accept(hex("02aabb01cc01dd"))
        assertEquals(listOf(hex("aabb").toList(), hex("cc").toList(), hex("dd").toList()), fake.packets)
        assertTrue(frames.single().all { it == 900.toShort() })
        val delayed = object : OpusDecoder {
            override fun decode(packet: ByteArray) = emptyList<DecodedPcm>()
            override fun finish() = listOf(DecodedPcm(ShortArray(480), 16000, 1))
        }
        assertEquals(480, AudioDecoder(Codec.Opus20, opus = delayed).finish().single().size)
    }

    @Test fun malformedOpusPackingDoesNotPartiallyDecode() {
        var calls = 0
        val fake = object : OpusDecoder {
            override fun decode(packet: ByteArray): List<DecodedPcm> { calls++; return emptyList() }
        }
        val decoder = AudioDecoder(Codec.Opus20, packing = OpusPacking.LengthPrefixed, opus = fake)
        for (payload in listOf(hex("01aa02bb"), hex("00"))) {
            val failure = assertThrows(IllegalArgumentException::class.java) { decoder.accept(payload) }
            assertTrue(failure.message!!.contains("长度前缀"))
        }
        assertEquals(0, calls)
    }
}
