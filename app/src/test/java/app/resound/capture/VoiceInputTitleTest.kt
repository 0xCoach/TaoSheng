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
        val source = listOf(line("标题🌸", 350, 150, 650),
            line("跨过标题栏边界", 100, 180, 600), input)
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
    @Test fun centeredChatTimestampCannotReplaceContactName() {
        val source = listOf(line("马正涛", 300, 100, 560),
            line("8月20日 凌晨2:00", 300, 145, 700),
            line("明天见", 60, 500, 450), input)
        val result = VoiceInputOcr.extract(source, layout, "com.tencent.mm")!!
        assertEquals("马正涛", result.title)
        assertEquals(listOf("马正涛：明天见"), result.context)
    }

    @Test fun timestampsNeverBecomeNamesAndMissingNameStaysUnknown() {
        for (text in listOf("8月20日 凌晨2:00", "昨天 18:30", "星期五 下午3:00", "2026年10月25日", "凌晨2:00")) {
            assertNull(VoiceInputOcr.cleanTitle(text))
        }
        val result = VoiceInputOcr.extract(listOf(line("8月20日 凌晨2:00", 300, 145, 700),
            line("明天见", 60, 500, 450), input), layout, "com.tencent.mm")!!
        assertNull(result.title)
        assertEquals(listOf("对方：明天见"), result.context)
        assertEquals("夏天🌸", VoiceInputOcr.cleanTitle("夏天🌸"))
    }

    @Test fun measuredWechatTitleBandScalesAcrossResolutions() {
        for (scale in listOf(1.0, 4.0 / 3.0, 0.5)) {
            fun px(value: Int) = (value * scale).toInt()
            val screen = OcrLayout(px(1080), px(2400), px(126), px(2332), px(1500))
            val title = OcrLine("文件传输助手", px(399), px(168), px(670), px(212))
            // 标题栏下面刚露出的聊天日期即使更居中，也不能参选。
            val date = OcrLine("8月20日 凌晨2:00", px(300), px(270), px(780), px(305))
            assertEquals("文件传输助手", VoiceInputOcr.title(listOf(date, title), screen))
            assertTrue(VoiceInputOcr.titleBarBottom(screen) in px(254)..px(256))
            assertEquals(VoiceInputOcr.titleBarBottom(screen),
                VoiceInputOcr.titleBarBottom(screen.copy(imeTop = px(1000))))
        }
    }

}
