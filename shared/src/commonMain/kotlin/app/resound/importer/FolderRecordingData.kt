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
data class FolderRoundCounts(val total: Int, val remaining: Int)

private const val IN_RANGE = "SELECT * FROM folder_recordings WHERE startAt >= :start AND startAt < :end ORDER BY startAt DESC, id DESC"
private const val FIRST_IMPORT = "SELECT * FROM folder_recordings WHERE importRecordId = :id ORDER BY startAt DESC, id DESC LIMIT 1"

@Dao
interface FolderRecordingDao {
    @Query("SELECT * FROM folder_recordings ORDER BY startAt DESC, id DESC") fun observe(): Flow<List<FolderRecordingRow>>
    @Query("SELECT * FROM folder_recordings ORDER BY startAt DESC, id DESC") suspend fun all(): List<FolderRecordingRow>
    @Query("SELECT * FROM folder_recordings WHERE id = :id") suspend fun get(id: Long): FolderRecordingRow?
    @Query("""SELECT COUNT(*) AS total, COUNT(CASE WHEN r.status IS NULL OR r.status != 'Done' THEN 1 END) AS remaining
        FROM folder_recordings f LEFT JOIN import_records r ON r.id = f.importRecordId
        WHERE f.backfill = 1 AND f.notified = 0""")
    fun roundCounts(): Flow<FolderRoundCounts>
    @Query(IN_RANGE) fun observeBetween(start: Long, end: Long): Flow<List<FolderRecordingRow>>
    @Query(IN_RANGE) suspend fun between(start: Long, end: Long): List<FolderRecordingRow>
    @Query(FIRST_IMPORT) fun observeImport(id: Long): Flow<FolderRecordingRow?>
    @Query(FIRST_IMPORT) suspend fun byImport(id: Long): FolderRecordingRow?
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
}

object FolderRecordingSchema {
    fun create(db: SQLiteConnection) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `folder_recordings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `folderId` TEXT NOT NULL, `documentUri` TEXT NOT NULL, `fingerprint` TEXT, `fileName` TEXT NOT NULL, `size` INTEGER NOT NULL, `modified` INTEGER NOT NULL, `startAt` INTEGER NOT NULL, `durationMillis` INTEGER NOT NULL, `number` TEXT, `partyName` TEXT, `direction` TEXT, `state` TEXT NOT NULL, `importRecordId` INTEGER, `createdAt` INTEGER NOT NULL, `kind` TEXT NOT NULL, `pendingId` TEXT, `backfill` INTEGER NOT NULL, `notified` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_folder_recordings_startAt` ON `folder_recordings` (`startAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_folder_recordings_number` ON `folder_recordings` (`number`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_folder_recordings_folderId_documentUri` ON `folder_recordings` (`folderId`, `documentUri`)")
        install(db)
    }
    fun install(db: SQLiteConnection) {
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS folder_import_deleted AFTER DELETE ON import_records BEGIN
            UPDATE folder_recordings SET importRecordId = NULL, state = 'Listed', pendingId = NULL,
                backfill = 0, notified = 1 WHERE importRecordId = OLD.id; END""")
    }
}
