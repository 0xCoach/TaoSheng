package app.resound.things

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import app.resound.data.TranscriptLineEntity
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "things")
internal data class ThingRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String,
    val aliasesJson: String = "[]", val owner: String, val createdAt: Long, val updatedAt: Long, val deleted: Boolean = false)

@Entity(tableName = "thing_records", indices = [Index("thingId"), Index("sourceLineId"), Index("at"), Index("status")])
internal data class ThingRecordRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val thingId: Long? = null,
    val name: String, val action: String, val place: String?, val who: String?, val owner: String,
    val at: Long, val sourceLineId: Long?, val sourceText: String?, val status: String = "Pending",
    val origin: String = "Live", val explicit: Boolean = false, val sameAsId: Long? = null,
    val createdAt: Long, val acceptedAt: Long? = null, val aliasesJson: String = "[]")

/** 水位与本批结果同事务提交；失败批次的上界固定，重启也不会因新增句子重置失败计数。 */
@Entity(tableName = "thing_watch_state")
internal data class ThingWatchState(@PrimaryKey val slot: Int = 1, val watermark: Long = 0,
    val enabled: Boolean = true, val revision: Long = 0, val failedEnd: Long? = null, val failures: Int = 0)

internal const val THING_ELIGIBLE = """s.sourceId != 'import' AND NOT EXISTS
    (SELECT 1 FROM env_sounds e WHERE e.segmentId = l.segmentId AND e.reportedWrong = 0 AND (e.markMedia = 1 OR e.verdict = 'Media'))
    AND NOT EXISTS (SELECT 1 FROM memory_events e WHERE e.id = l.eventId AND e.deleted = 1)"""

@Dao
internal interface ThingDao {
    @Transaction
    @Query("SELECT * FROM things WHERE deleted = 0 ORDER BY updatedAt DESC, id DESC") fun observeThings(): Flow<List<ThingRow>>
    @Transaction
    @Query("SELECT * FROM thing_records ORDER BY at DESC, id DESC") fun observeRecords(): Flow<List<ThingRecordRow>>
    @Query("SELECT * FROM things WHERE deleted = 0 ORDER BY updatedAt DESC, id DESC") suspend fun allThings(): List<ThingRow>
    @Query("SELECT * FROM thing_records ORDER BY at DESC, id DESC") suspend fun allRecords(): List<ThingRecordRow>
    @Query("SELECT * FROM things WHERE id = :id AND deleted = 0") suspend fun thing(id: Long): ThingRow?
    @Query("SELECT * FROM thing_records WHERE id = :id") suspend fun record(id: Long): ThingRecordRow?
    @Insert suspend fun insert(row: ThingRow): Long
    @Insert suspend fun insertRecord(row: ThingRecordRow): Long
    @Update suspend fun update(row: ThingRow)
    @Update suspend fun updateRecord(row: ThingRecordRow)
    @Query("DELETE FROM thing_records WHERE id = :id") suspend fun deleteRecord(id: Long)
    @Query("DELETE FROM thing_records WHERE thingId = :id") suspend fun deleteRecords(id: Long)
    @Query("UPDATE thing_records SET sameAsId = NULL WHERE sameAsId = :id") suspend fun clearSameAs(id: Long)
    @Query("SELECT * FROM thing_watch_state WHERE slot = 1") fun observeState(): Flow<ThingWatchState?>
    @Query("SELECT * FROM thing_watch_state WHERE slot = 1") suspend fun state(): ThingWatchState?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun state(row: ThingWatchState)
    @Query("SELECT COALESCE(MAX(id), 0) FROM transcript_lines") suspend fun maxLineId(): Long
    @Query("SELECT COALESCE(MAX(id), 0) FROM transcript_lines WHERE startAt < :before") suspend fun maxLineIdBefore(before: Long): Long
    @Query("SELECT * FROM transcript_lines WHERE id > :after AND id <= :until ORDER BY id LIMIT 80")
    suspend fun batch(after: Long, until: Long = Long.MAX_VALUE): List<TranscriptLineEntity>
    @Query("SELECT l.* FROM transcript_lines l JOIN segments s ON s.id = l.segmentId WHERE l.id IN (:ids) AND " + THING_ELIGIBLE + " ORDER BY l.id")
    suspend fun eligible(ids: List<Long>): List<TranscriptLineEntity>
    @Query("SELECT l.* FROM transcript_lines l JOIN segments s ON s.id = l.segmentId WHERE l.id <= :before AND " + THING_ELIGIBLE + " ORDER BY l.id DESC LIMIT 20")
    suspend fun context(before: Long): List<TranscriptLineEntity>
    // LIKE 参数由调用方转义；检索最新状态以及最后一次 Placed，不能把历史旧借用人当作当前借用人。
    @Query("""SELECT t.id FROM things t WHERE t.deleted = 0 AND (t.name LIKE :pattern ESCAPE '\' OR
        t.aliasesJson LIKE :pattern ESCAPE '\' OR EXISTS (SELECT 1 FROM thing_records r WHERE r.thingId = t.id AND r.status = 'Accepted'
        AND ((r.id = (SELECT id FROM thing_records WHERE thingId = t.id AND status = 'Accepted' ORDER BY at DESC, id DESC LIMIT 1)
        AND (r.place LIKE :pattern ESCAPE '\' OR r.who LIKE :pattern ESCAPE '\')) OR
        (r.id = (SELECT id FROM thing_records WHERE thingId = t.id AND status = 'Accepted' AND action = 'Placed' ORDER BY at DESC, id DESC LIMIT 1)
        AND r.place LIKE :pattern ESCAPE '\'))))""")
    fun matching(pattern: String): Flow<List<Long>>
}

