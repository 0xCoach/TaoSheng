package app.resound.search

import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteStatement
import app.resound.data.readSql
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** 五个独立配额；Other 中用 source 区分跳转目标，不能单独用 id 作为列表键。 */
enum class SearchKind { Line, Event, Todo, Memory, Other }
enum class SearchSource(val kind: SearchKind) {
    Line(SearchKind.Line), Event(SearchKind.Event), Todo(SearchKind.Todo), Memory(SearchKind.Memory),
    Thing(SearchKind.Other), ThingRecord(SearchKind.Other), Topic(SearchKind.Other),
    Review(SearchKind.Other), AskMessage(SearchKind.Other), AskConversation(SearchKind.Other),
}

data class ExpandedSearchHit(
    val source: SearchSource, val id: Long, val at: Long, val title: String, val text: String,
    val eventId: Long? = null, val segmentId: Long? = null, val speaker: String? = null,
    val itemCategory: String? = null, val status: String? = null,
    val thingId: Long? = null, val reviewDate: String? = null, val conversationId: Long? = null,
) {
    val kind: SearchKind get() = source.kind
    val completed: Boolean get() = kind == SearchKind.Todo && status == "Done"
}

/** 时间为 epoch 毫秒，[startAt, endAt)；offsets 是各类别的已加载条数。换关键词/时间须清零。 */
data class ExpandedSearchRequest(
    val query: String, val kind: SearchKind? = null, val startAt: Long? = null, val endAt: Long? = null,
    val offsets: Map<SearchKind, Int> = emptyMap(), val pageSize: Int = 100,
) {
    init {
        require(pageSize in 1..100)
        require(offsets.values.all { it >= 0 && it <= Int.MAX_VALUE - 101 })
        require(startAt == null || endAt == null || startAt <= endAt)
    }
}

/** count 为整个时间范围内的命中数，最多报 101；101 表示 100+。nextOffset=null 表示到底。 */
data class SearchCategoryPage(val hits: List<ExpandedSearchHit>, val count: Int, val nextOffset: Int?) {
    val countIsLowerBound: Boolean get() = count == 101
}
data class ExpandedSearchResult(val categories: Map<SearchKind, SearchCategoryPage>)

internal data class SearchSql(val sql: String, val args: List<Any>)
internal data class SearchRow(val hit: ExpandedSearchHit, val body: String)

