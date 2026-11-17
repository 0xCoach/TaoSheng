package app.resound.night

import androidx.room.*
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Entity(tableName = "nights", indices = [Index(value = ["date"], unique = true)])
data class NightRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val date: String,
    val start: Long, val end: Long, val state: String, val payload: String, val signals: String,
    val createdAt: Long, val updatedAt: Long)
@Entity(tableName = "night_events", indices = [Index("nightId")], foreignKeys = [ForeignKey(entity = NightRow::class, parentColumns = ["id"], childColumns = ["nightId"], onDelete = ForeignKey.CASCADE)])
data class NightEventRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val nightId: Long, val start: Long, val end: Long,
    val kind: String, val payload: String, val certainty: Double, val vetoed: Boolean)
@Entity(tableName = "night_clips", indices = [Index("nightId")], foreignKeys = [ForeignKey(entity = NightRow::class, parentColumns = ["id"], childColumns = ["nightId"], onDelete = ForeignKey.CASCADE)])
data class NightClipRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val nightId: Long, val start: Long, val end: Long,
    val path: String?, val scores: ByteArray, val features: String, val confirmed: Boolean, val expiresAt: Long, val candidate: Boolean)
@Entity(tableName = "night_envelope", foreignKeys = [ForeignKey(entity = NightRow::class, parentColumns = ["id"], childColumns = ["nightId"], onDelete = ForeignKey.CASCADE)])
data class NightEnvelopeRow(@PrimaryKey val nightId: Long, val start: Long, val points: ByteArray)
@Entity(tableName = "night_feedback", indices = [Index("nightId"), Index(value = ["eventId"], unique = true)], foreignKeys = [ForeignKey(entity = NightRow::class, parentColumns = ["id"], childColumns = ["nightId"], onDelete = ForeignKey.CASCADE)])
data class NightFeedbackRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val nightId: Long, val eventId: Long,
    val kind: String, val yes: Boolean, val scores: ByteArray, val features: String, val at: Long)
// 卡 203 表预先加入同一 39→40 迁移；卡 202 不启用第二版业务。
@Entity(tableName = "night_tags") data class NightTagRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val preset: Boolean)
@Entity(tableName = "night_tag_links", primaryKeys = ["nightId", "tagId"], indices = [Index("tagId")], foreignKeys = [ForeignKey(entity = NightRow::class,parentColumns=["id"],childColumns=["nightId"],onDelete=ForeignKey.CASCADE),ForeignKey(entity=NightTagRow::class,parentColumns=["id"],childColumns=["tagId"],onDelete=ForeignKey.CASCADE)])
data class NightTagLink(val nightId: Long, val tagId: Long, val state: String)
@Entity(tableName = "night_dreams", indices = [Index("nightId")], foreignKeys = [ForeignKey(entity = NightRow::class,parentColumns=["id"],childColumns=["nightId"],onDelete=ForeignKey.CASCADE)])
data class NightDreamRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val nightId: Long, val at: Long, val until: Long, val payload: String)
@Entity(tableName = "night_personal") data class NightPersonalRow(@PrimaryKey val kind: String, val payload: String, val trainedAt: Long, val feedbackCount: Int)
@Entity(tableName = "night_weeks") data class NightWeekRow(@PrimaryKey val weekStart: String, val payload: String, val pushState: String)
@Entity(tableName = "night_notice") data class NightNoticeRow(@PrimaryKey val key: String, val at: Long, val muted: Boolean)

