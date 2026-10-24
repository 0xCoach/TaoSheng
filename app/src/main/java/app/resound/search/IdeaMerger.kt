package app.resound.search

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import app.resound.data.CaptureDatabase

/** 只比较当前模型、向量已就绪的独立灵感；早记录为主，原文和出处始终保留。 */
class IdeaMerger(private val db: CaptureDatabase) {
    private data class Idea(val id: Long, val vector: FloatArray)

    suspend fun merge(model: String) = db.withTransaction {
        val ideas = db.query(SimpleSQLiteQuery("""SELECT i.id,v.vector,v.dim FROM memory_items i
            JOIN memory_events e ON e.id=i.eventId
            JOIN embeddings v ON v.kind='item' AND v.refId=i.id AND v.model=?
            WHERE i.deleted=0 AND e.deleted=0 AND i.category='灵感' AND i.mergedInto IS NULL AND i.noMerge=0 AND i.editedAt IS NULL
            ORDER BY i.createdAt,i.id""", arrayOf(model))).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val vector = runCatching { Vectors.normalize(Vectors.decode(cursor.getBlob(1), cursor.getInt(2))) }.getOrNull()
                    if (vector != null) add(Idea(cursor.getLong(0), vector))
                }
            }
        }
        val roots = mutableListOf<Idea>()
        for (idea in ideas) {
            val closest = roots.map { it to Vectors.cosine(idea.vector, it.vector) }.maxByOrNull { it.second }
            if (closest != null && closest.second >= THRESHOLD) db.memory().mergeInto(idea.id, closest.first.id)
            else roots += idea
        }
    }

    // 同模型合成句对出现 0.860014 的不同想法，按卡 040 微调以降低误并。
    companion object { const val THRESHOLD = 0.88 }
}
