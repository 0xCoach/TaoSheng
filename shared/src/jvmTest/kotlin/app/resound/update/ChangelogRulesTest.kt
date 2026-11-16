package app.resound.update

import org.junit.Assert.*
import org.junit.Test

class ChangelogRulesTest {
    private val md = """
        # 涛声更新日志

        ## 0.4.2 · 2026-11-16

        待办按日历看、一键加到手机日历

        【待办日历】
        顶上新增一周日历。

        ## 0.3.0-preview.11 · 2026-11-13

        - 为录音队列增加索引

        ## 0.3.0 · 2026-11-13

        首页与夜间模式焕新。

        ### 桌面小组件
        - 放到桌面即可使用
    """.trimIndent()

    @Test fun parsesVersionsDatesTitlesAndBodies() {
        val e = ChangelogRules.parse(md)
        assertEquals(listOf("0.4.2", "0.3.0-preview.11", "0.3.0"), e.map { it.version })
        assertEquals("2026-11-16", e[0].date)
        assertEquals("待办按日历看、一键加到手机日历", e[0].title)
        assertTrue(e[0].body.contains("【待办日历】") && !e[0].body.contains("0.3.0"))
        assertEquals("为录音队列增加索引", e[1].title)
        assertTrue(e[2].body.contains("### 桌面小组件"))
    }

    @Test fun hidesPreviewsUnlessInstalled() {
        val e = ChangelogRules.parse(md)
        assertEquals(listOf("0.4.2", "0.3.0"), ChangelogRules.visible(e, "0.4.2").map { it.version })
        assertEquals(3, ChangelogRules.visible(e, "0.3.0-preview.11").size)
    }

    @Test fun whatsNewOnlyAfterRealUpdateOncePerVersion() {
        val e = ChangelogRules.parse(md)
        assertTrue(ChangelogRules.shouldShowWhatsNew(null, "0.4.2", freshInstall = false, entries = e))
        assertTrue(ChangelogRules.shouldShowWhatsNew("0.3.0", "0.4.2", false, e))
        assertFalse("全新安装不提示", ChangelogRules.shouldShowWhatsNew(null, "0.4.2", true, e))
        assertFalse("看过不再提示", ChangelogRules.shouldShowWhatsNew("0.4.2", "0.4.2", false, e))
        assertFalse("日志里没有这一版", ChangelogRules.shouldShowWhatsNew("0.4.2", "0.4.3", false, e))
    }
}
