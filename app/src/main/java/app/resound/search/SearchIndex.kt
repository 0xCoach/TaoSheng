package app.resound.search

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.Locale

@Fts4 @Entity(tableName = "lines_fts")
data class LineFts(@PrimaryKey @ColumnInfo(name = "rowid") val id: Long, val text: String)
@Fts4 @Entity(tableName = "events_fts")
data class EventFts(@PrimaryKey @ColumnInfo(name = "rowid") val id: Long, val title: String, val summary: String, val points: String)
@Fts4 @Entity(tableName = "items_fts")
data class ItemFts(@PrimaryKey @ColumnInfo(name = "rowid") val id: Long, val content: String)

/** Kotlin 查询和 SQL 触发器使用同一规则；标点不进入 MATCH 语法。 */
object SearchTokens {
    private fun han(c: Char) = c.code in 0x3400..0x4dbf || c.code in 0x4e00..0x9fff || c.code in 0xf900..0xfaff
    fun split(text: String): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val start = i
            when {
                han(text[i]) -> {
                    while (i < text.length && han(text[i])) i++
                    val run = text.substring(start, i)
                    result += if (run.length == 1) listOf(run) else run.windowed(2)
                }
                text[i] in 'a'..'z' || text[i] in 'A'..'Z' || text[i] in '0'..'9' -> {
                    while (i < text.length && text[i].let { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) i++
                    result += text.substring(start, i).lowercase(Locale.ROOT)
                }
                else -> i++
            }
        }
        return result
    }
    fun match(words: List<String>) = words.map { split(it) }.filter { it.isNotEmpty() }
        .joinToString(" OR ") { tokens -> tokens.joinToString(" AND ", "(", ")") { "\"$it\"" } }

    /** 不依赖 Android 自定义 SQLite 函数，迁移和所有写入路径均可用。 */
    fun sql(value: String): String {
        fun han(c: String) = "(unicode($c) BETWEEN 13312 AND 19903 OR unicode($c) BETWEEN 19968 AND 40959 OR unicode($c) BETWEEN 63744 AND 64255)"
        val c = "substr(s,i,1)"; val next = "substr(s,i+1,1)"; val prev = "substr(s,i-1,1)"
        return """(WITH RECURSIVE scan(s,i,word,token) AS (
            SELECT lower($value),0,'','' UNION ALL
            SELECT s,i+1,CASE WHEN substr(s,i+1,1) GLOB '[a-z0-9]' THEN word||substr(s,i+1,1) ELSE '' END,
              CASE WHEN $c GLOB '[a-z0-9]' AND $next NOT GLOB '[a-z0-9]' THEN word
                   WHEN ${han(c)} AND ${han(next)} THEN $c||$next
                   WHEN ${han(c)} AND NOT coalesce(${han(prev)},0) AND NOT coalesce(${han(next)},0) THEN $c
                   ELSE '' END FROM scan WHERE i<=length(s))
            SELECT coalesce(group_concat(token,' '),'') FROM scan WHERE token!='')"""
    }
}

object SearchIndex {
    val callback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) { install(db, backfill = false) }
        // Room 会在迁移前移除 room_fts_content_sync_ 前缀触发器；本项目触发器使用独立前缀。
    }
    fun install(db: SupportSQLiteDatabase, backfill: Boolean) {
        data class Index(val table: String, val fts: String, val columns: Map<String, String>, val kind: String)
        val indices = listOf(
            Index("transcript_lines", "lines_fts", mapOf("text" to "text"), "line"),
            Index("memory_events", "events_fts", mapOf("title" to "title", "summary" to "summary", "points" to "pointsJson"), "event"),
            Index("memory_items", "items_fts", mapOf("content" to "content"), "item"))
        for (index in indices) {
            val (table, fts, columns, kind) = index
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS $fts USING FTS4(${columns.keys.joinToString { "`$it` TEXT NOT NULL" }})")
            fun insert(prefix: String) = "INSERT INTO $fts(rowid,${columns.keys.joinToString()}) SELECT ${prefix}id,${columns.values.joinToString { SearchTokens.sql(prefix + it) }}"
            val invalidate = if (kind == "line") "" else "DELETE FROM embeddings WHERE kind='$kind' AND refId=old.id;"
            db.execSQL("CREATE TRIGGER IF NOT EXISTS search_${kind}_insert AFTER INSERT ON $table BEGIN ${insert("new.")}; END")
            val watched = columns.values.toMutableList().apply { if (kind == "item") add("category") }.joinToString()
            db.execSQL("CREATE TRIGGER IF NOT EXISTS search_${kind}_update AFTER UPDATE OF $watched ON $table BEGIN DELETE FROM $fts WHERE rowid=old.id; ${insert("new.")}; $invalidate END")
            db.execSQL("CREATE TRIGGER IF NOT EXISTS search_${kind}_delete AFTER DELETE ON $table BEGIN DELETE FROM $fts WHERE rowid=old.id; $invalidate END")
            if (backfill) db.execSQL("${insert("")} FROM $table")
        }
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS search_event_hidden AFTER UPDATE OF deleted ON memory_events
            WHEN new.deleted=1 BEGIN DELETE FROM embeddings WHERE (kind='event' AND refId=new.id)
            OR (kind='item' AND refId IN (SELECT id FROM memory_items WHERE eventId=new.id)); END""")
    }
}
