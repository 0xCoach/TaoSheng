package app.resound.importer

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import app.resound.data.SegmentEntity
import app.resound.data.TranscriptLineEntity
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "import_records", indices = [Index(value = ["pendingId"], unique = true), Index("startAt")])
data class ImportRecordRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pendingId: String, val fileName: String, val kind: String,
    val startAt: Long, val endAt: Long, val byChannel: Boolean,
    val status: String = "Transcribing", val error: String? = null, val cardId: Long? = null,
    val ingestComplete: Boolean = false, val revision: Long = 0,
    /** 区分“转写失败后自动恢复”和“纪要失败，等待用户重试”。 */
    val summaryRequested: Boolean = true, val speakersReady: Boolean = false,
)

data class ImportRecordWithSegments(@Embedded val row: ImportRecordRow,
    @Relation(parentColumn = "id", entityColumn = "importRecordId") val segments: List<SegmentEntity>) {
    fun record() = ImportRecord(row.id, row.fileName, ImportKind.valueOf(row.kind), row.startAt, row.endAt,
        ImportRecordStatus.valueOf(row.status), row.error, row.cardId,
        segments.sortedWith(compareBy({ it.startAt }, { it.importChannel }, { it.id })).map { it.id }, row.byChannel)
}

@Dao
abstract class ImportRecordDao {
    @Query("UPDATE memory_items SET eventId = (SELECT eventId FROM transcript_lines WHERE id = memory_items.lineId) WHERE eventId IS NULL AND lineId IN (SELECT l.id FROM transcript_lines l JOIN segments s ON s.id = l.segmentId WHERE s.importRecordId = :id)")
    abstract suspend fun attachTodoEvents(id: Long)
    @Query("SELECT id FROM import_records") abstract suspend fun ids(): List<Long>
    @Query("SELECT * FROM memory_items WHERE category = '待办' AND lineId IN (SELECT l.id FROM transcript_lines l JOIN segments s ON s.id = l.segmentId WHERE s.importRecordId = :id) ORDER BY id")
    abstract suspend fun todos(id: Long): List<app.resound.data.MemoryItemEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insert(row: ImportRecordRow): Long
    @Update abstract suspend fun update(row: ImportRecordRow)
    @Query("SELECT * FROM import_records WHERE id = :id") abstract suspend fun get(id: Long): ImportRecordRow?
    @Query("SELECT * FROM import_records WHERE pendingId = :id") abstract suspend fun byPending(id: String): ImportRecordRow?
    @Transaction @Query("SELECT * FROM import_records ORDER BY startAt, id")
    abstract fun observe(): Flow<List<ImportRecordWithSegments>>
    @Transaction @Query("SELECT * FROM import_records WHERE id = :id")
    abstract fun observe(id: Long): Flow<ImportRecordWithSegments?>
    @Transaction @Query("SELECT * FROM import_records WHERE startAt < :end AND endAt > :start ORDER BY startAt, id")
    abstract fun day(start: Long, end: Long): Flow<List<ImportRecordWithSegments>>
    @Query("SELECT * FROM import_records WHERE ingestComplete = 1 AND summaryRequested = 1 ORDER BY id")
    abstract suspend fun pending(): List<ImportRecordRow>
    @Query("SELECT * FROM segments WHERE importRecordId = :id ORDER BY startAt, importChannel, id")
    abstract suspend fun segments(id: Long): List<SegmentEntity>
    @Query("SELECT l.* FROM transcript_lines l JOIN segments s ON s.id = l.segmentId WHERE s.importRecordId = :id ORDER BY l.startAt, s.importChannel, l.id")
    abstract suspend fun lines(id: Long): List<TranscriptLineEntity>
    @Query("DELETE FROM skill_cards WHERE id = :id") abstract suspend fun deleteCard(id: Long)
    @Query("DELETE FROM import_records WHERE id = :id") abstract suspend fun delete(id: Long)
    @Update abstract suspend fun line(row: TranscriptLineEntity)

    @Transaction open suspend fun invalidate(id: Long) {
        val old = get(id) ?: return
        old.cardId?.let { deleteCard(it) }
        if (segments(id).isEmpty() && old.ingestComplete) delete(id)
        else update(old.copy(cardId = null, status = "Transcribing", error = null,
            revision = old.revision + 1, summaryRequested = true))
    }
}

/** 覆盖失败段删除、隐私删除等所有 SQL 删段入口；同事务撤销包含已删原话的纪要。 */
object ImportRecordSchema {
    fun install(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS import_segment_deleted AFTER DELETE ON segments
            WHEN OLD.importRecordId IS NOT NULL BEGIN
            DELETE FROM skill_cards WHERE id = (SELECT cardId FROM import_records WHERE id = OLD.importRecordId);
            UPDATE import_records SET cardId = NULL, revision = revision + 1, status = 'Transcribing', error = NULL,
                summaryRequested = 1 WHERE id = OLD.importRecordId;
            DELETE FROM import_records WHERE id = OLD.importRecordId AND ingestComplete = 1
                AND NOT EXISTS (SELECT 1 FROM segments WHERE importRecordId = OLD.importRecordId);
            END""")
    }
    val callback = object : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) = install(db)
    }
}
