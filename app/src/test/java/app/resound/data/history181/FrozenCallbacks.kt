// 卡 181 历史夹具，由 tools/trigger-audit-181/freeze_history.py 从 git 原文提取。
// 仅改 package / imports 和历史建库类型/回调基类；SQL 原文不变，不跟随产品代码更新。
package app.resound.data.history181

import android.database.sqlite.SQLiteDatabase

// a577273:search/SearchIndex.kt
object SearchTokens {
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

// a577273:search/SearchIndex.kt
object SearchIndex {
    val callback = object : HistoricalCallback() {
        override fun onCreate(db: SQLiteDatabase) { install(db, backfill = false) }
        // Room 会在迁移前移除 room_fts_content_sync_ 前缀触发器；本项目触发器使用独立前缀。
    }
    fun install(db: SQLiteDatabase, backfill: Boolean) {
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

// 52b9103:importer/ImportRecordDao.kt
object ImportRecordSchema {
    fun install(db: SQLiteDatabase) {
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS import_segment_deleted AFTER DELETE ON segments
            WHEN OLD.importRecordId IS NOT NULL BEGIN
            DELETE FROM skill_cards WHERE id = (SELECT cardId FROM import_records WHERE id = OLD.importRecordId);
            UPDATE import_records SET cardId = NULL, revision = revision + 1, status = 'Transcribing', error = NULL,
                summaryRequested = 1 WHERE id = OLD.importRecordId;
            DELETE FROM import_records WHERE id = OLD.importRecordId AND ingestComplete = 1
                AND NOT EXISTS (SELECT 1 FROM segments WHERE importRecordId = OLD.importRecordId);
            END""")
    }
    val callback = object : HistoricalCallback() {
        override fun onOpen(db: SQLiteDatabase) = install(db)
    }
}

// 0bbc36e:things/ThingData.kt
internal object ThingSchema {
    fun create(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS things (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, aliasesJson TEXT NOT NULL, owner TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS thing_records (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, thingId INTEGER, name TEXT NOT NULL, action TEXT NOT NULL, place TEXT, who TEXT, owner TEXT NOT NULL, at INTEGER NOT NULL, sourceLineId INTEGER, sourceText TEXT, status TEXT NOT NULL, origin TEXT NOT NULL, explicit INTEGER NOT NULL, sameAsId INTEGER, createdAt INTEGER NOT NULL, acceptedAt INTEGER, aliasesJson TEXT NOT NULL)")
        listOf("thingId", "sourceLineId", "at", "status").forEach { db.execSQL("CREATE INDEX IF NOT EXISTS index_thing_records_$it ON thing_records($it)") }
        db.execSQL("CREATE TABLE IF NOT EXISTS thing_watch_state (slot INTEGER NOT NULL PRIMARY KEY, watermark INTEGER NOT NULL, enabled INTEGER NOT NULL, revision INTEGER NOT NULL, failedEnd INTEGER, failures INTEGER NOT NULL)")
        install(db)
    }
    val callback = object : HistoricalCallback() {
        override fun onCreate(db: SQLiteDatabase) = install(db)
    }
    fun install(db: SQLiteDatabase) {
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS things_line_deleted BEFORE DELETE ON transcript_lines BEGIN
            DELETE FROM thing_records WHERE sourceLineId = OLD.id AND status = 'Pending';
            UPDATE thing_records SET sourceLineId = NULL WHERE sourceLineId = OLD.id;
            END""")
        // 事件软删除也等同于依据消失；备份恢复与隐私删除使用相同规则。
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS things_event_hidden AFTER UPDATE OF deleted ON memory_events WHEN NEW.deleted = 1 BEGIN
            DELETE FROM thing_records WHERE status = 'Pending' AND sourceLineId IN (SELECT id FROM transcript_lines WHERE eventId = NEW.id);
            UPDATE thing_records SET sourceLineId = NULL WHERE sourceLineId IN (SELECT id FROM transcript_lines WHERE eventId = NEW.id);
            END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS things_event_deleted BEFORE DELETE ON memory_events BEGIN
            DELETE FROM thing_records WHERE status = 'Pending' AND sourceLineId IN (SELECT id FROM transcript_lines WHERE eventId = OLD.id);
            UPDATE thing_records SET sourceLineId = NULL WHERE sourceLineId IN (SELECT id FROM transcript_lines WHERE eventId = OLD.id);
            END""")
    }
}

// 774263c:data/PendingDigest.kt
internal object PendingDigestSchema {
    fun create(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS digest_pending (id TEXT NOT NULL PRIMARY KEY, lineIds TEXT NOT NULL, content TEXT NOT NULL, day TEXT NOT NULL, promptTokens INTEGER NOT NULL, completionTokens INTEGER NOT NULL, thingRevision INTEGER)")
        install(db)
    }
    private fun install(db: SQLiteDatabase) {
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS digest_pending_source_deleted AFTER DELETE ON transcript_lines BEGIN
            DELETE FROM digest_pending WHERE instr(',' || lineIds || ',', ',' || OLD.id || ',') > 0;
        END""")
    }
    val callback = object : HistoricalCallback() {
        override fun onOpen(db: SQLiteDatabase) = install(db)
    }
}
