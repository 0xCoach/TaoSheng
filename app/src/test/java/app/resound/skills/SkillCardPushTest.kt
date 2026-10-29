package app.resound.skills

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class SkillCardPushTest {
    @Test fun listsFieldsUnderSummaryAndSkipsEmptyOnes() {
        val card = SkillCard(1, "evening", "收工打卡", "moon", LocalDate.of(2026, 10, 27), 0, null, "周二收工", "推进了省电改造。",
            listOf(
                SkillField("done", "今天做了什么", FieldType.Bullets, ""),
                SkillField("progress", "推进到哪", FieldType.Paragraph, ""),
                SkillField("tomorrow", "明天计划", FieldType.Checklist, ""),
                SkillField("line", "收工一句话", FieldType.Text, ""),
            ),
            mapOf(
                "done" to FieldValue(items = listOf("改收音循环", "加上手清单")),
                "progress" to FieldValue(text = " "),
                "tomorrow" to FieldValue(items = listOf("测耗电", "发版"), checked = listOf(true, false)),
                "line" to FieldValue(text = "稳步推进"),
            ), emptyList(), 0, false)
        assertEquals("收工打卡 · 周二收工", SkillCardPush.title(card))
        assertEquals("""
            ${app.resound.OutputTime().moment(card.at)}
            推进了省电改造。
            【今天做了什么】
            · 改收音循环
            · 加上手清单
            【明天计划】
            ☑ 测耗电
            ☐ 发版
            【收工一句话】稳步推进
        """.trimIndent(), SkillCardPush.body(card))
    }
}
