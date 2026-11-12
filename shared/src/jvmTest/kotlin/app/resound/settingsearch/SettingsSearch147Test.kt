package app.resound.settingsearch

import org.junit.Assert.*
import org.junit.Test

class SettingsSearch147Test {
    private val search = DefaultSettingsSearch()

    @Test fun colloquialBatteryQueryFindsKeepAliveAndIndexHasUniqueAnchors() {
        assertEquals("后台保活", search.search("耗电").first().title)
        assertEquals("battery", search.search("耗电").first().anchor)
        assertEquals(search.entries.size, search.entries.distinctBy { it.page to it.anchor }.size)
        assertTrue(search.entries.all { it.keywords.size in 2..6 })
    }

    @Test fun hotwordsRouteAndRankingIgnoreWhitespaceAndCase() {
        val hotwords = search.search("热 词").first()
        assertEquals("Transcript", hotwords.page)
        assertEquals("hotwords", hotwords.anchor)
        assertEquals("整理模型", search.search(" d Ee P s Ee K ").first().title)
        assertEquals("整理模型", search.search("整理模型").first().title)
        assertEquals("余额提醒", search.search("余额").first().title) // 标题开头优先于关键词完全相等
        assertEquals(listOf("本地模型", "整理模型"), search.search("模型", 2).map { it.title }) // 同分按索引顺序
    }

    @Test fun emptyQueryAndNonPositiveLimitReturnNothing() {
        assertTrue(search.search(" \t\n　").isEmpty())
        assertTrue(search.search("模型", 0).isEmpty())
        assertTrue(search.search("模型", -1).isEmpty())
    }
}
