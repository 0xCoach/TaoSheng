package app.resound.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "segments", indices = [Index("startAt"), Index(value = ["path"], unique = true)])
data class SegmentEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val startAt: Long, val endAt: Long,
    val sourceId: String, val path: String, val sizeBytes: Long, val status: String = "Pending",
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
    val nextRetryAt: Long? = null, val error: String? = null, val completedAt: Long? = null,
    val logId: String? = null, @ColumnInfo(defaultValue = "0") val sentMillis: Long = 0, val voiceprintProcessedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val playbackMillis: Long = 0, val firstFailedAt: Long? = null)
@Entity(tableName = "watchdog", indices = [Index("at")])
data class EventEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val at: Long, val type: String,
    val detail: String?, val batteryPercent: Int?, val charging: Boolean?, val gapStart: Long? = null, val gapEnd: Long? = null)
@Entity(tableName = "heartbeats", indices = [Index("at")])
data class HeartbeatEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val at: Long,
    val intervalStart: Long, val running: Boolean, val session: String)
@Entity(tableName = "battery", indices = [Index("at")])
data class BatteryEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val at: Long,
    val percent: Int?, val charging: Boolean?)

@Entity(tableName = "app_power", indices = [Index("at")])
data class AppPowerEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long, val elapsedMs: Long, val session: String, val cpuMs: Long,
    val txBytes: Long, val rxBytes: Long, val micMs: Long, val charging: Boolean?,
    val chargeEpoch: Long, val capacityMah: Double)

@Dao
interface CaptureDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun segment(row: SegmentEntity)
    @Insert suspend fun event(row: EventEntity)
    @Insert suspend fun heartbeat(row: HeartbeatEntity)
    @Insert suspend fun battery(row: BatteryEntity)
    @Insert suspend fun appPower(row: AppPowerEntity)
    // 包含跨午夜的相邻样本；估算时裁剪至当天。
    @Query("SELECT * FROM app_power WHERE at >= :start - 1200000 AND at <= :end + 1200000 ORDER BY at, id")
    fun appPowerSamples(start: Long, end: Long): Flow<List<AppPowerEntity>>
    @Query("SELECT * FROM segments WHERE startAt < :end AND endAt > :start ORDER BY startAt")
    fun segments(start: Long, end: Long): Flow<List<SegmentEntity>>
    @Query("SELECT * FROM watchdog WHERE (at >= :start AND at < :end) OR (gapStart < :end AND gapEnd > :start) ORDER BY at")
    fun events(start: Long, end: Long): Flow<List<EventEntity>>
    @Query("SELECT * FROM heartbeats WHERE intervalStart < :end AND at > :start ORDER BY at")
    fun heartbeats(start: Long, end: Long): Flow<List<HeartbeatEntity>>
    @Query("SELECT * FROM battery WHERE at >= :start AND at < :end ORDER BY at")
    fun batteries(start: Long, end: Long): Flow<List<BatteryEntity>>
}

@Database(entities = [SegmentEntity::class, EventEntity::class, HeartbeatEntity::class, BatteryEntity::class,
    TranscriptLineEntity::class, AsrUsageEntity::class, AsrAttemptEntity::class,
    MemoryEventEntity::class, MemoryItemEntity::class, LlmUsageV2Entity::class, AppPowerEntity::class,
    VoiceprintSampleEntity::class, VoiceprintExclusionEntity::class, VoiceprintPersonEntity::class,
    AskConversationEntity::class, AskMessageEntity::class, ReviewEntity::class, app.resound.search.LineFts::class, app.resound.search.EventFts::class,
    app.resound.search.ItemFts::class, app.resound.search.EmbeddingEntity::class,
    app.resound.skills.SkillCardRow::class, app.resound.skills.SkillRunRow::class], version = CaptureDatabase.VERSION, exportSchema = true)
