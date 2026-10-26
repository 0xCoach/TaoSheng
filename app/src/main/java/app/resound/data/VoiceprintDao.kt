package app.resound.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "voiceprint_samples", indices = [Index(value = ["segmentId", "groupKey"], unique = true)])
data class VoiceprintSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String, val sourceLineId: Long?, val segmentId: Long?, val groupKey: String?,
    val vector: ByteArray, val durationMillis: Long, val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val personId: Long = 0,
)
@Entity(tableName = "voiceprint_exclusions", primaryKeys = ["segmentId", "groupKey"])
data class VoiceprintExclusionEntity(val segmentId: Long, val groupKey: String, val createdAt: Long)

@Entity(tableName = "voiceprint_people", indices = [Index(value = ["name"], unique = true)])
data class VoiceprintPersonEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val createdAt: Long)

data class VoiceprintSampleInput(val sampleId: Long, val sourceId: String?)

@Dao
abstract class VoiceprintDao {
    @Query("SELECT * FROM voiceprint_people ORDER BY id") abstract fun observePeople(): Flow<List<VoiceprintPersonEntity>>
    @Query("SELECT * FROM voiceprint_people ORDER BY id") abstract suspend fun people(): List<VoiceprintPersonEntity>
    @Insert abstract suspend fun insertPerson(row: VoiceprintPersonEntity): Long
    @Query("UPDATE voiceprint_people SET name = :name WHERE id = :id") abstract suspend fun renamePerson(id: Long, name: String)
    @Query("DELETE FROM voiceprint_people WHERE id = :id") abstract suspend fun deletePerson(id: Long)
    @Query("SELECT v.id AS sampleId, CASE WHEN v.source = 'enroll' THEN 'mic' ELSE s.sourceId END AS sourceId FROM voiceprint_samples v LEFT JOIN segments s ON s.id = v.segmentId")
    abstract suspend fun sampleInputs(): List<VoiceprintSampleInput>
    @Query("SELECT * FROM voiceprint_samples ORDER BY id") abstract suspend fun allSamples(): List<VoiceprintSampleEntity>
    @Query("SELECT * FROM voiceprint_samples ORDER BY id") abstract fun observeAllSamples(): Flow<List<VoiceprintSampleEntity>>
    @Query("DELETE FROM voiceprint_samples WHERE personId = :id") abstract suspend fun clearPersonSamples(id: Long)
    @Query("UPDATE transcript_lines SET speakerName = :name WHERE speakerPersonId = :id AND voiceInputJson IS NULL") abstract suspend fun renameResults(id: Long, name: String)
    @Query("UPDATE transcript_lines SET speakerName = NULL, speakerScore = NULL, speakerPersonId = NULL, speakerManual = 0 WHERE speakerPersonId = :id AND voiceInputJson IS NULL") abstract suspend fun clearPersonResults(id: Long)
    @Query("UPDATE transcript_lines SET speakerName = :name, speakerScore = :score, speakerPersonId = :personId, speakerManual = :manual WHERE voiceInputJson IS NULL AND id IN (:ids) AND (:manual = 1 OR (speakerManual = 0 AND (speakerPersonId IS NOT NULL OR speakerName IS NULL OR speakerName = '我')))")
    abstract suspend fun identity(ids: List<Long>, personId: Long?, name: String?, score: Float?, manual: Boolean = false)

    @Query("SELECT * FROM voiceprint_samples WHERE personId = 0 ORDER BY id") abstract suspend fun samples(): List<VoiceprintSampleEntity>
    @Query("SELECT * FROM voiceprint_samples WHERE personId = 0 ORDER BY id") abstract fun observeSamples(): Flow<List<VoiceprintSampleEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun saveSample(sample: VoiceprintSampleEntity)
    @Query("DELETE FROM voiceprint_samples WHERE segmentId = :segmentId AND groupKey = :key")
    abstract suspend fun removeSample(segmentId: Long, key: String)
    /** 同时间按 id 保留较新的自动样本，永不触及 line/enroll。必须与插入同事务。 */
    @Query("DELETE FROM voiceprint_samples WHERE source = 'auto' AND id NOT IN (SELECT id FROM voiceprint_samples WHERE source = 'auto' ORDER BY createdAt DESC, id DESC LIMIT 40)")
    abstract suspend fun trimAutoSamples()
    @Query("DELETE FROM voiceprint_samples WHERE personId = 0") abstract suspend fun clearSamples()
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun exclude(row: VoiceprintExclusionEntity)
    @Query("DELETE FROM voiceprint_exclusions WHERE segmentId = :segmentId AND groupKey = :key")
    abstract suspend fun unexclude(segmentId: Long, key: String)
    @Query("SELECT groupKey FROM voiceprint_exclusions WHERE segmentId = :segmentId")
    abstract suspend fun exclusions(segmentId: Long): List<String>
    @Query("DELETE FROM voiceprint_exclusions") abstract suspend fun clearExclusions()
    @Query("SELECT * FROM transcript_lines WHERE id = :id") abstract suspend fun line(id: Long): TranscriptLineEntity?
    @Query("SELECT * FROM transcript_lines WHERE segmentId = :id ORDER BY startAt, id")
    abstract suspend fun lines(id: Long): List<TranscriptLineEntity>
    @Query("SELECT * FROM segments WHERE sourceId != 'voice-input' AND status = 'Done' AND voiceprintProcessedAt IS NULL ORDER BY startAt, id")
    abstract suspend fun pending(): List<SegmentEntity>
    @Query("UPDATE transcript_lines SET speakerName = CASE WHEN speakerName IS NULL OR speakerName = '我' THEN :name ELSE speakerName END, speakerScore = :score WHERE voiceInputJson IS NULL AND id IN (:ids)")
    abstract suspend fun result(ids: List<Long>, name: String?, score: Float?)
    @Query("UPDATE segments SET voiceprintProcessedAt = :at WHERE id = :id")
    abstract suspend fun processed(id: Long, at: Long)
    @Query("UPDATE segments SET voiceprintProcessedAt = NULL WHERE sourceId != 'voice-input' AND status = 'Done'")
    abstract suspend fun invalidate()
    @Query("SELECT * FROM segments WHERE sourceId != 'voice-input' AND status = 'Done'")
    abstract suspend fun doneSegments(): List<SegmentEntity>
    /** 只让指定的段重新匹配（录音还在的那些；录音已删的段重跑也只是原样标记完成） */
    @Query("UPDATE segments SET voiceprintProcessedAt = NULL WHERE id IN (:ids)")
    abstract suspend fun invalidateSegments(ids: List<Long>)
    /** 阈值调整后按已有分数重新标“我”（只动声纹写的“我”，不碰其他名字） */
    @Query("UPDATE transcript_lines SET speakerName = CASE WHEN speakerScore >= :sure THEN '我' ELSE NULL END WHERE voiceInputJson IS NULL AND speakerManual = 0 AND speakerScore IS NOT NULL AND (speakerName IS NULL OR speakerName = '我')")
    abstract suspend fun relabel(sure: Float)
    @Query("UPDATE transcript_lines SET speakerName = CASE WHEN speakerName = '我' THEN NULL ELSE speakerName END, speakerScore = NULL, speakerPersonId = NULL, speakerManual = 0 WHERE voiceInputJson IS NULL AND (speakerPersonId = 0 OR speakerName = '我' OR (speakerPersonId IS NULL AND speakerManual = 0))")
    abstract suspend fun clearResults()
}
