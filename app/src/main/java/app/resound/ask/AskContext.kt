package app.resound.ask

import androidx.sqlite.db.SimpleSQLiteQuery
import app.resound.data.CaptureDatabase
import org.json.JSONObject

class AskContextReader(private val db: CaptureDatabase) : app.resound.ask.core.AskContextStore {
    private val core = app.resound.ask.core.AskContextCore(this)
    fun followUp(history: List<AskMessage>) = core.followUp(history)
    fun read(source: AskSource) = core.read(source)

    override fun refresh(source: AskSource): String? {
        val eventQuote = "COALESCE((SELECT group_concat(text,char(10)) FROM (SELECT substr(text,1,900) AS text FROM transcript_lines WHERE eventId=e.id ORDER BY startAt,id LIMIT 8)), '')"
        val sql = when (source.kind) {
            "line" -> """SELECT l.id,l.startAt AS at,COALESCE(l.speakerName,l.speakerTag,'某人') AS title,l.text AS quote
                FROM transcript_lines l WHERE l.id=? AND (l.eventId IS NULL OR EXISTS(SELECT 1 FROM memory_events e WHERE e.id=l.eventId AND e.deleted=0))"""
            "event" -> "SELECT e.id,e.startAt AS at,e.title,e.summary,$eventQuote AS quote FROM memory_events e WHERE e.id=? AND e.deleted=0"
            "item" -> """SELECT i.id,COALESCE(e.startAt,i.createdAt) AS at,i.category AS title,i.content AS summary,i.status,
                COALESCE((SELECT l.text FROM transcript_lines l WHERE l.id=i.lineId),NULLIF($eventQuote,''),i.sourceText,'') AS quote
                FROM memory_items i LEFT JOIN memory_events e ON e.id=i.eventId
                WHERE i.id=? AND i.deleted=0 AND i.mergedInto IS NULL AND (e.id IS NULL OR e.deleted=0)"""
            else -> return null
        }
        return db.query(SimpleSQLiteQuery(sql, arrayOf(source.id))).use { c ->
            if (!c.moveToFirst()) null else JSONObject().apply {
                put("kind", source.kind)
                c.columnNames.forEachIndexed { i, name ->
                    put(name, if (name in setOf("id", "at")) c.getLong(i) else if (c.isNull(i)) "" else c.getString(i).take(if (name == "title") 100 else 900))
                }
            }?.toString()
        }
    }

    override fun record(source: AskSource): app.resound.ask.core.AskContextRecord {
        require(source.kind in setOf("event", "item"))
        // 只信库里的当前内容；不用调用方传来的标题/摘要，以免已删除内容继续被上传。
        val item = source.kind == "item"
        val sql = if (item) """SELECT e.id,COALESCE(e.title,i.category),COALESCE(e.summary,i.sourceText,''),COALESCE(e.startAt,i.createdAt),i.content,i.category FROM memory_items i
            LEFT JOIN memory_events e ON e.id=i.eventId WHERE i.id=? AND i.deleted=0 AND (e.id IS NULL OR e.deleted=0)"""
            else "SELECT id,title,summary,startAt FROM memory_events WHERE id=? AND deleted=0"
        return db.query(SimpleSQLiteQuery(sql, arrayOf(source.id))).use { c ->
            require(c.moveToFirst()) { "这条记录已不存在" }
            app.resound.ask.core.AskContextRecord(if (c.isNull(0)) null else c.getLong(0), c.getLong(3), c.getString(1), c.getString(2),
                if (item) c.getString(4) else null, if (item) c.getString(5) else null)
        }
    }
    override fun lines(eventId: Long?): List<app.resound.ask.core.AskContextLine> =
        db.query(SimpleSQLiteQuery("SELECT id,startAt,text,coalesce(speakerName,speakerTag,'某人'),voiceInputJson FROM transcript_lines WHERE eventId=? ORDER BY startAt,id LIMIT 40", arrayOf(eventId))).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val meta = if (c.isNull(4)) null else app.resound.transcribe.VoiceInputMetadata.read(c.getString(4))?.let {
                        app.resound.transcribe.VoiceInputMetadata.json(it.copy(before = it.before?.take(500), after = it.after?.take(500))).toString()
                    }
                    add(app.resound.ask.core.AskContextLine(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), meta))
                }
            }
        }
}