/** SQL 只拼固定标识符；用户文字、时间、游标全部参数绑定。 */
internal object ExpandedSearchRules {
    private const val TASKS = "('待办','约定','备忘')"
    const val BATCH = 256
    fun like(word: String) = "%" + word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    private fun strings(json: String): String = try {
        Json.parseToJsonElement(json).jsonArray.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.joinToString(" ")
    } catch (_: IllegalArgumentException) { "" }
    fun reviewText(json: String): String = try {
        Json.parseToJsonElement(json).jsonArray.joinToString(" ") { section ->
            val obj = section.jsonObject
            val lines = obj["lines"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            if (lines.isEmpty()) "" else (listOfNotNull((obj["title"] as? JsonPrimitive)?.content) + lines).joinToString(" ")
        }
    } catch (_: IllegalArgumentException) { "" }

    fun query(source: SearchSource, words: List<String>, request: ExpandedSearchRequest, before: Pair<Long, Long>?): SearchSql {
        // 统一列：id, at, title, body, json, eventId, segmentId, speaker, category, status, thingId, date, conversationId
        val nil = "NULL"
        var title = "''"; var body = "''"; var json = "''"; var event = nil; var segment = nil
        var speaker = nil; var category = nil; var status = nil; var thing = nil; var date = nil; var conversation = nil
        val table: String; val at: String; var visible = "1"; var fts: String? = null
        when (source) {
            SearchSource.Line -> {
                table = "transcript_lines"; at = "startAt"; body = "text"; event = "eventId"; segment = "segmentId"; speaker = "speakerName"; fts = "lines_fts"
                visible = "NOT EXISTS (SELECT 1 FROM memory_events e WHERE e.id = transcript_lines.eventId AND e.deleted = 1)"
            }
            SearchSource.Event -> {
                table = "memory_events"; at = "startAt"; title = "title"; body = "summary"; json = "pointsJson"; event = "id"; visible = "deleted = 0"; fts = "events_fts"
            }
            SearchSource.Todo, SearchSource.Memory -> {
                table = "memory_items"; at = "createdAt"; body = "content"; event = "eventId"; category = "category"; status = "status"; fts = "items_fts"
                visible = "deleted = 0 AND mergedInto IS NULL AND status != 'Ignored' AND " +
                    "NOT EXISTS (SELECT 1 FROM memory_events e WHERE e.id = memory_items.eventId AND e.deleted = 1) AND " +
                    if (source == SearchSource.Todo) "category IN $TASKS" else "category NOT IN $TASKS"
            }
            SearchSource.Thing -> {
                table = "things"; at = "updatedAt"; title = "name"; json = "aliasesJson"; thing = "id"
                visible = "deleted = 0 AND EXISTS (SELECT 1 FROM thing_records r WHERE r.thingId = things.id AND r.status = 'Accepted')"
            }
            SearchSource.ThingRecord -> {
                table = "thing_records"; at = "at"; title = "name"; thing = "thingId"; status = "status"
                body = "(CASE action WHEN 'Placed' THEN '放在' WHEN 'Lent' THEN '借给' WHEN 'Returned' THEN '还回来了' WHEN 'TakenAway' THEN '带走了' ELSE action END) || ' ' || coalesce(place,'') || ' ' || coalesce(who,'') || ' ' || coalesce(sourceText,'')"
                visible = "status = 'Pending' OR (status = 'Accepted' AND EXISTS (SELECT 1 FROM things t WHERE t.id = thing_records.thingId AND t.deleted = 0))"
            }
            SearchSource.Topic -> { table = "topics"; at = "createdAt"; title = "coalesce(name,'')"; event = "eventId"; status = "status" }
            SearchSource.Review -> {
                table = "reviews"; at = "generatedAt"; body = "brief"; json = "sectionsJson"; date = "startDate"
                visible = "type = 'daily' AND startDate = endDate"
            }
            SearchSource.AskMessage -> {
                table = "ask_messages"; at = "at"; body = "text"; conversation = "conversationId"
                visible = "EXISTS (SELECT 1 FROM ask_conversations c WHERE c.id = ask_messages.conversationId)"
            }
            SearchSource.AskConversation -> {
                table = "ask_conversations"; at = "createdAt"; title = "title"; conversation = "id"
                visible = "EXISTS (SELECT 1 FROM ask_messages m WHERE m.conversationId = ask_conversations.id)"
            }
        }
        val args = mutableListOf<Any>()
        val conditions = mutableListOf("($visible)")
        for (word in words) {
            val tokens = SearchTokens.split(word)
            if (fts != null && tokens.isNotEmpty() && tokens.none { it.length == 1 && it[0].code >= 0x3400 }) {
                conditions += "id IN (SELECT rowid FROM $fts WHERE $fts MATCH ?)"
                args += tokens.joinToString(" ") { "\"$it\"" }
            } else {
                // JSON 的转义表示不一定含原文字面量；含反斜杠的行放进候选，解码后再核对。
                conditions += "(($title || ' ' || $body || ' ' || $json) LIKE ? ESCAPE '\\'" +
                    (if (json != "''") " OR instr($json, '\\') > 0)" else ")")
                args += like(word)
            }
        }
        request.startAt?.let { conditions += "$at >= ?"; args += it }
        request.endAt?.let { conditions += "$at < ?"; args += it }
        before?.let { (time, id) -> conditions += "($at < ? OR ($at = ? AND id < ?))"; args.addAll(listOf(time, time, id)) }
        return SearchSql("SELECT id, $at, $title, $body, $json, $event, $segment, $speaker, $category, $status, $thing, $date, $conversation FROM $table WHERE ${conditions.joinToString(" AND ")} ORDER BY $at DESC, id DESC LIMIT $BATCH", args)
    }

    fun row(source: SearchSource, s: SQLiteStatement): SearchRow {
        fun number(i: Int) = if (s.isNull(i)) null else s.getLong(i)
        fun text(i: Int) = if (s.isNull(i)) null else s.getText(i)
        val json = s.getText(4)
        val decoded = if (source == SearchSource.Review) reviewText(json) else if (json.isNotEmpty()) strings(json) else ""
        val body = listOf(s.getText(2), s.getText(3), decoded).filter { it.isNotBlank() }.joinToString(" ")
        return SearchRow(ExpandedSearchHit(source, s.getLong(0), s.getLong(1), s.getText(2), "",
            number(5), number(6), text(7), text(8), text(9), number(10), text(11), number(12)), body)
    }

    fun page(rows: List<ExpandedSearchHit>, offset: Int, size: Int): SearchCategoryPage {
        val sorted = rows.sortedWith(compareByDescending<ExpandedSearchHit> { it.at }.thenBy { it.source }.thenByDescending { it.id })
        val hits = sorted.drop(offset).take(size)
        return SearchCategoryPage(hits, sorted.size.coerceAtMost(101), if (sorted.size > offset + hits.size) offset + hits.size else null)
    }
}

/** 分批取候选并在 LIMIT 前核对原文；即使前 300 条都是二元组误命中，也不会截断真命中。 */
internal class ExpandedSearch(private val db: RoomDatabase) {
    suspend fun search(request: ExpandedSearchRequest): ExpandedSearchResult {
        val words = RecordSearchRules.words(request.query)
        val result = linkedMapOf<SearchKind, SearchCategoryPage>()
        for (kind in SearchKind.entries.filter { request.kind == null || it == request.kind }) {
            val offset = request.offsets[kind] ?: 0
            val needed = maxOf(101, offset + request.pageSize + 1)
            val hits = mutableListOf<ExpandedSearchHit>()
            if (words.isNotEmpty()) for (source in SearchSource.entries.filter { it.kind == kind }) {
                var before: Pair<Long, Long>? = null
                var found = 0
                while (found < needed) {
                    currentCoroutineContext().ensureActive()
                    val q = ExpandedSearchRules.query(source, words, request, before)
                    val rows = db.readSql(q.sql, q.args.toTypedArray()) { s -> buildList {
                        while (s.step()) add(ExpandedSearchRules.row(source, s))
                    } }
                    for (row in rows) {
                        if (!RecordSearchRules.contains(row.body, words)) continue
                        hits += row.hit.copy(text = RecordSearchRules.snippet(row.body, words))
                        found++
                        if (found == needed) break
                    }
                    if (rows.size < ExpandedSearchRules.BATCH) break
                    before = rows.last().hit.let { it.at to it.id }
                }
            }
            result[kind] = ExpandedSearchRules.page(hits, offset, request.pageSize)
        }
        return ExpandedSearchResult(result)
    }
}
