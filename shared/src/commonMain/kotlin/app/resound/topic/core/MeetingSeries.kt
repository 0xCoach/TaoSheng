package app.resound.topic.core

import app.resound.net.Obj

/** 上次同系列会议安排的、记录里还没有完成迹象的事项。 */
data class MeetingOpenItem(val label: String, val who: String?, val content: String, val dueText: String?)

/**
 * 同系列会议（seriesKey 相同）的跨场跟进资料：把上一场纪要的结论/决定/行动项和未闭环事项
 * 拼成资料区注入下一场纪要生成，让“上次安排的事这次解决了吗”成为模型可见的上下文。
 */
object MeetingSeriesRules {
    private fun bullets(values: Obj?, key: String, limit: Int): List<String> =
        values?.optJSONObject(key)?.optJSONArray("items")
            ?.let { a -> (0 until a.length()).map { a.optString(it) } }
            ?.filter { it.isNotBlank() }?.take(limit) ?: emptyList()

    private fun text(values: Obj?, key: String): String? =
        values?.optJSONObject(key)?.optString("text")?.takeIf { it.isNotBlank() }

    /** 拼“上次同场”资料区；上次没有卡片且没有待跟进项时返回空串（不注入）。 */
    fun previousContext(name: String?, dateLabel: String, cardValuesJson: String?,
                        openItems: List<MeetingOpenItem>, limit: Int = 8): String {
        val values = runCatching { cardValuesJson?.let { Obj(it) } }.getOrNull()
        val lines = buildList {
            add("上次同系列专题${name?.let { "《$it》" }.orEmpty()}（$dateLabel）的纪要与待跟进：")
            text(values, "conclusion")?.let { add("上次结论：$it") }
            bullets(values, "decisions", limit).forEach { add("上次已决定：$it") }
            bullets(values, "actions", limit).forEach { add("上次行动项：$it") }
            if (openItems.isNotEmpty()) {
                add("以下事项上次安排过、记录里还没有完成的迹象；若本场有新进展或已被取消，请在纪要中写明：")
                openItems.take(limit).forEach { i ->
                    add("- ${i.label}${i.who?.let { "（$it）" }.orEmpty()}：${i.content}${i.dueText?.let { "，$it" }.orEmpty()}")
                }
            }
        }
        return if (lines.size > 1) lines.joinToString("\n") else ""
    }
}
