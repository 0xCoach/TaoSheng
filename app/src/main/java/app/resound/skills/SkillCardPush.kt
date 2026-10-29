package app.resound.skills

import kotlin.math.roundToLong

/**
 * 技能卡的推送内容：标题“技能名 · 卡片标题”，正文先一句话摘要，再按字段逐项列出。
 * 通知栏收起时只看到第一行，展开看全部；企业微信、PushPlus 等通道收到同样的完整文字。
 */
object SkillCardPush {
    fun title(card: SkillCard): String =
        if (card.title.isBlank() || card.title == card.skillName) card.skillName else "${card.skillName} · ${card.title}"

    fun body(card: SkillCard): String = buildString {
        val time = app.resound.OutputTime()
        appendLine(card.endAt?.let { time.event(card.at, it) } ?: time.moment(card.at))
        if (card.summary.isNotBlank()) appendLine(card.summary.trim())
        for (field in card.fields) {
            val lines = lines(field, card.values[field.key] ?: continue)
            if (lines.isEmpty()) continue
            // 一句话类的放在同一行，列表类的标题单独一行
            if (lines.size == 1 && field.type in INLINE) appendLine("【${field.label}】${lines.single()}")
            else { appendLine("【${field.label}】"); lines.forEach(::appendLine) }
        }
    }.trimEnd()

    private val INLINE = setOf(FieldType.Text, FieldType.Paragraph, FieldType.Number, FieldType.Rating, FieldType.Tags)

    private fun lines(field: SkillField, value: FieldValue): List<String> = when (field.type) {
        FieldType.Text, FieldType.Paragraph -> listOfNotNull(value.text?.trim()?.takeIf { it.isNotEmpty() })
        FieldType.Bullets -> value.items.filter { it.isNotBlank() }.map { "· ${it.trim()}" }
        FieldType.Checklist -> value.items.mapIndexedNotNull { i, item ->
            item.takeIf { it.isNotBlank() }?.let { (if (value.checked.getOrNull(i) == true) "☑ " else "☐ ") + it.trim() }
        }
        FieldType.Quotes -> value.quotes.filter { it.text.isNotBlank() }.map { "· ${app.resound.OutputTime().moment(it.at)} ${it.speaker}：“${it.text.trim()}”" }
        FieldType.Number -> listOfNotNull(value.number?.let { n ->
            (if (n == n.roundToLong().toDouble()) n.roundToLong().toString() else "%.1f".format(n)) + (value.unit?.let { " $it" } ?: "")
        })
        FieldType.Tags -> value.items.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { listOf(it.joinToString("、")) } ?: emptyList()
        FieldType.Rating -> listOfNotNull(value.rating?.coerceIn(1, 5)?.let { "★".repeat(it) + "☆".repeat(5 - it) })
    }
}