internal object ThingSchema {
    fun create(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS things (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, aliasesJson TEXT NOT NULL, owner TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS thing_records (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, thingId INTEGER, name TEXT NOT NULL, action TEXT NOT NULL, place TEXT, who TEXT, owner TEXT NOT NULL, at INTEGER NOT NULL, sourceLineId INTEGER, sourceText TEXT, status TEXT NOT NULL, origin TEXT NOT NULL, explicit INTEGER NOT NULL, sameAsId INTEGER, createdAt INTEGER NOT NULL, acceptedAt INTEGER, aliasesJson TEXT NOT NULL)")
        listOf("thingId", "sourceLineId", "at", "status").forEach { db.execSQL("CREATE INDEX IF NOT EXISTS index_thing_records_$it ON thing_records($it)") }
        db.execSQL("CREATE TABLE IF NOT EXISTS thing_watch_state (slot INTEGER NOT NULL PRIMARY KEY, watermark INTEGER NOT NULL, enabled INTEGER NOT NULL, revision INTEGER NOT NULL, failedEnd INTEGER, failures INTEGER NOT NULL)")
        install(db)
    }
    val callback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) = install(db)
    }
    fun install(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS things_line_deleted BEFORE DELETE ON transcript_lines BEGIN
            DELETE FROM thing_records WHERE sourceLineId = OLD.id AND status = 'Pending';
            UPDATE thing_records SET sourceLineId = NULL WHERE sourceLineId = OLD.id;
            END""")
        // 事件软删除也等同于依据消失；备份恢复与隐私删除使用相同规则。
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS things_event_hidden AFTER UPDATE OF deleted ON memory_events WHEN NEW.deleted = 1 BEGIN
            DELETE FROM thing_records WHERE status = 'Pending' AND sourceLineId IN (SELECT id FROM transcript_lines WHERE eventId = NEW.id);
            UPDATE thing_records SET sourceLineId = NULL WHERE sourceLineId IN (SELECT id FROM transcript_lines WHERE eventId = NEW.id);
            END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS things_event_deleted BEFORE DELETE ON memory_events BEGIN
            DELETE FROM thing_records WHERE status = 'Pending' AND sourceLineId IN (SELECT id FROM transcript_lines WHERE eventId = OLD.id);
            UPDATE thing_records SET sourceLineId = NULL WHERE sourceLineId IN (SELECT id FROM transcript_lines WHERE eventId = OLD.id);
            END""")
    }
}
