package app.resound.search

import androidx.room.RoomDatabase
import app.resound.data.readSql

/** 首页“搜索记录”的一条结果：原话或整理出的事件。 */
data class SearchHit(val kind: Kind, val id: Long, val at: Long, val text: String,
                     val speaker: String? = null, val segmentId: Long? = null) {
    enum class Kind { Line, Event }
}

/**
 * 按关键词查原话和事件（议题 #34）。复用已有全文索引；多个词之间是“都要包含”。
 * 索引按两字切分，可能把不连续的两段拼成命中，所以查出来后再按原文逐词核对一遍。
 */
object RecordSearchRules {
    const val LIMIT = 100
    private const val FETCH = 300

    fun words(query: String): List<String> =
        query.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(8)

    /** 单个汉字切不出两字词，用 LIKE 兜底；其余走全文索引。 */
    private fun clause(word: String, column: String, fts: String, args: MutableList<Any>): String {
        val tokens = SearchTokens.split(word)
        val singleHan = tokens.any { it.length == 1 && it[0].code >= 0x3400 }
        return if (tokens.isNotEmpty() && !singleHan) {
            args += tokens.joinToString(" ") { "\"$it\"" }
            "id IN (SELECT rowid FROM $fts WHERE $fts MATCH ?)"
        } else {
            args += "%" + word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            "$column LIKE ? ESCAPE '\\'"
        }
    }

    fun lineQuery(words: List<String>): Pair<String, List<Any>> {
        val args = mutableListOf<Any>()
        val where = words.joinToString(" AND ") { clause(it, "text", "lines_fts", args) }
        return "SELECT id, startAt, text, speakerName, segmentId FROM transcript_lines WHERE $where " +
            "ORDER BY startAt DESC, id DESC LIMIT $FETCH" to args
    }

    fun eventQuery(words: List<String>): Pair<String, List<Any>> {
        val args = mutableListOf<Any>()
        val where = words.joinToString(" AND ") { clause(it, "title || ' ' || summary", "events_fts", args) }
        return "SELECT id, startAt, title, summary FROM memory_events WHERE deleted = 0 AND $where " +
            "ORDER BY startAt DESC, id DESC LIMIT $FETCH" to args
    }

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

    /** 两类结果按时间倒序合在一起，最多 [LIMIT] 条。 */
    fun merge(lines: List<SearchHit>, events: List<SearchHit>): List<SearchHit> =
        (lines + events).sortedWith(compareByDescending<SearchHit> { it.at }.thenBy { it.kind }).take(LIMIT)
}

class RecordSearch(private val db: RoomDatabase) {
    suspend fun search(query: String): List<SearchHit> {
        val words = RecordSearchRules.words(query)
        if (words.isEmpty()) return emptyList()
        val (lineSql, lineArgs) = RecordSearchRules.lineQuery(words)
        val lines = db.readSql(lineSql, lineArgs.toTypedArray()) { s ->
            buildList {
                while (s.step()) {
                    val text = s.getText(2)
                    if (!RecordSearchRules.contains(text, words)) continue
                    add(SearchHit(SearchHit.Kind.Line, s.getLong(0), s.getLong(1), RecordSearchRules.snippet(text, words),
                        if (s.isNull(3)) null else s.getText(3), s.getLong(4)))
                }
            }
        }
        val (eventSql, eventArgs) = RecordSearchRules.eventQuery(words)
        val events = db.readSql(eventSql, eventArgs.toTypedArray()) { s ->
            buildList {
                while (s.step()) {
                    val title = s.getText(2); val summary = s.getText(3)
                    if (!RecordSearchRules.contains("$title $summary", words)) continue
                    val body = if (RecordSearchRules.contains(title, words)) title else "$title：" + RecordSearchRules.snippet(summary, words)
                    add(SearchHit(SearchHit.Kind.Event, s.getLong(0), s.getLong(1), body))
                }
            }
        }
        return RecordSearchRules.merge(lines, events)
    }
}
