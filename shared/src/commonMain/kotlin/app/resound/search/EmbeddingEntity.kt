package app.resound.search

import androidx.room.*

@Entity(tableName = "embeddings", primaryKeys = ["kind", "refId"])
data class EmbeddingEntity(val kind: String, val refId: Long, val model: String, val dim: Int, val vector: ByteArray, val updatedAt: Long)
