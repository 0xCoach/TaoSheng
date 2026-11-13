package app.resound.search

import androidx.room.*
import app.resound.data.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

interface Embedder {
    val model: String
    fun ready(): Boolean
    fun embed(text: String, query: Boolean = false): FloatArray
}

class EmbeddingStore(private val db: CaptureDatabase) {
    data class Document(val kind: String, val id: Long, val text: String)
    private val documents = """SELECT 'event' AS kind,id,title||char(10)||summary||char(10)||pointsJson AS text
        FROM memory_events WHERE deleted=0 UNION ALL
        SELECT 'item',i.id,i.category||char(10)||i.content FROM memory_items i LEFT JOIN memory_events e ON e.id=i.eventId WHERE i.deleted=0 AND (e.id IS NULL OR e.deleted=0)"""
    suspend fun pending(model: String): List<Document> = db.readSql("""SELECT d.* FROM ($documents) d
        LEFT JOIN embeddings v ON v.kind=d.kind AND v.refId=d.id AND v.model=? WHERE v.refId IS NULL LIMIT 64""", arrayOf(model)) { c ->
        buildList { while (c.step()) add(Document(c.getText(0), c.getLong(1), c.getText(2))) }
    }
    suspend fun save(document: Document, model: String, vector: FloatArray) {
        // 算向量期间内容可能变化或被删除，只有快照仍一致才落库。
        val normalized = Vectors.normalize(vector)
        db.writeSql("""INSERT OR REPLACE INTO embeddings(kind,refId,model,dim,vector,updatedAt)
            SELECT kind,id,?,?,?,? FROM ($documents) WHERE kind=? AND id=? AND text=?""",
            arrayOf(model, normalized.size, Vectors.encode(normalized), System.currentTimeMillis(), document.kind, document.id, document.text))
    }
}
