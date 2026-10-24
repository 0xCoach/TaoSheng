package app.resound.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceInputChatOrderTest {
    @Test fun interleavedSpeakersFollowScreenOrderInsteadOfNodeTraversalOrder() {
        val lines = listOf(
            OcrLine("我再确认一下", 650, 700, 950, 740, 4),
            OcrLine("明天下午见", 50, 400, 450, 440, 1),
            OcrLine("好的", 700, 500, 950, 540, 2),
            OcrLine("地点发给你", 50, 600, 450, 640, 3))
        assertEquals(listOf("老王：明天下午见", "我：好的", "老王：地点发给你", "我：我再确认一下"),
            VoiceInputOcr.chatContext(lines, 1000, "com.tencent.mm", "老王"))
    }

    @Test fun wrappedLinesMergeButSeparateMessagesFromSameSpeakerStaySeparate() {
        val lines = listOf(
            OcrLine("这是同一条", 650, 400, 950, 440, 1),
            OcrLine("消息的折行", 650, 450, 950, 490, 1),
            OcrLine("这是下一条", 650, 500, 950, 540, 2),
            OcrLine("隔很远的新消息", 650, 700, 950, 740, 2))
        assertEquals(listOf("我：这是同一条消息的折行", "我：这是下一条", "我：隔很远的新消息"),
            VoiceInputOcr.chatContext(lines, 1000, "com.tencent.mm", "老王"))
    }
}
