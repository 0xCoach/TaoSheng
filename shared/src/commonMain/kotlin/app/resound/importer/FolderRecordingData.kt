package app.resound.importer

import androidx.room.*
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow

@Entity(tableName="folder_recordings", indices=[Index("startAt"), Index("number"), Index(value=["folderId","documentUri"], unique=true)])
data class FolderRecordingRow(
    @PrimaryKey(autoGenerate=true) val id: Long = 0,
    val folderId: String, val documentUri: String, val fingerprint: String? = null,
    val fileName: String, val size: Long, val modified: Long, val startAt: Long, val durationMillis: Long,
    val number: String? = null, val partyName: String? = null, val direction: String? = null,
    val state: String = "Listed", val importRecordId: Long? = null, val createdAt: Long,
    // 恢复、删除文件夹后仍保留类型；以下字段是可恢复队列的凭据，不是展示身份。
    val kind: String, val pendingId: String? = null, val backfill: Boolean = false, val notified: Boolean = false,
)
@Entity(tableName="phone_labels")
data class PhoneLabelRow(@PrimaryKey val number: String, val name: String, val updatedAt: Long)

data class CallDisplayLine(val id: Long, val recordId: Long, val speakerTag: String?, val speakerName: String?,
    val speakerPersonId: Long?, val number: String?, val partyName: String?, val label: String?)

@Dao
interface FolderRecordingDao {
    @Query("SELECT * FROM folder_recordings ORDER BY startAt DESC, id DESC") fun observe(): Flow<List<FolderRecordingRow>>
    @Query("SELECT * FROM folder_recordings ORDER BY startAt DESC, id DESC") suspend fun all(): List<FolderRecordingRow>
    @Query("SELECT * FROM folder_recordings WHERE id = :id") suspend fun get(id: Long): FolderRecordingRow?
    @Query("SELECT * FROM folder_recordings WHERE importRecordId = :id LIMIT 1") suspend fun byImport(id: Long): FolderRecordingRow?
    @Query("SELECT * FROM folder_recordings WHERE folderId = :folder AND documentUri = :uri LIMIT 1") suspend fun find(folder: String, uri: String): FolderRecordingRow?
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insert(row: FolderRecordingRow): Long
    @Update suspend fun update(row: FolderRecordingRow)
    @Query("DELETE FROM folder_recordings WHERE folderId = :id AND state != 'Imported'") suspend fun removeFolder(id: String)
    @Query("UPDATE folder_recordings SET importRecordId = :record, state = 'Imported' WHERE pendingId = :pending") suspend fun attached(pending: String, record: Long)
    @Query("UPDATE folder_recordings SET notified = 1 WHERE folderId = :folder AND backfill = 1") suspend fun notified(folder: String)
    @Query("SELECT l.id FROM transcript_lines l JOIN segments s ON s.id = l.segmentId JOIN folder_recordings f ON f.importRecordId = s.importRecordId WHERE f.backfill = 1 AND f.startAt < :today")
    suspend fun quietLines(today: Long): List<Long>
    @Query("SELECT r.cardId FROM import_records r JOIN folder_recordings f ON f.importRecordId = r.id WHERE f.backfill = 1 AND f.startAt < :today AND r.cardId IS NOT NULL")
    suspend fun quietCards(today: Long): List<Long>
    @Query("SELECT * FROM phone_labels") fun labels(): Flow<List<PhoneLabelRow>>
    @Query("SELECT * FROM phone_labels") suspend fun labelSnapshot(): List<PhoneLabelRow>
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun label(row: PhoneLabelRow)
    @Query("DELETE FROM phone_labels WHERE number = :number") suspend fun deleteLabel(number: String)
    @Query("""SELECT l.id, s.importRecordId AS recordId, l.speakerTag, l.speakerName, l.speakerPersonId,
        f.number, f.partyName, p.name AS label FROM transcript_lines l
        JOIN segments s ON s.id = l.segmentId JOIN folder_recordings f ON f.importRecordId = s.importRecordId
        JOIN import_records r ON r.id = s.importRecordId LEFT JOIN phone_labels p ON p.number = f.number
        WHERE f.kind = 'Call' AND r.kind = 'Call'""")
    fun displayLines(): Flow<List<CallDisplayLine>>
}

object FolderRecordingSchema {
    fun create(db: SQLiteConnection) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `folder_recordings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `folderId` TEXT NOT NULL, `documentUri` TEXT NOT NULL, `fingerprint` TEXT, `fileName` TEXT NOT NULL, `size` INTEGER NOT NULL, `modified` INTEGER NOT NULL, `startAt` INTEGER NOT NULL, `durationMillis` INTEGER NOT NULL, `number` TEXT, `partyName` TEXT, `direction` TEXT, `state` TEXT NOT NULL, `importRecordId` INTEGER, `createdAt` INTEGER NOT NULL, `kind` TEXT NOT NULL, `pendingId` TEXT, `backfill` INTEGER NOT NULL, `notified` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_folder_recordings_startAt` ON `folder_recordings` (`startAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_folder_recordings_number` ON `folder_recordings` (`number`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_folder_recordings_folderId_documentUri` ON `folder_recordings` (`folderId`, `documentUri`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `phone_labels` (`number` TEXT NOT NULL, `name` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`number`))")
        install(db)
    }
    fun install(db: SQLiteConnection) {
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS folder_import_deleted AFTER DELETE ON import_records BEGIN
            UPDATE folder_recordings SET importRecordId = NULL, state = 'Listed', pendingId = NULL,
                backfill = 0, notified = 1 WHERE importRecordId = OLD.id; END""")
    }
}
