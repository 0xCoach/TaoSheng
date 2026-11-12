package app.resound.topic

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import app.resound.data.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "topics", indices = [Index("status"), Index("createdAt")])
internal data class TopicRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String, val name: String?, val status: String, val createdAt: Long,
    val plannedStartAt: Long? = null, val plannedEndAt: Long? = null, val repeatRule: Int? = null,
    val eventId: Long? = null, val cardId: Long? = null, val error: String? = null, val revision: Long = 0,
    // 下列少量恢复元数据也进备份，避免进程重启丢失用户修改或重复提醒。
    val quietOverride: Boolean = false, val retainedItemIds: String = "[]", val starred: Boolean = false,
    val quietNotifiedAfter: Long? = null, val endingNotifiedAt: Long? = null,
)
@Entity(tableName = "topic_spans", indices = [Index("topicId"), Index("startAt")], foreignKeys = [
    ForeignKey(entity = TopicRow::class, parentColumns = ["id"], childColumns = ["topicId"], onDelete = ForeignKey.CASCADE)])
internal data class TopicSpanRow(@PrimaryKey(autoGenerate = true) val id: Long = 0,
    val topicId: Long, val startAt: Long, val endAt: Long? = null)
@Entity(tableName = "topic_marks", indices = [Index("topicId")], foreignKeys = [
    ForeignKey(entity = TopicRow::class, parentColumns = ["id"], childColumns = ["topicId"], onDelete = ForeignKey.CASCADE)])
internal data class TopicMarkRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val topicId: Long, val at: Long)
internal data class TopicGroup(@Embedded val row: TopicRow,
    @Relation(parentColumn = "id", entityColumn = "topicId") val spans: List<TopicSpanRow>,
    @Relation(parentColumn = "id", entityColumn = "topicId") val marks: List<TopicMarkRow>) {
    fun topic() = Topic(row.id, TopicKind.valueOf(row.kind), row.name, TopicStatus.valueOf(row.status), row.createdAt,
        row.plannedStartAt, row.plannedEndAt, TopicPolicy.days(row.repeatRule),
        spans.sortedBy { it.startAt }.map { TopicSpan(it.id, it.startAt, it.endAt) },
        marks.sortedBy { it.at }.map { TopicMark(it.id, it.at) }, row.eventId, row.cardId, row.error, row.revision, row.quietOverride)
    fun deadline(): Long = row.plannedEndAt ?: ((spans.minOfOrNull { it.startAt } ?: row.createdAt) + TopicPolicy.MAX_DURATION)
}

/** 所有归属查询按原话的开始时刻；相邻专题共享边界时一句只归一个专题。 */
internal const val OUTSIDE_TOPICS = """NOT EXISTS (SELECT 1 FROM topic_spans ts JOIN topics t ON t.id = ts.topicId
    WHERE t.status != 'Cancelled' AND l.startAt >= ts.startAt AND l.startAt < COALESCE(ts.endAt, t.plannedEndAt,
        (SELECT MIN(initial_span.startAt) + 14400000 FROM topic_spans initial_span WHERE initial_span.topicId = t.id)))"""