@Dao interface NightDao {
    @Query("SELECT * FROM nights WHERE date >= :from AND date <= :to ORDER BY date DESC") fun nights(from: String, to: String): Flow<List<NightRow>>
    @Query("SELECT * FROM nights ORDER BY date DESC") fun observe(): Flow<List<NightRow>>
    @Query("SELECT * FROM nights ORDER BY date DESC") suspend fun all(): List<NightRow>
    @Query("SELECT * FROM nights WHERE id = :id") suspend fun get(id: Long): NightRow?
    @Query("SELECT * FROM nights WHERE date = :date") suspend fun onDate(date: String): NightRow?
    @Insert suspend fun insert(row: NightRow): Long
    @Update suspend fun update(row: NightRow)
    @Query("DELETE FROM nights WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM night_events WHERE nightId = :id ORDER BY start,id") suspend fun events(id: Long): List<NightEventRow>
    @Query("SELECT * FROM night_events WHERE id = :id") suspend fun event(id: Long): NightEventRow?
    @Insert suspend fun insertEvent(row: NightEventRow): Long
    @Update suspend fun updateEvent(row: NightEventRow)
    @Query("DELETE FROM night_events WHERE id = :id") suspend fun deleteEvent(id: Long)
    @Query("SELECT * FROM night_clips WHERE nightId = :id ORDER BY start") suspend fun clips(id: Long): List<NightClipRow>
    @Query("SELECT * FROM night_clips WHERE id = :id") suspend fun clip(id: Long): NightClipRow?
    @Query("SELECT * FROM night_clips WHERE path IS NOT NULL AND confirmed = 0 AND expiresAt <= :at") suspend fun expired(at: Long): List<NightClipRow>
    @Insert suspend fun insertClip(row: NightClipRow): Long
    @Update suspend fun updateClip(row: NightClipRow)
    @Upsert suspend fun envelope(row: NightEnvelopeRow)
    @Query("SELECT * FROM night_envelope WHERE nightId = :id") suspend fun envelope(id: Long): NightEnvelopeRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun feedback(row: NightFeedbackRow)
    @Query("SELECT * FROM night_feedback ORDER BY at,id") suspend fun feedback(): List<NightFeedbackRow>
    @Query("SELECT * FROM night_tags ORDER BY preset DESC,id") fun tags(): Flow<List<NightTagRow>>
    @Query("SELECT * FROM night_tags ORDER BY id") suspend fun tagList(): List<NightTagRow>
    @Insert suspend fun insertTag(row: NightTagRow): Long
    @Query("DELETE FROM night_tags WHERE id=:id AND preset=0") suspend fun deleteTag(id: Long)
    @Upsert suspend fun tagLink(row: NightTagLink)
    @Query("SELECT * FROM night_tag_links WHERE nightId=:id") suspend fun tagLinks(id: Long): List<NightTagLink>
    @Insert suspend fun insertDream(row: NightDreamRow): Long
    @Update suspend fun updateDream(row: NightDreamRow)
    @Query("SELECT * FROM night_dreams WHERE nightId=:id ORDER BY at") suspend fun dreams(id: Long): List<NightDreamRow>
    @Query("SELECT * FROM night_dreams WHERE id=:id") suspend fun dream(id: Long): NightDreamRow?
    @Query("DELETE FROM night_dreams WHERE id=:id") suspend fun deleteDream(id: Long)
    @Query("SELECT * FROM night_personal") fun personal(): Flow<List<NightPersonalRow>>
    @Upsert suspend fun personal(row: NightPersonalRow)
    @Query("SELECT * FROM night_weeks ORDER BY weekStart DESC") fun weeks(): Flow<List<NightWeekRow>>
    @Upsert suspend fun week(row: NightWeekRow)
    @Query("SELECT * FROM night_notice WHERE `key`=:key") suspend fun notice(key: String): NightNoticeRow?
    @Upsert suspend fun notice(row: NightNoticeRow)
}

/** SQL 和 Room schema 的列、约束及索引保持一致。 */
object NightSchema {
    fun create(db: SQLiteConnection) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `nights` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `date` TEXT NOT NULL, `start` INTEGER NOT NULL, `end` INTEGER NOT NULL, `state` TEXT NOT NULL, `payload` TEXT NOT NULL, `signals` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_nights_date` ON `nights` (`date`)")
        fun child(name: String, columns: String, key: String = "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, ") {
            db.execSQL("CREATE TABLE IF NOT EXISTS `$name` ($key`nightId` INTEGER NOT NULL, $columns, FOREIGN KEY(`nightId`) REFERENCES `nights`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_${name}_nightId` ON `$name` (`nightId`)")
        }
        child("night_events", "`start` INTEGER NOT NULL, `end` INTEGER NOT NULL, `kind` TEXT NOT NULL, `payload` TEXT NOT NULL, `certainty` REAL NOT NULL, `vetoed` INTEGER NOT NULL")
        child("night_clips", "`start` INTEGER NOT NULL, `end` INTEGER NOT NULL, `path` TEXT, `scores` BLOB NOT NULL, `features` TEXT NOT NULL, `confirmed` INTEGER NOT NULL, `expiresAt` INTEGER NOT NULL, `candidate` INTEGER NOT NULL")
        db.execSQL("CREATE TABLE IF NOT EXISTS `night_envelope` (`nightId` INTEGER NOT NULL PRIMARY KEY, `start` INTEGER NOT NULL, `points` BLOB NOT NULL, FOREIGN KEY(`nightId`) REFERENCES `nights`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        child("night_feedback", "`eventId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `yes` INTEGER NOT NULL, `scores` BLOB NOT NULL, `features` TEXT NOT NULL, `at` INTEGER NOT NULL")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_night_feedback_eventId` ON `night_feedback` (`eventId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `night_tags` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `preset` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `night_tag_links` (`nightId` INTEGER NOT NULL, `tagId` INTEGER NOT NULL, `state` TEXT NOT NULL, PRIMARY KEY(`nightId`,`tagId`), FOREIGN KEY(`nightId`) REFERENCES `nights`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`tagId`) REFERENCES `night_tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_night_tag_links_tagId` ON `night_tag_links` (`tagId`)")
        child("night_dreams", "`at` INTEGER NOT NULL, `until` INTEGER NOT NULL, `payload` TEXT NOT NULL")
        db.execSQL("CREATE TABLE IF NOT EXISTS `night_personal` (`kind` TEXT NOT NULL PRIMARY KEY, `payload` TEXT NOT NULL, `trainedAt` INTEGER NOT NULL, `feedbackCount` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `night_weeks` (`weekStart` TEXT NOT NULL PRIMARY KEY, `payload` TEXT NOT NULL, `pushState` TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `night_notice` (`key` TEXT NOT NULL PRIMARY KEY, `at` INTEGER NOT NULL, `muted` INTEGER NOT NULL)")
    }
}
