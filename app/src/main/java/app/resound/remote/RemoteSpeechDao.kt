package app.resound.remote

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** 只保存配置指纹，不保存 API key。任务与人物映射都按服务/凭据隔离。 */
@Entity(tableName = "remote_speech_tasks")
data class RemoteSpeechTask(@PrimaryKey val key: String, val scope: String, val requestId: String,
    val jobId: String?, val segmentId: Long?, val createdAt: Long)

@Entity(tableName = "remote_speech_people", primaryKeys = ["scope", "localId"])
data class RemoteSpeechPerson(val scope: String, val localId: Long, val remoteId: String?, val name: String,
    val samples: Int = 0, val seconds: Int = 0, val ready: Boolean = false, val deleted: Boolean = false)

@Entity(tableName = "remote_speech_actions")
data class RemoteSpeechAction(@PrimaryKey val id: String, val scope: String, val localId: Long,
    val kind: String, val value: String?, val audioPath: String?, val createdAt: Long, val error: String? = null,
    /** enroll 来源的原话；录入的声音为 null */
    val lineId: Long? = null, val durationMillis: Long? = null)

/** 服务端样本的本地索引：id 即服务端样本 id；lineId 记录来自哪句原话（服务端不保存）。 */
@Entity(tableName = "remote_speech_samples")
data class RemoteSpeechSample(@PrimaryKey val id: String, val scope: String, val localId: Long, val lineId: Long?,
    val durationMillis: Long, val createdAt: Long)

data class RemoteSampleRow(@Embedded val sample: RemoteSpeechSample, val text: String?, val lineStart: Long?, val segmentPath: String?)
data class RemoteActionRow(@Embedded val action: RemoteSpeechAction, val text: String?, val lineStart: Long?)

@Dao
interface RemoteSpeechDao {
    @Query("SELECT * FROM remote_speech_tasks WHERE `key` = :key") suspend fun task(key: String): RemoteSpeechTask?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun task(row: RemoteSpeechTask)
    @Query("DELETE FROM remote_speech_tasks WHERE `key` = :key") suspend fun removeTask(key: String)
    @Query("SELECT * FROM remote_speech_tasks WHERE scope = :scope") suspend fun tasks(scope: String): List<RemoteSpeechTask>
    @Query("DELETE FROM remote_speech_tasks WHERE segmentId = :id AND scope = (SELECT remoteSpeakerScope FROM segments WHERE id = :id)") suspend fun consumeSegment(id: Long)
    @Query("SELECT t.* FROM remote_speech_tasks t LEFT JOIN segments s ON s.id = t.segmentId WHERE t.segmentId IS NOT NULL AND s.id IS NULL")
    fun orphanTasks(): Flow<List<RemoteSpeechTask>>
    @Query("SELECT * FROM remote_speech_people WHERE scope = :scope") suspend fun people(scope: String): List<RemoteSpeechPerson>
    @Query("SELECT * FROM remote_speech_people") fun observePeople(): Flow<List<RemoteSpeechPerson>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun person(row: RemoteSpeechPerson)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun action(row: RemoteSpeechAction)
    @Query("SELECT * FROM remote_speech_actions WHERE scope = :scope ORDER BY createdAt, id") suspend fun actions(scope: String): List<RemoteSpeechAction>
    @Query("DELETE FROM remote_speech_actions WHERE id = :id") suspend fun removeAction(id: String)
    @Query("SELECT * FROM remote_speech_actions WHERE id = :id") suspend fun findAction(id: String): RemoteSpeechAction?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun sample(row: RemoteSpeechSample)
    @Query("SELECT * FROM remote_speech_samples WHERE scope = :scope AND localId = :id") suspend fun samples(scope: String, id: Long): List<RemoteSpeechSample>
    @Query("SELECT * FROM remote_speech_samples WHERE scope = :scope AND lineId IN (:lines)") suspend fun samplesFor(scope: String, lines: List<Long>): List<RemoteSpeechSample>
    @Query("DELETE FROM remote_speech_samples WHERE id = :id") suspend fun removeSample(id: String)
    @Query("DELETE FROM remote_speech_samples WHERE scope = :scope AND localId = :id") suspend fun clearSamples(scope: String, id: Long)
    @Query("SELECT r.*, l.text AS text, l.startAt AS lineStart, s.path AS segmentPath FROM remote_speech_samples r LEFT JOIN transcript_lines l ON l.id = r.lineId LEFT JOIN segments s ON s.id = l.segmentId WHERE r.scope = :scope AND r.localId = :id ORDER BY r.createdAt DESC")
    fun observeSamples(scope: String, id: Long): Flow<List<RemoteSampleRow>>
    @Query("SELECT a.*, l.text AS text, l.startAt AS lineStart FROM remote_speech_actions a LEFT JOIN transcript_lines l ON l.id = a.lineId WHERE a.scope = :scope AND a.localId = :id AND a.kind IN ('enroll', 'sample-delete') ORDER BY a.createdAt DESC")
    fun observeActions(scope: String, id: Long): Flow<List<RemoteActionRow>>
    @Query("UPDATE transcript_lines SET speakerName = NULL, speakerPersonId = NULL, speakerScore = NULL WHERE speakerManual = 0 AND segmentId IN (SELECT id FROM segments WHERE remoteSpeakerScope = :scope) AND speakerPersonId = :id")
    suspend fun clearResults(scope: String, id: Long)
    @Query("UPDATE transcript_lines SET speakerName = NULL, speakerPersonId = NULL, speakerScore = NULL, speakerManual = 0 WHERE segmentId IN (SELECT id FROM segments WHERE remoteSpeakerScope = :scope) AND speakerPersonId = :id")
    suspend fun clearExplicitResults(scope: String, id: Long)
}

object RemoteSpeechSchema {
    fun create(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS remote_speech_tasks (`key` TEXT NOT NULL PRIMARY KEY, scope TEXT NOT NULL, requestId TEXT NOT NULL, jobId TEXT, segmentId INTEGER, createdAt INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS remote_speech_people (scope TEXT NOT NULL, localId INTEGER NOT NULL, remoteId TEXT, name TEXT NOT NULL, samples INTEGER NOT NULL, seconds INTEGER NOT NULL, ready INTEGER NOT NULL, deleted INTEGER NOT NULL, PRIMARY KEY(scope, localId))")
        db.execSQL("CREATE TABLE IF NOT EXISTS remote_speech_actions (id TEXT NOT NULL PRIMARY KEY, scope TEXT NOT NULL, localId INTEGER NOT NULL, kind TEXT NOT NULL, value TEXT, audioPath TEXT, createdAt INTEGER NOT NULL, error TEXT)")
    }
    /** 30→31：动作记来源原话，新增服务端样本索引 */
    fun addSamples(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE remote_speech_actions ADD COLUMN lineId INTEGER")
        db.execSQL("ALTER TABLE remote_speech_actions ADD COLUMN durationMillis INTEGER")
        db.execSQL("CREATE TABLE IF NOT EXISTS remote_speech_samples (id TEXT NOT NULL PRIMARY KEY, scope TEXT NOT NULL, localId INTEGER NOT NULL, lineId INTEGER, durationMillis INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
    }
}
