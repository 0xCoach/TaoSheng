package app.resound.capture

import org.junit.Assert.*
import org.junit.Test

class VoiceInputTitleTest {
    private val layout = OcrLayout(1000, 2000, 70, 1950, 1200)
    private fun line(text: String, left: Int, top: Int, right: Int) =
        OcrLine(text, left, top, right, top + 40)
    private val input = line("正在输入的内容", 100, 1140, 600)

    @Test fun choosesCenteredTitleAndRemovesAllHeaderText() {
        val source = listOf(line("左侧文字", 10, 100, 160), line("‹ (99+) 老王👨‍👩‍👧", 300, 100, 700),
            line("更多", 850, 100, 990), line("第一条消息", 80, 250, 500), input)
        val result = VoiceInputOcr.extract(source, layout, "com.tencent.mm")!!
        assertEquals("老王👨‍👩‍👧", result.title)
        assertEquals(listOf("老王👨‍👩‍👧：第一条消息"), result.context)
    }

    @Test fun missingTitleNeverFallsBackToFirstChatMessage() {
        val source = listOf(line("く", 10, 100, 60), line("(99+)", 80, 100, 150),
            line("第一条消息", 80, 250, 500), input)
        val result = VoiceInputOcr.extract(source, layout, "com.tencent.mm")!!
        assertNull(result.title)
        assertEquals(listOf("对方：第一条消息"), result.context)
    }

    @Test fun preservesEmojiOnlyNamesAndGroupCounts() {
        assertEquals("🌸", VoiceInputOcr.cleanTitle("← (12) 🌸"))
        assertEquals("项目🌸(12)", VoiceInputOcr.cleanTitle("< 99+ 项目🌸(12)"))
        assertNull(VoiceInputOcr.cleanTitle("<1344"))
        assertFalse(VoiceInputOcr.looksLikeTitle("+putOcr.looksLike(\"く\")"))
    }

    @Test fun titleBoundaryIsFixedWhenKeyboardChangesAndOverlappingLineIsExcluded() {
        val source = listOf(line("标题🌸", 350, 190, 650),
            line("跨过标题栏边界", 100, 220, 600), input)
        assertEquals("标题🌸", VoiceInputOcr.title(source, layout))
        assertEquals("标题🌸", VoiceInputOcr.title(source, layout.copy(imeTop = 900)))
        assertTrue(VoiceInputOcr.extract(source, layout)!!.context.isEmpty())
    }

    @Test fun terminalContextAlsoExcludesWholeHeader() {
        val source = listOf(line("顶部标签", 300, 100, 700), line("左侧标签", 10, 100, 150),
            line("历史输出", 80, 500, 600), line("❯ 执行命令", 100, 1140, 600))
        val result = VoiceInputOcr.extract(source, layout, "com.termux")!!
        assertNull(result.title)
        assertEquals(listOf("历史输出"), result.context)
        assertEquals("执行命令", result.text)
    }
}
