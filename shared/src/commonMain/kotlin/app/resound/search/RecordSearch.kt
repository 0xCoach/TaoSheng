package app.resound.search

import androidx.room.RoomDatabase

/**
 * 搜索的共用文字规则（议题 #34，卡 194 扩展）。多个词之间是“都要包含”。
 * 索引按两字切分，可能把不连续的两段拼成命中，所以查出来后再按原文逐词核对一遍。
 */
object RecordSearchRules {
    fun words(query: String): List<String> =
        query.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(8)

    /** 原文里逐词都出现才算命中（不分大小写）。 */
    fun contains(text: String, words: List<String>) = words.all { text.contains(it, ignoreCase = true) }

    /** 取第一个关键词前后一段，太长时两头加省略号。 */
    fun snippet(text: String, words: List<String>, radius: Int = 36): String {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        val at = words.map { clean.indexOf(it, ignoreCase = true) }.filter { it >= 0 }.minOrNull() ?: 0
        val start = (at - radius).coerceAtLeast(0)
        val end = (at + radius * 2).coerceAtMost(clean.length)
        return (if (start > 0) "…" else "") + clean.substring(start, end) + (if (end < clean.length) "…" else "")
    }
}

/** 记录搜索入口：对话、事件、待办、记忆和其他五类各自计数、时间过滤与分页（卡 194）。 */
class RecordSearch(private val db: RoomDatabase) {
    suspend fun searchAll(request: ExpandedSearchRequest): ExpandedSearchResult = ExpandedSearch(db).search(request)
}
