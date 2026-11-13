package app.resound.importer

import androidx.room.*

@Entity(tableName = "import_fingerprints")
data class ImportFingerprint(@PrimaryKey val fingerprint: String, val seenAt: Long)

@Entity(tableName = "folder_observations", primaryKeys = ["folderId", "documentId"])
data class FolderObservation(val folderId: String, val documentId: String, val size: Long,
    val modified: Long, val ignored: Boolean, val fingerprint: String? = null)

@Dao
interface FolderImportDao {
    @Query("SELECT EXISTS(SELECT 1 FROM import_fingerprints WHERE fingerprint = :key)")
    suspend fun contains(key: String): Boolean
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun remember(row: ImportFingerprint)
    @Query("SELECT * FROM folder_observations WHERE folderId = :id")
    suspend fun observations(id: String): List<FolderObservation>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun observe(rows: List<FolderObservation>)
    @Query("DELETE FROM folder_observations WHERE folderId = :id") suspend fun clearObservations(id: String)
}
