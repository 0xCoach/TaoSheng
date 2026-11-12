package app.resound.topic.core

import app.resound.skills.*

/** 保留分块中的事实与出处，不让最后一轮摘要删除行动项；相互矛盾的条目不靠字符串相似度合并。 */
object TopicFactMerge {
    fun values(fields: List<SkillField>, current: Map<String, FieldValue>, leaves: List<Map<String, FieldValue>>): Map<String, FieldValue> {
        val result = current.toMutableMap()
        for (field in fields) {
            val parts = leaves.mapNotNull { it[field.key] }
            val old = result[field.key] ?: FieldValue()
            result[field.key] = when (field.type) {
                FieldType.Paragraph -> old.copy(text = parts.mapNotNull { it.text }.distinct().joinToString("\n\n").ifBlank { old.text.orEmpty() })
                FieldType.Bullets, FieldType.Tags, FieldType.Checklist -> {
                    val items = parts.flatMap { it.items }.distinct()
                    old.copy(items = items, checked = if (field.type == FieldType.Checklist) items.map { false } else emptyList())
                }
                FieldType.Quotes -> old.copy(quotes = parts.flatMap { it.quotes }.distinctBy { it.lineId })
                else -> old
            }
        }
        return result
    }
}
