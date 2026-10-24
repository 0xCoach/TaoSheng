package app.resound.capture

import org.junit.Assert.*
import org.junit.Test

class VoiceInputSpeakerTest {
    @Test fun greenBubbleOverridesCenteredAndLeftAlignedTextAcrossScales() {
        for (scale in listOf(1, 2)) {
            val line = OcrLine("合成文字", 200 * scale, 400 * scale, 880 * scale, 440 * scale)
            assertEquals("我", VoiceInputOcr.wechatSpeaker(line, 1080 * scale, 2400 * scale) { x, _ ->
                if (x % 3 == 0) 0x101010 else 0x36b375
            })
            assertNull(VoiceInputOcr.wechatSpeaker(line, 1080 * scale, 2400 * scale) { _, _ -> 0x2b2b2b })
            assertNull(VoiceInputOcr.wechatSpeaker(line, 1080 * scale, 2400 * scale) { _, _ -> 0xffffff })
        }
    }

    @Test fun ownLongMessageAndShortWrapShareOwnSpeaker() {
        val lines = listOf(
            OcrLine("长消息的第一行", 200, 400, 880, 440, 1, "我"),
            OcrLine("折行短句", 200, 470, 480, 510, 1, "我"),
            OcrLine("另一条消息", 200, 600, 880, 640, 2, "我"))
        assertEquals(listOf("我：长消息的第一行折行短句", "我：另一条消息"),
            VoiceInputOcr.chatContext(lines, 1080, "com.tencent.mm", "测试"))
    }

    @Test fun incomingLongMessageSharesSpeakerWithItsShortWrap() {
        val lines = listOf(
            OcrLine("对方的长消息", 200, 400, 880, 440, 1),
            OcrLine("折行短句", 200, 470, 600, 510, 1))
        assertEquals(listOf("测试：对方的长消息折行短句"),
            VoiceInputOcr.chatContext(lines, 1080, "com.tencent.mm", "测试"))
    }
    @Test fun actualBubbleBoundariesMergeAcrossOcrBlocksAndSeparateAdjacentBubbles() {
        val layout = OcrLayout(1080, 2400, 126, 2332, 1525)
        // 按真机截图的气泡边界构造：两条绿色气泡，然后一条灰色气泡。
        fun pixel(x: Int, y: Int): Int = when {
            x in 168..910 && (y in 445..622 || y in 655..832) -> 0x3eb575
            x in 168..910 && y in 865..1042 -> 0x2c2c2c
            else -> 0x111111
        }
        val raw = listOf(
            OcrLine("第一句的上半段", 200, 480, 880, 520, 1),
            OcrLine("下半段", 200, 548, 560, 590, 2),
            OcrLine("下一条消息", 200, 690, 880, 730, 2),
            OcrLine("也有换行", 200, 755, 560, 800, 3),
            OcrLine("对方长消息", 200, 900, 880, 940, 4),
            OcrLine("对方的折行", 200, 965, 600, 1005, 5))
        val lines = raw.map { VoiceInputOcr.wechatBubble(it, layout, ::pixel) }
        assertEquals(listOf("我", "我", "我", "我", "对方", "对方"), lines.map { it.speakerHint })
        assertEquals(lines[0].bubbleKey, lines[1].bubbleKey)
        assertNotEquals(lines[1].bubbleKey, lines[2].bubbleKey)
        assertEquals(listOf("我：第一句的上半段下半段", "我：下一条消息也有换行", "测试：对方长消息对方的折行"),
            VoiceInputOcr.chatContext(lines, 1080, "com.tencent.mm", "测试"))
        assertEquals("下一条消息也有换行", VoiceInputOcr.lastRightBubble(lines, layout))
    }

    @Test fun lightThemeIncomingBubbleIsNotMineAndTimestampHasNoBubble() {
        val layout = OcrLayout(1080, 2400, 126, 2332, 1525)
        fun pixel(x: Int, y: Int) = if (x in 168..910 && y in 445..622) 0xffffff else 0xededed
        val incoming = VoiceInputOcr.wechatBubble(OcrLine("合成长句", 200, 480, 880, 520), layout, ::pixel)
        assertEquals("对方", incoming.speakerHint)
        val time = VoiceInputOcr.wechatBubble(OcrLine("凌晨1:03", 430, 1100, 650, 1140), layout, ::pixel)
        assertNull(time.bubbleKey)
        assertNull(time.speakerHint)
    }

}