@Dao
internal interface TopicDao {
    @Transaction @Query("SELECT * FROM topics ORDER BY createdAt, id") fun observeAll(): Flow<List<TopicGroup>>
    @Transaction @Query("SELECT * FROM topics ORDER BY createdAt, id") suspend fun all(): List<TopicGroup>
    @Transaction @Query("SELECT * FROM topics WHERE id = :id") suspend fun get(id: Long): TopicGroup?
    @Insert suspend fun insert(row: TopicRow): Long
    @Update suspend fun update(row: TopicRow)
    @Insert suspend fun span(row: TopicSpanRow): Long
    @Insert suspend fun mark(row: TopicMarkRow): Long
    @Query("DELETE FROM topic_marks WHERE id = :markId AND topicId = :topicId") suspend fun unmark(topicId: Long, markId: Long)
    @Query("UPDATE topic_spans SET endAt = MAX(startAt, :at) WHERE topicId = :id AND endAt IS NULL") suspend fun closeSpan(id: Long, at: Long)
    @Query("DELETE FROM topic_spans WHERE topicId = :id") suspend fun deleteSpans(id: Long)
    /** 时段和重点靠外键级联一起删 */
    @Query("DELETE FROM topics WHERE id = :id") suspend fun deleteTopic(id: Long)
    @Query("""SELECT EXISTS(SELECT 1 FROM topic_spans ts JOIN topics t ON t.id = ts.topicId
        WHERE t.kind = 'Training' AND t.status NOT IN ('Cancelled', 'Scheduled') AND :at >= ts.startAt
        AND :at < COALESCE(ts.endAt, t.plannedEndAt, (SELECT MIN(s.startAt) + 14400000 FROM topic_spans s WHERE s.topicId = t.id)))""")
    fun trainingAt(at: Long): Boolean
    @Query("SELECT MAX(endAt) FROM transcript_lines WHERE digestedAt IS NOT NULL AND startAt < :end AND endAt > :start")
    suspend fun digestedUntil(start: Long, end: Long): Long?
    @Query("""SELECT l.* FROM transcript_lines l JOIN segments s ON s.id = l.segmentId
        WHERE s.status = 'Done' AND l.voiceInputPendingUntil IS NULL AND EXISTS
        (SELECT 1 FROM topic_spans ts WHERE ts.topicId = :id AND l.startAt >= ts.startAt AND l.startAt < ts.endAt)
        ORDER BY l.startAt, l.id""") suspend fun lines(id: Long): List<TranscriptLineEntity>
    @Query("""SELECT s.* FROM segments s WHERE EXISTS (SELECT 1 FROM topic_spans ts
        WHERE ts.topicId = :id AND s.startAt < COALESCE(ts.endAt, :now) AND s.endAt > ts.startAt) ORDER BY s.startAt, s.id""")
    suspend fun segments(id: Long, now: Long): List<SegmentEntity>
    @Query("""SELECT * FROM memory_events WHERE deleted = 0 AND startAt < :end AND endAt > :start
        AND id NOT IN (SELECT eventId FROM topics WHERE eventId IS NOT NULL AND status != 'Cancelled') ORDER BY startAt, id""")
    suspend fun ordinaryEvents(start: Long, end: Long): List<MemoryEventEntity>
    @Query("SELECT * FROM memory_items WHERE eventId IN (:ids)") suspend fun eventItems(ids: List<Long>): List<MemoryItemEntity>
    @Query("SELECT * FROM memory_items WHERE id IN (:ids)") suspend fun items(ids: List<Long>): List<MemoryItemEntity>
    @Query("UPDATE memory_items SET eventId = :eventId WHERE id IN (:ids)") suspend fun attachItems(ids: List<Long>, eventId: Long?)
    @Query("DELETE FROM memory_items WHERE id IN (:ids)") suspend fun deleteItems(ids: List<Long>)
    @Query("UPDATE transcript_lines SET eventId = NULL, digestedAt = NULL WHERE eventId IN (:ids)") suspend fun releaseEvents(ids: List<Long>)
    @Query("UPDATE transcript_lines SET eventId = NULL, digestedAt = NULL WHERE startAt >= :start AND startAt < :end")
    suspend fun releaseRange(start: Long, end: Long)
    @Query("DELETE FROM memory_events WHERE id IN (:ids)") suspend fun deleteEvents(ids: List<Long>)
    @Query("DELETE FROM skill_cards WHERE id = :id") suspend fun deleteCard(id: Long)
    @Query("""SELECT MAX(endAt) FROM segments WHERE sourceId != 'voice-input' AND status NOT IN ('SkippedPlayback', 'SkippedEnv', 'Empty')
        AND startAt >= :start AND startAt < :end""") suspend fun lastRecording(start: Long, end: Long): Long?
    @Query("UPDATE segments SET nextRetryAt = 0 WHERE id IN (:ids) AND status = 'Failed' AND attempts < 5")
    suspend fun prioritize(ids: List<Long>)
    @Query("""SELECT COUNT(*) FROM segments s WHERE s.status = 'Pending' AND EXISTS
        (SELECT 1 FROM topic_spans ts JOIN topics t ON t.id = ts.topicId
        WHERE t.status IN ('Ended', 'Summarizing', 'Done') AND s.startAt < ts.endAt AND s.endAt > ts.startAt)""")
    suspend fun pendingAudio(): Int
}

internal object TopicSchema {
    fun create(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS topics (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            kind TEXT NOT NULL, name TEXT, status TEXT NOT NULL, createdAt INTEGER NOT NULL,
            plannedStartAt INTEGER, plannedEndAt INTEGER, repeatRule INTEGER, eventId INTEGER, cardId INTEGER,
            error TEXT, revision INTEGER NOT NULL, quietOverride INTEGER NOT NULL, retainedItemIds TEXT NOT NULL,
            starred INTEGER NOT NULL, quietNotifiedAfter INTEGER, endingNotifiedAt INTEGER)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_topics_status ON topics(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_topics_createdAt ON topics(createdAt)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS topic_spans (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            topicId INTEGER NOT NULL, startAt INTEGER NOT NULL, endAt INTEGER,
            FOREIGN KEY(topicId) REFERENCES topics(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_topic_spans_topicId ON topic_spans(topicId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_topic_spans_startAt ON topic_spans(startAt)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS topic_marks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            topicId INTEGER NOT NULL, at INTEGER NOT NULL,
            FOREIGN KEY(topicId) REFERENCES topics(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_topic_marks_topicId ON topic_marks(topicId)")
    }
}
