package app.resound.search

import androidx.room.*
import app.resound.data.CaptureDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

@Entity(tableName = "embeddings", primaryKeys = ["kind", "refId"])
data class EmbeddingEntity(val kind: String, val refId: Long, val model: String, val dim: Int, val vector: ByteArray, val updatedAt: Long)

interface Embedder {
    val model: String
    fun ready(): Boolean
    fun embed(text: String, query: Boolean = false): FloatArray
}

object Vectors {
    fun normalize(values: FloatArray): FloatArray {
        require(values.isNotEmpty() && values.all { it.isFinite() })
        val norm = sqrt(values.sumOf { it.toDouble() * it })
        require(norm > 0)
        return FloatArray(values.size) { (values[it] / norm).toFloat() }
    }
    fun encode(values: FloatArray): ByteArray = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
        values.forEach { putFloat(it) }
    }.array()
    fun decode(bytes: ByteArray, dim: Int): FloatArray {
        require(dim > 0 && bytes.size == dim * 4)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(dim) { buffer.float }
    }
    fun cosine(a: FloatArray, b: FloatArray): Double = if (a.size != b.size) Double.NEGATIVE_INFINITY
        else a.indices.sumOf { a[it].toDouble() * b[it] }
    /** 两路等权 RRF，稳定去重；仅接收各路前 20 条。 */
    fun <T> merge(keyword: List<T>, semantic: List<T>): List<T> {
        val scores = linkedMapOf<T, Double>()
        for (list in listOf(keyword, semantic)) list.distinct().take(20).forEachIndexed { i, id ->
            scores[id] = (scores[id] ?: 0.0) + 1.0 / (60 + i + 1)
        }
        return scores.keys.sortedByDescending { scores[it] }.take(10)
    }
}

class EmbeddingStore(private val db: CaptureDatabase) {
    data class Document(val kind: String, val id: Long, val text: String)
    private val documents = """SELECT 'event' AS kind,id,title||char(10)||summary||char(10)||pointsJson AS text
        FROM memory_events WHERE deleted=0 UNION ALL
        SELECT 'item',i.id,i.category||char(10)||i.content FROM memory_items i JOIN memory_events e ON e.id=i.eventId WHERE i.deleted=0 AND e.deleted=0"""
    fun pending(model: String): List<Document> = db.query(SimpleSQLiteQuery("""SELECT d.* FROM ($documents) d
        LEFT JOIN embeddings v ON v.kind=d.kind AND v.refId=d.id AND v.model=? WHERE v.refId IS NULL LIMIT 64""", arrayOf(model))).use { c ->
        buildList { while (c.moveToNext()) add(Document(c.getString(0), c.getLong(1), c.getString(2))) }
    }
    fun save(document: Document, model: String, vector: FloatArray) {
        // 算向量期间内容可能变化或被删除，只有快照仍一致才落库。
        val normalized = Vectors.normalize(vector)
        db.openHelper.writableDatabase.execSQL("""INSERT OR REPLACE INTO embeddings(kind,refId,model,dim,vector,updatedAt)
            SELECT kind,id,?,?,?,? FROM ($documents) WHERE kind=? AND id=? AND text=?""",
            arrayOf(model, normalized.size, Vectors.encode(normalized), System.currentTimeMillis(), document.kind, document.id, document.text))
    }
}