abstract class CaptureDatabase : RoomDatabase() {
    internal abstract fun push(): app.resound.push.PushDao
    abstract fun privacy(): PrivacyDao
    abstract fun capture(): CaptureDao
    abstract fun voiceInput(): VoiceInputDao
    abstract fun transcribe(): TranscribeDao
    abstract fun memory(): MemoryDao
    abstract fun voiceprint(): VoiceprintDao
    abstract fun ask(): AskDao
    abstract fun review(): ReviewDao
    abstract fun skills(): app.resound.skills.SkillDao
    companion object {
        /** 当前数据库版本；加迁移时同步改这里 */
        const val VERSION = 22

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE segments ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE segments ADD COLUMN nextRetryAt INTEGER")
                db.execSQL("ALTER TABLE segments ADD COLUMN error TEXT")
                db.execSQL("ALTER TABLE segments ADD COLUMN completedAt INTEGER")
                db.execSQL("ALTER TABLE segments ADD COLUMN logId TEXT")
                db.execSQL("ALTER TABLE segments ADD COLUMN sentMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE segments SET status = 'Pending' WHERE status = '待转写'")
                db.execSQL("CREATE TABLE IF NOT EXISTS transcript_lines (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, segmentId INTEGER NOT NULL, startAt INTEGER NOT NULL, endAt INTEGER NOT NULL, text TEXT NOT NULL, speakerTag TEXT, speakerName TEXT, FOREIGN KEY(segmentId) REFERENCES segments(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transcript_lines_segmentId ON transcript_lines(segmentId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS asr_usage (day TEXT NOT NULL PRIMARY KEY, sentMillis INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS asr_attempts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, segmentId INTEGER, at INTEGER NOT NULL, result TEXT NOT NULL, errorType TEXT, elapsedMillis INTEGER NOT NULL, sentMillis INTEGER NOT NULL, logId TEXT)")
            }
        }
        /** 加入大模型整理：事件、提取项、用量；句子加“归入哪件事”和“整理时间”。旧句子都算没整理过。 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transcript_lines ADD COLUMN eventId INTEGER")
                db.execSQL("ALTER TABLE transcript_lines ADD COLUMN digestedAt INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transcript_lines_eventId ON transcript_lines(eventId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS memory_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, startAt INTEGER NOT NULL, endAt INTEGER NOT NULL, scene TEXT NOT NULL, title TEXT NOT NULL, summary TEXT NOT NULL, pointsJson TEXT NOT NULL, peopleJson TEXT NOT NULL, place TEXT, emotion TEXT, intensity INTEGER, important INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, createdAt INTEGER NOT NULL, model TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_events_startAt ON memory_events(startAt)")
                db.execSQL("CREATE TABLE IF NOT EXISTS memory_items (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, eventId INTEGER NOT NULL, category TEXT NOT NULL, kind TEXT, content TEXT NOT NULL, dueText TEXT, dueAt INTEGER, who TEXT, lineId INTEGER, status TEXT NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(eventId) REFERENCES memory_events(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_items_eventId ON memory_items(eventId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_items_status ON memory_items(status)")
                db.execSQL("CREATE TABLE IF NOT EXISTS llm_usage (day TEXT NOT NULL, promptTokens INTEGER NOT NULL, completionTokens INTEGER NOT NULL, calls INTEGER NOT NULL, PRIMARY KEY(day))")
            }
        }
        /** 自身耗电采样表 */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS app_power (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, at INTEGER NOT NULL, elapsedMs INTEGER NOT NULL, session TEXT NOT NULL, cpuMs INTEGER NOT NULL, txBytes INTEGER NOT NULL, rxBytes INTEGER NOT NULL, micMs INTEGER NOT NULL, charging INTEGER, chargeEpoch INTEGER NOT NULL, capacityMah REAL NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_app_power_at ON app_power(at)")
            }
        }
        /** 声纹：句子相似度、录音段声纹处理时间、样本与排除表 */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transcript_lines ADD COLUMN speakerScore REAL")
                db.execSQL("ALTER TABLE segments ADD COLUMN voiceprintProcessedAt INTEGER")
                db.execSQL("CREATE TABLE IF NOT EXISTS voiceprint_samples (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, source TEXT NOT NULL, sourceLineId INTEGER, segmentId INTEGER, groupKey TEXT, vector BLOB NOT NULL, durationMillis INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_voiceprint_samples_segmentId_groupKey ON voiceprint_samples(segmentId, groupKey)")
                db.execSQL("CREATE TABLE IF NOT EXISTS voiceprint_exclusions (segmentId INTEGER NOT NULL, groupKey TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(segmentId, groupKey))")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS ask_conversations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, createdAt INTEGER NOT NULL, title TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS ask_messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, conversationId INTEGER NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL, sourcesJson TEXT NOT NULL, at INTEGER NOT NULL, error TEXT, FOREIGN KEY(conversationId) REFERENCES ask_conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ask_messages_conversationId ON ask_messages(conversationId)")
            }
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS reviews (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, type TEXT NOT NULL, startDate TEXT NOT NULL, endDate TEXT NOT NULL, brief TEXT NOT NULL, sectionsJson TEXT NOT NULL, generatedAt INTEGER NOT NULL, model TEXT NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_reviews_type_startDate_endDate ON reviews(type, startDate, endDate)")
            }
        }
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE segments ADD COLUMN playbackMillis INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS voiceprint_people (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_voiceprint_people_name ON voiceprint_people(name)")
                db.execSQL("ALTER TABLE voiceprint_samples ADD COLUMN personId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE transcript_lines ADD COLUMN speakerPersonId INTEGER")
                db.execSQL("ALTER TABLE transcript_lines ADD COLUMN speakerManual INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE transcript_lines SET speakerPersonId = 0 WHERE speakerName = '我'")
                db.execSQL("UPDATE transcript_lines SET speakerManual = 1 WHERE speakerName IS NOT NULL AND speakerName != '我'")
                db.execSQL("UPDATE transcript_lines SET speakerManual = 1 WHERE EXISTS (SELECT 1 FROM voiceprint_samples s WHERE s.source = 'line' AND s.segmentId = transcript_lines.segmentId AND s.groupKey = CASE WHEN transcript_lines.speakerTag IS NULL THEN 'line:' || transcript_lines.id ELSE 'tag:' || transcript_lines.speakerTag END)")
                db.execSQL("UPDATE transcript_lines SET speakerManual = 1 WHERE EXISTS (SELECT 1 FROM voiceprint_exclusions e WHERE e.segmentId = transcript_lines.segmentId AND e.groupKey = CASE WHEN transcript_lines.speakerTag IS NULL THEN 'line:' || transcript_lines.id ELSE 'tag:' || transcript_lines.speakerTag END)")
            }
        }
        /** 历史完成项没有准确完成时间，暂用提取时间回填。 */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memory_items ADD COLUMN doneAt INTEGER")
                db.execSQL("ALTER TABLE memory_items ADD COLUMN statusChangedAt INTEGER")
                db.execSQL("ALTER TABLE memory_items ADD COLUMN sortOrder INTEGER")
                db.execSQL("UPDATE memory_items SET doneAt = createdAt WHERE status = 'Done'")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_items_dueAt ON memory_items(dueAt)")
            }
        }
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ask_messages ADD COLUMN actionJson TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS embeddings (kind TEXT NOT NULL, refId INTEGER NOT NULL, model TEXT NOT NULL, dim INTEGER NOT NULL, vector BLOB NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(kind, refId))")
                app.resound.search.SearchIndex.install(db, backfill = true)
            }
        }
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS skill_cards (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, skillId TEXT NOT NULL, skillVersion INTEGER NOT NULL, skillName TEXT NOT NULL, icon TEXT NOT NULL, date INTEGER NOT NULL, entity TEXT NOT NULL, at INTEGER NOT NULL, endAt INTEGER, title TEXT NOT NULL, summary TEXT NOT NULL, fieldsJson TEXT NOT NULL, valuesJson TEXT NOT NULL, evidenceJson TEXT NOT NULL, generatedAt INTEGER NOT NULL, manual INTEGER NOT NULL, deletedAt INTEGER, aliasesJson TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_skill_cards_date ON skill_cards(date)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_skill_cards_skillId_date ON skill_cards(skillId, date)")
                db.execSQL("CREATE TABLE IF NOT EXISTS skill_runs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, skillId TEXT NOT NULL, date INTEGER NOT NULL, at INTEGER NOT NULL, ok INTEGER NOT NULL, result TEXT NOT NULL, tokens INTEGER NOT NULL, kind TEXT NOT NULL, retryable INTEGER NOT NULL, purpose TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_skill_runs_skillId_date ON skill_runs(skillId, date)")
            }
        }
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memory_items ADD COLUMN mergedInto INTEGER")
                db.execSQL("ALTER TABLE memory_items ADD COLUMN noMerge INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memory_events ADD COLUMN starred INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE memory_events ADD COLUMN starredAt INTEGER")
            }
        }
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS llm_usage_v2 (day TEXT NOT NULL, purpose TEXT NOT NULL, promptTokens INTEGER NOT NULL, completionTokens INTEGER NOT NULL, calls INTEGER NOT NULL, PRIMARY KEY(day, purpose))")
                db.execSQL("INSERT INTO llm_usage_v2 (day, purpose, promptTokens, completionTokens, calls) SELECT day, 'Other', promptTokens, completionTokens, calls FROM llm_usage")
                db.execSQL("DROP TABLE llm_usage")
            }
        }
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memory_items ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE memory_items ADD COLUMN editedAt INTEGER")
                db.execSQL("ALTER TABLE memory_items ADD COLUMN doneHintLineId INTEGER")
                db.execSQL("ALTER TABLE memory_items ADD COLUMN doneHintAt INTEGER")
                db.execSQL("ALTER TABLE memory_items ADD COLUMN dismissedDoneLinesJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE memory_items ADD COLUMN reminderVersion INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transcript_lines ADD COLUMN voiceInputJson TEXT")
                db.execSQL("ALTER TABLE transcript_lines ADD COLUMN voiceInputPendingUntil INTEGER")
            }
        }
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 重建表会移除条目的搜索触发器；跨表触发器也先撤下，改名后统一恢复。
                db.execSQL("DROP TRIGGER IF EXISTS search_event_hidden")
                db.execSQL("CREATE TABLE memory_items_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, eventId INTEGER, category TEXT NOT NULL, kind TEXT, content TEXT NOT NULL, dueText TEXT, dueAt INTEGER, who TEXT, lineId INTEGER, status TEXT NOT NULL, createdAt INTEGER NOT NULL, doneAt INTEGER, statusChangedAt INTEGER, sortOrder INTEGER, mergedInto INTEGER, noMerge INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0, editedAt INTEGER, doneHintLineId INTEGER, doneHintAt INTEGER, dismissedDoneLinesJson TEXT NOT NULL DEFAULT '[]', reminderVersion INTEGER NOT NULL DEFAULT 0, origin TEXT NOT NULL DEFAULT 'digest', sourceText TEXT, FOREIGN KEY(eventId) REFERENCES memory_events(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("INSERT INTO memory_items_new (id,eventId,category,kind,content,dueText,dueAt,who,lineId,status,createdAt,doneAt,statusChangedAt,sortOrder,mergedInto,noMerge,deleted,editedAt,doneHintLineId,doneHintAt,dismissedDoneLinesJson,reminderVersion) SELECT id,eventId,category,kind,content,dueText,dueAt,who,lineId,status,createdAt,doneAt,statusChangedAt,sortOrder,mergedInto,noMerge,deleted,editedAt,doneHintLineId,doneHintAt,dismissedDoneLinesJson,reminderVersion FROM memory_items")
                db.execSQL("DROP TABLE memory_items")
                db.execSQL("ALTER TABLE memory_items_new RENAME TO memory_items")
                listOf("eventId", "status", "dueAt").forEach { column ->
                    db.execSQL("CREATE INDEX index_memory_items_$column ON memory_items($column)")
                }
                app.resound.search.SearchIndex.install(db, backfill = false)
            }
        }
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memory_items ADD COLUMN strongReminder INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memory_events ADD COLUMN editedAt INTEGER")
                db.execSQL("ALTER TABLE memory_events ADD COLUMN originalTitle TEXT")
                db.execSQL("ALTER TABLE memory_events ADD COLUMN originalSummary TEXT")
                db.execSQL("ALTER TABLE memory_events ADD COLUMN originalPointsJson TEXT")
            }
        }
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE segments ADD COLUMN firstFailedAt INTEGER")
                // 老数据优先使用首次失败日志；没有日志的段在启动维护时开始计时。
                db.execSQL("UPDATE segments SET firstFailedAt = (SELECT MIN(at) FROM asr_attempts WHERE segmentId = segments.id AND result = 'Failed') WHERE status = 'Failed'")
            }
        }
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transcript_lines ADD COLUMN originalText TEXT")
            }
        }
        /** 全部迁移，按版本顺序。新增版本只在这里加一条；App 和迁移测试都用它，不再各写一份清单 */
        val MIGRATIONS get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22)
        fun open(context: Context, name: String = "capture.db") = Room.databaseBuilder(context, CaptureDatabase::class.java, name)
            .addMigrations(*MIGRATIONS)
            .addCallback(app.resound.search.SearchIndex.callback).build()
    }
}
