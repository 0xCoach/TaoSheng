package app.resound.marks

import androidx.room.*
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import app.resound.data.CaptureDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "marks", indices = [Index("at"), Index(value = ["topicMarkId"], unique = true)])
data class MarkRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val at: Long,
    val beforeMillis: Long, val afterMillis: Long, val label: String?, val source: String,
    val createdAt: Long, val topicMarkId: Long? = null) {
    @get:Ignore val startAt: Long get() = at - beforeMillis
    @get:Ignore val endAt: Long get() = at + afterMillis
}
@Dao
interface MarkDao {
    @Insert suspend fun insert(row: MarkRow): Long
    @Query("SELECT * FROM marks WHERE id = :id") suspend fun get(id: Long): MarkRow?
    @Query("SELECT * FROM marks WHERE at - beforeMillis < :until AND at + afterMillis > :from ORDER BY at, id")
    suspend fun overlapping(from: Long, until: Long): List<MarkRow>
    @Query("SELECT * FROM marks WHERE at - beforeMillis < :until AND at + afterMillis > :from ORDER BY at, id")
    fun observe(from: Long, until: Long): Flow<List<MarkRow>>
    @Query("SELECT topicId FROM topic_marks WHERE id = :id") suspend fun topicId(id: Long): Long?
    @Query("DELETE FROM marks WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM marks WHERE topicMarkId = :id") suspend fun deleteTopicMark(id: Long)
    @Query("DELETE FROM topic_marks WHERE at >= :from AND at < :until") suspend fun deleteLegacyRange(from: Long, until: Long)
    @Query("DELETE FROM marks WHERE at >= :from AND at < :until") suspend fun deleteRange(from: Long, until: Long)
}
object MarkSchema {
    const val BACKFILL = "INSERT INTO marks (at,beforeMillis,afterMillis,label,source,createdAt,topicMarkId) SELECT t.at,120000,60000,NULL,'Topic',t.at,t.id FROM topic_marks t WHERE NOT EXISTS (SELECT 1 FROM marks m WHERE m.topicMarkId = t.id)"
    const val CLEAN_EMPTY_SEQUENCE = "DELETE FROM sqlite_sequence WHERE name = 'marks' AND seq = 0 AND NOT EXISTS (SELECT 1 FROM marks)"
    fun create(db: SQLiteConnection) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `marks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at` INTEGER NOT NULL, `beforeMillis` INTEGER NOT NULL, `afterMillis` INTEGER NOT NULL, `label` TEXT, `source` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `topicMarkId` INTEGER)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_marks_at` ON `marks` (`at`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_marks_topicMarkId` ON `marks` (`topicMarkId`)")
        db.execSQL(BACKFILL)
        db.execSQL(CLEAN_EMPTY_SEQUENCE)
    }
}
/** 界面传实际音源起点和相对于该起点的播放毫秒数，不使用墙上时钟替代回放位置。 */
class MarkRepository(private val db: CaptureDatabase, private val now: () -> Long, private val range: () -> MarkRange,
    private val undoTopic: suspend (Long, Long) -> Unit) {
    suspend fun recording(label: String? = null): MarkRow = add(now(), "Recording", label)
    suspend fun playback(sourceStartAt: Long, positionMillis: Long, durationMillis: Long, label: String? = null): MarkRow =
        add(MarkRules.playbackAt(sourceStartAt, positionMillis, durationMillis), "Playback", label)
    private suspend fun add(at: Long, source: String, label: String?): MarkRow {
        val row = MarkRules.create(at, range(), label, source, now())
        return row.copy(id = db.marks().insert(row))
    }
    suspend fun undo(id: Long) {
        val row = db.marks().get(id) ?: return
        val topicMark = row.topicMarkId
        val topicId = topicMark?.let { db.marks().topicId(it) }
        if (topicMark != null && topicId != null) undoTopic(topicId, topicMark)
        else db.marks().delete(id)
    }
    fun observeDay(from: Long, until: Long) = db.marks().observe(from, until)
    suspend fun forSegment(id: Long): List<MarkRow> = db.transcribe().findSegment(id)?.let { db.marks().overlapping(it.startAt, it.endAt) } ?: emptyList()
    suspend fun forEvent(id: Long): List<MarkRow> = db.memory().event(id)?.let { db.marks().overlapping(it.startAt, it.endAt) } ?: emptyList()
}
