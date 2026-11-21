package app.resound.data

import app.resound.agent.AgentDao
import app.resound.agent.AgentEndpointRow
import app.resound.agent.AgentSchema
import app.resound.agent.AgentTaskRow
import app.resound.analysis.AnalysisDao
import app.resound.analysis.AnalysisRunRow
import app.resound.analysis.AnalysisSchema
import app.resound.analysis.AnalysisTemplateRow
import app.resound.collect.CaptureItemDao
import app.resound.collect.CaptureItemRow
import app.resound.collect.CaptureItemSchema
import app.resound.decision.DecisionDao
import app.resound.decision.DecisionRow
import app.resound.decision.DecisionSchema
import app.resound.devices.DeviceSyncStore
import app.resound.devices.DeviceTransfer
import app.resound.envsound.EnvSoundDao
import app.resound.envsound.EnvSoundRow
import app.resound.inbox.IdeaDao
import app.resound.inbox.IdeaRow
import app.resound.inbox.IdeaSchema
import app.resound.importer.FolderImportDao
import app.resound.importer.FolderObservation
import app.resound.importer.ImportFingerprint
import app.resound.importer.ImportRecordDao
import app.resound.importer.ImportRecordRow
import app.resound.importer.ImportRecordSchema
import app.resound.location.LocationDao
import app.resound.location.LocationPoint
import app.resound.location.LocationSchema
import app.resound.location.PlaceDao
import app.resound.location.PlaceRow
import app.resound.location.PlaceSchema
import app.resound.followup.FollowupDao
import app.resound.followup.FollowupRow
import app.resound.followup.FollowupSchema
import app.resound.mcp.McpClientDao
import app.resound.mcp.McpClientRow
import app.resound.mcp.McpSchema
import app.resound.projects.ProjectDao
import app.resound.projects.ProjectLinkRow
import app.resound.projects.ProjectMarkRow
import app.resound.projects.ProjectRow
import app.resound.projects.ProjectSchema
import app.resound.push.PushDao
import app.resound.revise.MemoryRevisionDao
import app.resound.revise.MemoryRevisionRow
import app.resound.revise.RevisionSchema
import app.resound.rules.RuleRunRow
import app.resound.rules.RuleSchema
import app.resound.rules.WorkflowRuleDao
import app.resound.rules.WorkflowRuleRow
import app.resound.redo.RedoDao
import app.resound.redo.RedoSnapshotRow
import app.resound.search.EmbeddingEntity
import app.resound.search.EventFts
import app.resound.search.ItemFts
import app.resound.search.LineFts
import app.resound.search.SearchIndex
import app.resound.screentime.PlaybackRow
import app.resound.screentime.ScreenAppNameRow
import app.resound.screentime.ScreenCoverageGapRow
import app.resound.screentime.ScreenEventRow
import app.resound.screentime.ScreenPullStateRow
import app.resound.screentime.ScreenTimeDao
import app.resound.skills.SkillCardRow
import app.resound.skills.SkillDao
import app.resound.skills.SkillRunRow
import app.resound.things.ThingDao
import app.resound.things.ThingRecordRow
import app.resound.things.ThingRow
import app.resound.things.ThingSchema
import app.resound.things.ThingWatchState
import app.resound.topic.TopicDao
import app.resound.topic.TopicMarkRow
import app.resound.topic.TopicRow
import app.resound.topic.TopicSchema
import app.resound.topic.TopicSpanRow

import androidx.sqlite.execSQL
import androidx.room.*
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "segments", indices = [Index("startAt"), Index(value = ["path"], unique = true), Index("importRecordId"), Index(value = ["status",
    "voiceprintProcessedAt"])], foreignKeys = [ForeignKey(entity = ImportRecordRow::class, parentColumns = ["id"], childColumns = ["importRecordId"], onDelete = ForeignKey.SET_NULL)])
data class SegmentEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val startAt: Long, val endAt: Long,
    val sourceId: String, val path: String, val sizeBytes: Long, val status: String = "Pending",
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
    val nextRetryAt: Long? = null, val error: String? = null, val completedAt: Long? = null,
    val logId: String? = null, @ColumnInfo(defaultValue = "0") val sentMillis: Long = 0, val voiceprintProcessedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val playbackMillis: Long = 0, val firstFailedAt: Long? = null, val asrSource: String? = null, @ColumnInfo(defaultValue = "0") val redoPending: Boolean = false,
    /** null 为升级前尚未确定；首次转写按录音开始时刻固化。 */
    val separateSpeakers: Boolean? = null, val importRecordId: Long? = null, val importChannel: Int? = null, val speakerCount: Int? = null,
    /** 外放「仍要转写」片段的整理闸：0 正常；1 暂不参与整理；2 用户已放行。 */
    @ColumnInfo(defaultValue = "0") val playbackDigest: Int = 0, val speakerIntervals: String? = null)
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
    @Query("SELECT path FROM segments") suspend fun segmentPaths(): List<String>
    /** 最近一段已保存人声片段的结束时刻；安静自动整理用此判定"安静了多久" */
    @Query("SELECT MAX(endAt) FROM segments WHERE importRecordId IS NULL") suspend fun latestSpeechEndAt(): Long?
    /** 时刻 at 所在（或之前最近）的片段，记灵感等"当前片段"关联用 */
    @Query("SELECT * FROM segments WHERE startAt <= :at ORDER BY startAt DESC LIMIT 1")
    suspend fun segmentAt(at: Long): SegmentEntity?
    @Insert suspend fun event(row: EventEntity)
    @Insert suspend fun heartbeat(row: HeartbeatEntity)
    @Insert suspend fun battery(row: BatteryEntity)
    @Insert suspend fun appPower(row: AppPowerEntity)
    // 包含跨午夜的相邻样本；估算时裁剪至当天。
    @Transaction
    @Query("SELECT * FROM app_power WHERE at >= :start - 1200000 AND at <= :end + 1200000 ORDER BY at, id")
    fun appPowerSamples(start: Long, end: Long): Flow<List<AppPowerEntity>>
    @Transaction
    @Query("SELECT * FROM segments WHERE startAt < :end AND endAt > :start ORDER BY startAt")
    fun segments(start: Long, end: Long): Flow<List<SegmentEntity>>
    @Query("SELECT * FROM watchdog WHERE (at >= :start AND at < :end) OR (gapStart < :end AND gapEnd > :start) ORDER BY at")
    fun events(start: Long, end: Long): Flow<List<EventEntity>>
    @Query("SELECT * FROM heartbeats WHERE intervalStart < :end AND at > :start ORDER BY at")
    fun heartbeats(start: Long, end: Long): Flow<List<HeartbeatEntity>>
    @Query("SELECT * FROM battery WHERE at >= :start AND at < :end ORDER BY at")
    fun batteries(start: Long, end: Long): Flow<List<BatteryEntity>>
}

@Database(entities = [app.resound.night.NightRow::class, app.resound.night.NightEventRow::class, app.resound.night.NightClipRow::class, app.resound.night.NightEnvelopeRow::class, app.resound.night.NightFeedbackRow::class, app.resound.night.NightTagRow::class, app.resound.night.NightTagLink::class, app.resound.night.NightDreamRow::class, app.resound.night.NightPersonalRow::class, app.resound.night.NightWeekRow::class, app.resound.night.NightNoticeRow::class, app.resound.importer.FolderRecordingRow::class, app.resound.marks.MarkRow::class, TopicRow::class, TopicSpanRow::class, TopicMarkRow::class, DeviceTransfer::class, ImportFingerprint::class, FolderObservation::class,
    PrivacyFileCleanup::class, ThingRow::class, ThingRecordRow::class, ThingWatchState::class, ImportRecordRow::class, RedoSnapshotRow::class, SegmentEntity::class,
    EventEntity::class, HeartbeatEntity::class, BatteryEntity::class,
    TranscriptLineEntity::class, AsrUsageEntity::class, AsrAttemptEntity::class,
    MemoryEventEntity::class, MemoryItemEntity::class, LlmUsageV2Entity::class, AppPowerEntity::class,
    VoiceprintSampleEntity::class, VoiceprintExclusionEntity::class, VoiceprintPersonEntity::class,
    AskConversationEntity::class, AskMessageEntity::class, ReviewEntity::class, LineFts::class, EventFts::class,
    ItemFts::class, EmbeddingEntity::class,
    PendingDigest::class, EnvSoundRow::class, SkillCardRow::class, SkillRunRow::class, LocationPoint::class, PlaceRow::class,
    ScreenEventRow::class, ScreenAppNameRow::class, ScreenPullStateRow::class, ScreenCoverageGapRow::class, PlaybackRow::class,
    IdeaRow::class, app.resound.inbox.IdeaEntryRow::class, ProjectRow::class, ProjectLinkRow::class, ProjectMarkRow::class,
    AnalysisTemplateRow::class, AnalysisRunRow::class, AgentEndpointRow::class, AgentTaskRow::class,
    DecisionRow::class, FollowupRow::class, CaptureItemRow::class,
    WorkflowRuleRow::class, RuleRunRow::class, MemoryRevisionRow::class, McpClientRow::class, SceneSessionRow::class], version = CaptureDatabase.VERSION, exportSchema = true)
@ConstructedBy(CaptureDatabaseConstructor::class)
abstract class CaptureDatabase : RoomDatabase() {
    /** 由安卓装配入口传入；内存测试库为 null，不从底层连接反查路径。 */
    var filePath: String? = null

    abstract fun night(): app.resound.night.NightDao
    abstract fun marks(): app.resound.marks.MarkDao
    abstract fun topics(): TopicDao
    abstract fun deviceSync(): DeviceSyncStore
    abstract fun folderRecordings(): app.resound.importer.FolderRecordingDao
    abstract fun folderImports(): FolderImportDao
    abstract fun imports(): ImportRecordDao
    abstract fun things(): ThingDao
    abstract fun redoData(): RedoDao
    abstract fun envSound(): EnvSoundDao
    abstract fun push(): PushDao
    abstract fun privacy(): PrivacyDao
    abstract fun capture(): CaptureDao
    abstract fun voiceInput(): VoiceInputDao
    abstract fun transcribe(): TranscribeDao
    abstract fun memory(): MemoryDao
    abstract fun voiceprint(): VoiceprintDao
    abstract fun ask(): AskDao
    abstract fun review(): ReviewDao
    abstract fun skills(): SkillDao
    abstract fun location(): LocationDao
    abstract fun places(): PlaceDao
    abstract fun screenTime(): ScreenTimeDao
    abstract fun ideas(): IdeaDao
    abstract fun projects(): ProjectDao
    abstract fun analysis(): AnalysisDao
    abstract fun agent(): AgentDao
    abstract fun decisions(): DecisionDao
    abstract fun followups(): FollowupDao
    abstract fun captureItems(): CaptureItemDao
    abstract fun workflowRules(): WorkflowRuleDao
    abstract fun revisions(): MemoryRevisionDao
    abstract fun mcpClients(): McpClientDao
    abstract fun sceneSessions(): SceneSessionDao
    companion object {
        /** 当前数据库版本；加迁移时同步改这里 */
        const val VERSION = 45

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
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
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
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
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                db.execSQL("CREATE TABLE IF NOT EXISTS app_power (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, at INTEGER NOT NULL, elapsedMs INTEGER NOT NULL, session TEXT NOT NULL, cpuMs INTEGER NOT NULL, txBytes INTEGER NOT NULL, rxBytes INTEGER NOT NULL, micMs INTEGER NOT NULL, charging INTEGER, chargeEpoch INTEGER NOT NULL, capacityMah REAL NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_app_power_at ON app_power(at)")
            }
        }
        /** 声纹：句子相似度、录音段声纹处理时间、样本与排除表 */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                db.execSQL("ALTER TABLE transcript_lines ADD COLUMN speakerScore REAL")
                db.execSQL("ALTER TABLE segments ADD COLUMN voiceprintProcessedAt INTEGER")
                db.execSQL("CREATE TABLE IF NOT EXISTS voiceprint_samples (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, source TEXT NOT NULL, sourceLineId INTEGER, segmentId INTEGER, groupKey TEXT, vector BLOB NOT NULL, durationMillis INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_voiceprint_samples_segmentId_groupKey ON voiceprint_samples(segmentId, groupKey)")
                db.execSQL("CREATE TABLE IF NOT EXISTS voiceprint_exclusions (segmentId INTEGER NOT NULL, groupKey TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(segmentId, groupKey))")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                db.execSQL("CREATE TABLE IF NOT EXISTS ask_conversations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, createdAt INTEGER NOT NULL, title TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS ask_messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, conversationId INTEGER NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL, sourcesJson TEXT NOT NULL, at INTEGER NOT NULL, error TEXT, FOREIGN KEY(conversationId) REFERENCES ask_conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ask_messages_conversationId ON ask_messages(conversationId)")
            }
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                db.execSQL("CREATE TABLE IF NOT EXISTS reviews (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, type TEXT NOT NULL, startDate TEXT NOT NULL, endDate TEXT NOT NULL, brief TEXT NOT NULL, sectionsJson TEXT NOT NULL, generatedAt INTEGER NOT NULL, model TEXT NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_reviews_type_startDate_endDate ON reviews(type, startDate, endDate)")
            }
        }
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                db.execSQL("ALTER TABLE segments ADD COLUMN playbackMillis INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
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
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                db.execSQL("ALTER TABLE memory_items ADD COLUMN doneAt INTEGER")
                db.execSQL("ALTER TABLE memory_items ADD COLUMN statusChangedAt INTEGER")
                db.execSQL("ALTER TABLE memory_items ADD COLUMN sortOrder INTEGER")
                db.execSQL("UPDATE memory_items SET doneAt = createdAt WHERE status = 'Done'")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_items_dueAt ON memory_items(dueAt)")
            }
        }
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                db.execSQL("ALTER TABLE ask_messages ADD COLUMN actionJson TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS embeddings (kind TEXT NOT NULL, refId INTEGER NOT NULL, model TEXT NOT NULL, dim INTEGER NOT NULL, vector BLOB NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(kind, refId))")
                SearchIndex.install(db::execSQL, backfill = true)
            }
        }
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                db.execSQL("CREATE TABLE IF NOT EXISTS skill_cards (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, skillId TEXT NOT NULL, skillVersion INTEGER NOT NULL, skillName TEXT NOT NULL, icon TEXT NOT NULL, date INTEGER NOT NULL, entity TEXT NOT NULL, at INTEGER NOT NULL, endAt INTEGER, title TEXT NOT NULL, summary TEXT NOT NULL, fieldsJson TEXT NOT NULL, valuesJson TEXT NOT NULL, evidenceJson TEXT NOT NULL, generatedAt INTEGER NOT NULL, manual INTEGER NOT NULL, deletedAt INTEGER, aliasesJson TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_skill_cards_date ON skill_cards(date)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_skill_cards_skillId_date ON skill_cards(skillId, date)")
                db.execSQL("CREATE TABLE IF NOT EXISTS skill_runs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, skillId TEXT NOT NULL, date INTEGER NOT NULL, at INTEGER NOT NULL, ok INTEGER NOT NULL, result TEXT NOT NULL, tokens INTEGER NOT NULL, kind TEXT NOT NULL, retryable INTEGER NOT NULL, purpose TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_skill_runs_skillId_date ON skill_runs(skillId, date)")
            }
        }
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                db.execSQL("ALTER TABLE memory_items ADD COLUMN mergedInto INTEGER")
                db.execSQL("ALTER TABLE memory_items ADD COLUMN noMerge INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                db.execSQL("ALTER TABLE memory_events ADD COLUMN starred INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE memory_events ADD COLUMN starredAt INTEGER")
            }
        }
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                db.execSQL("CREATE TABLE IF NOT EXISTS llm_usage_v2 (day TEXT NOT NULL, purpose TEXT NOT NULL, promptTokens INTEGER NOT NULL, completionTokens INTEGER NOT NULL, calls INTEGER NOT NULL, PRIMARY KEY(day, purpose))")
                db.execSQL("INSERT INTO llm_usage_v2 (day, purpose, promptTokens, completionTokens, calls) SELECT day, 'Other', promptTokens, completionTokens, calls FROM llm_usage")
                db.execSQL("DROP TABLE llm_usage")
            }
        }
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                db.execSQL("ALTER TABLE memory_items ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE memory_items ADD COLUMN editedAt INTEGER")
                db.execSQL("ALTER TABLE memory_items ADD COLUMN doneHintLineId INTEGER")
                db.execSQL("ALTER TABLE memory_items ADD COLUMN doneHintAt INTEGER")
                db.execSQL("ALTER TABLE memory_items ADD COLUMN dismissedDoneLinesJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE memory_items ADD COLUMN reminderVersion INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                db.execSQL("ALTER TABLE transcript_lines ADD COLUMN voiceInputJson TEXT")
                db.execSQL("ALTER TABLE transcript_lines ADD COLUMN voiceInputPendingUntil INTEGER")
            }
        }
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                // 重建表会移除条目的搜索触发器；跨表触发器也先撤下，改名后统一恢复。
                db.execSQL("DROP TRIGGER IF EXISTS search_event_hidden")
                db.execSQL("CREATE TABLE memory_items_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, eventId INTEGER, category TEXT NOT NULL, kind TEXT, content TEXT NOT NULL, dueText TEXT, dueAt INTEGER, who TEXT, lineId INTEGER, status TEXT NOT NULL, createdAt INTEGER NOT NULL, doneAt INTEGER, statusChangedAt INTEGER, sortOrder INTEGER, mergedInto INTEGER, noMerge INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0, editedAt INTEGER, doneHintLineId INTEGER, doneHintAt INTEGER, dismissedDoneLinesJson TEXT NOT NULL DEFAULT '[]', reminderVersion INTEGER NOT NULL DEFAULT 0, origin TEXT NOT NULL DEFAULT 'digest', sourceText TEXT, FOREIGN KEY(eventId) REFERENCES memory_events(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("INSERT INTO memory_items_new (id,eventId,category,kind,content,dueText,dueAt,who,lineId,status,createdAt,doneAt,statusChangedAt,sortOrder,mergedInto,noMerge,deleted,editedAt,doneHintLineId,doneHintAt,dismissedDoneLinesJson,reminderVersion) SELECT id,eventId,category,kind,content,dueText,dueAt,who,lineId,status,createdAt,doneAt,statusChangedAt,sortOrder,mergedInto,noMerge,deleted,editedAt,doneHintLineId,doneHintAt,dismissedDoneLinesJson,reminderVersion FROM memory_items")
                db.execSQL("DROP TABLE memory_items")
                db.execSQL("ALTER TABLE memory_items_new RENAME TO memory_items")
                listOf("eventId", "status", "dueAt").forEach { column ->
                    db.execSQL("CREATE INDEX index_memory_items_$column ON memory_items($column)")
                }
                SearchIndex.install(db::execSQL, backfill = false)
            }
        }
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                db.execSQL("ALTER TABLE memory_items ADD COLUMN strongReminder INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                db.execSQL("ALTER TABLE memory_events ADD COLUMN editedAt INTEGER")
                db.execSQL("ALTER TABLE memory_events ADD COLUMN originalTitle TEXT")
                db.execSQL("ALTER TABLE memory_events ADD COLUMN originalSummary TEXT")
                db.execSQL("ALTER TABLE memory_events ADD COLUMN originalPointsJson TEXT")
            }
        }
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                db.execSQL("ALTER TABLE segments ADD COLUMN firstFailedAt INTEGER")
                // 老数据优先使用首次失败日志；没有日志的段在启动维护时开始计时。
                db.execSQL("UPDATE segments SET firstFailedAt = (SELECT MIN(at) FROM asr_attempts WHERE segmentId = segments.id AND result = 'Failed') WHERE status = 'Failed'")
            }
        }
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                db.execSQL("ALTER TABLE transcript_lines ADD COLUMN originalText TEXT")
            }
        }
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                db.execSQL("CREATE TABLE IF NOT EXISTS env_sounds (segmentId INTEGER NOT NULL PRIMARY KEY, startAt INTEGER NOT NULL, endAt INTEGER NOT NULL, path TEXT NOT NULL, verdict TEXT NOT NULL, reason TEXT, tags TEXT NOT NULL, sounds TEXT NOT NULL, scores TEXT NOT NULL, skipped INTEGER NOT NULL, markMedia INTEGER NOT NULL, restored INTEGER NOT NULL, reportedWrong INTEGER NOT NULL, elapsedMs INTEGER NOT NULL, FOREIGN KEY(segmentId) REFERENCES segments(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_env_sounds_startAt ON env_sounds(startAt)")
            }
        }
        /** 全部迁移，按版本顺序。新增版本只在这里加一条；App 和迁移测试都用它，不再各写一份清单 */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                db.execSQL("ALTER TABLE segments ADD COLUMN asrSource TEXT")
                db.execSQL("ALTER TABLE segments ADD COLUMN redoPending INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS redo_snapshot (slot INTEGER NOT NULL PRIMARY KEY, jobId TEXT NOT NULL, at INTEGER NOT NULL, payload TEXT NOT NULL, applied INTEGER NOT NULL)")
            }
        }
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                db.execSQL("ALTER TABLE segments ADD COLUMN separateSpeakers INTEGER")
                db.execSQL("UPDATE segments SET separateSpeakers = 1 WHERE status IN ('Done', 'Empty')")
            }
        }
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                db.execSQL("CREATE TABLE IF NOT EXISTS import_records (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, pendingId TEXT NOT NULL, fileName TEXT NOT NULL, kind TEXT NOT NULL, startAt INTEGER NOT NULL, endAt INTEGER NOT NULL, byChannel INTEGER NOT NULL, status TEXT NOT NULL, error TEXT, cardId INTEGER, ingestComplete INTEGER NOT NULL, revision INTEGER NOT NULL, summaryRequested INTEGER NOT NULL, speakersReady INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX index_import_records_pendingId ON import_records(pendingId)")
                db.execSQL("CREATE INDEX index_import_records_startAt ON import_records(startAt)")
                db.execSQL("ALTER TABLE segments ADD COLUMN importRecordId INTEGER REFERENCES import_records(id) ON DELETE SET NULL")
                db.execSQL("ALTER TABLE segments ADD COLUMN importChannel INTEGER")
                db.execSQL("ALTER TABLE segments ADD COLUMN speakerCount INTEGER")
                db.execSQL("CREATE INDEX index_segments_importRecordId ON segments(importRecordId)")
                ImportRecordSchema.install(db)
            }
        }
        /** 卡 079 寻物 */
        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) = ThingSchema.create(db)
        }
        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                db.execSQL("CREATE TABLE IF NOT EXISTS privacy_file_cleanup (path TEXT NOT NULL PRIMARY KEY)")
            }
        }
        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) = PendingDigestSchema.create(db)
        }
        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                db.execSQL("CREATE TABLE IF NOT EXISTS import_fingerprints (fingerprint TEXT NOT NULL PRIMARY KEY, seenAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS folder_observations (folderId TEXT NOT NULL, documentId TEXT NOT NULL, size INTEGER NOT NULL, modified INTEGER NOT NULL, ignored INTEGER NOT NULL, fingerprint TEXT, PRIMARY KEY(folderId, documentId))")
            }
        }
        val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                db.execSQL("CREATE TABLE IF NOT EXISTS device_transfers (`key` TEXT NOT NULL PRIMARY KEY, device TEXT NOT NULL, fileId TEXT NOT NULL, name TEXT NOT NULL, size INTEGER, startedAt INTEGER, duration INTEGER, format TEXT NOT NULL, fromMillis INTEGER NOT NULL, toMillis INTEGER NOT NULL, phoneStart INTEGER NOT NULL, received INTEGER NOT NULL, imported INTEGER NOT NULL, deleted INTEGER NOT NULL)")
            }
        }
        /** 足迹（PR #19）：全天定位点、地名缓存（含手动改名与“家”）；原为 29→30，与 0.2.20/0.2.21 的版本顺延为 31→32 */
        val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                LocationSchema.create(db)
                PlaceSchema.create(db)
                PlaceSchema.addManual(db)
            }
        }
        val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) = TopicSchema.create(db)
        }
        /** 卡 183：只建测量通过的两个索引；Room 的升级事务负责中断回滚。 */
        val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_segments_status_voiceprintProcessedAt` ON `segments` (`status`, `voiceprintProcessedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transcript_lines_startAt` ON `transcript_lines` (`startAt`)")
            }
        }

        /** 第二大脑：灵感收件箱、项目长期记忆、AI 分析模板与结果、Agent 桥接。 */
        val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                IdeaSchema.create(db)
                ProjectSchema.create(db)
                AnalysisSchema.create(db)
                AgentSchema.create(db)
            }
        }
        /** M2+：决策记忆、主动跟进、统一收集箱、自动化规则、记忆修订日志、MCP 客户端。 */
        val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                DecisionSchema.create(db)
                FollowupSchema.create(db)
                CaptureItemSchema.create(db)
                RuleSchema.create(db)
                RevisionSchema.create(db)
                McpSchema.create(db)
            }
        }
        val MIGRATION_36_37 = object : Migration(36, 37) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                // 收集箱内容提取深度：已保存/已提取/已分析，历史行按 OCR 结果回填
                // v35→36 建表时按当前 schema 已含 extracted 列，这里只在列缺失时补建
                val hasExtracted = db.prepare("SELECT 1 FROM pragma_table_info('capture_items') WHERE name = 'extracted'").use { it.step() }
                if (!hasExtracted) {
                    db.execSQL("ALTER TABLE capture_items ADD COLUMN extracted TEXT NOT NULL DEFAULT 'Saved'")
                }
                db.execSQL("UPDATE capture_items SET extracted = 'Extracted' WHERE ocrText IS NOT NULL AND ocrText != '' OR kind = 'Text'")
                db.execSQL("UPDATE capture_items SET extracted = 'Analyzed' WHERE ideaId IS NOT NULL")
            }
        }
        val MIGRATION_37_38 = object : Migration(37, 38) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                // 低置信转写复核：人工复核过的时间；null 表示还没复核
                db.execSQL("ALTER TABLE transcript_lines ADD COLUMN reviewedAt INTEGER")
            }
        }
        /** 发行线：memory_items.recordedAt + 回填；主干线：文件夹录音表。 */
        val MIGRATION_38_39 = object : Migration(38, 39) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                // 时间语义分离：新增“录音时间”（说出来的时刻），与 dueAt（提及时间）、createdAt（处理时间）分开；
                // 存量行从原话行回填，手打条目保持 null。
                db.execSQL("ALTER TABLE memory_items ADD COLUMN recordedAt INTEGER")
                db.execSQL("UPDATE memory_items SET recordedAt = (SELECT startAt FROM transcript_lines WHERE transcript_lines.id = memory_items.lineId) WHERE lineId IS NOT NULL")
                app.resound.importer.FolderRecordingSchema.create(db)
            }
        }
        /** 发行线：memory_items.valueScore + 回填；主干线：夜间表族（39）。 */
        val MIGRATION_39_40 = object : Migration(39, 40) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                // 记忆价值分层：条目存内在分（自身属性）；星标/重点父事件加成查询时另算，不落库。
                // 与 shared MemoryValueRules.itemScore 同公式；改规则时两边一起改。
                db.execSQL("ALTER TABLE memory_items ADD COLUMN valueScore INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""UPDATE memory_items SET valueScore = MIN(100,
                    CASE category WHEN '约定' THEN 45 WHEN '待办' THEN 40 WHEN '备忘' THEN 25 ELSE 15 END
                    + CASE WHEN kind IN ('我答应别人','别人答应我') THEN 15 ELSE 0 END
                    + CASE WHEN dueAt IS NOT NULL THEN 15 ELSE 0 END
                    + CASE WHEN who IS NOT NULL AND who != '' THEN 10 ELSE 0 END
                    + CASE WHEN origin IS NOT NULL AND origin != 'digest' THEN 10 ELSE 0 END)""")
                app.resound.night.NightSchema.create(db)
            }
        }
        /** 发行线：scene_sessions；主干线：segments.speakerIntervals。 */
        val MIGRATION_40_41 = object : Migration(40, 41) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS scene_sessions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    kind TEXT NOT NULL, source TEXT NOT NULL, startedAt INTEGER NOT NULL, endedAt INTEGER, reason TEXT)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scene_sessions_startedAt ON scene_sessions(startedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scene_sessions_kind ON scene_sessions(kind)")
                val hasSegments = db.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name='segments'").use { it.step() }
                if (hasSegments) {
                    val hasSpeaker = db.prepare("SELECT 1 FROM pragma_table_info('segments') WHERE name = 'speakerIntervals'").use { it.step() }
                    if (!hasSpeaker) db.execSQL("ALTER TABLE segments ADD COLUMN speakerIntervals TEXT")
                }
            }
        }

        /** 发行线：topics.seriesKey + 回填；主干线：nights.kind/windowId + 唯一索引改组。 */
        val MIGRATION_41_42 = object : Migration(41, 42) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                // 同系列会议回链：seriesKey 缺失才补列，保证迁移可重复执行。
                val hasSeries = db.prepare("SELECT 1 FROM pragma_table_info('topics') WHERE name = 'seriesKey'").use { it.step() }
                if (!hasSeries) {
                    db.execSQL("ALTER TABLE topics ADD COLUMN seriesKey TEXT")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_topics_seriesKey` ON `topics` (`seriesKey`)")
                }
                // 与 TopicPolicy.seriesKey 同口径：去空白小写；匿名专题保持 NULL。
                db.execSQL("""UPDATE topics SET seriesKey = REPLACE(REPLACE(LOWER(TRIM(name)), ' ', ''), '　', '')
                    WHERE name IS NOT NULL AND TRIM(name) != ''""")
                // 老线设备可能尚无 nights 表（夜间表族在 39_40 由 Schema.create 补齐）。
                val hasNights = db.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name='nights'").use { it.step() }
                if (hasNights) {
                    val nightCols = db.prepare("PRAGMA table_info(nights)").use { cursor ->
                        buildSet { while (cursor.step()) add(cursor.getText(1)) }
                    }
                    for (name in listOf("kind", "windowId")) if (name !in nightCols) {
                        db.execSQL("ALTER TABLE nights ADD COLUMN `$name` TEXT NOT NULL DEFAULT 'night'")
                    }
                    db.execSQL("DROP INDEX IF EXISTS index_nights_date")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_nights_date_kind` ON `nights` (`date`, `kind`)")
                }
            }
        }

        /** 发行线：idea_entries/noMergeIdsJson/seriesId；主干线：transcript_lines.textEditedAt（卡211）。 */
        val MIGRATION_42_43 = object : Migration(42, 43) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                // 灵感发展轨迹表 + “不相关”标记 + 周期会议链锚；全部幂等，可重复执行。
                val hasEntries = db.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name='idea_entries'").use { it.step() }
                if (!hasEntries) {
                    db.execSQL("""CREATE TABLE idea_entries (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        ideaId INTEGER NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL, origin TEXT NOT NULL,
                        sourceLineId INTEGER, sourceSegmentId INTEGER, sourceItemId INTEGER, at INTEGER NOT NULL,
                        FOREIGN KEY(ideaId) REFERENCES ideas(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_idea_entries_ideaId ON idea_entries(ideaId)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_idea_entries_at ON idea_entries(at)")
                }
                val hasNoMerge = db.prepare("SELECT 1 FROM pragma_table_info('ideas') WHERE name = 'noMergeIdsJson'").use { it.step() }
                if (!hasNoMerge) db.execSQL("ALTER TABLE ideas ADD COLUMN noMergeIdsJson TEXT NOT NULL DEFAULT '[]'")
                val hasSeriesId = db.prepare("SELECT 1 FROM pragma_table_info('topics') WHERE name = 'seriesId'").use { it.step() }
                if (!hasSeriesId) db.execSQL("ALTER TABLE topics ADD COLUMN seriesId INTEGER")
                // 最小结构库（单测自建）可能无 transcript_lines；缺失时跳过，真实链路由 44_45 兜底。
                val hasLines = db.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name='transcript_lines'").use { it.step() }
                if (hasLines) {
                    val lineCols = db.prepare("PRAGMA table_info(transcript_lines)").use { cursor ->
                        buildSet { while (cursor.step()) add(cursor.getText(1)) }
                    }
                    if ("textEditedAt" !in lineCols) db.execSQL("ALTER TABLE transcript_lines ADD COLUMN `textEditedAt` INTEGER")
                }
            }
        }

        /** 主干合并：屏幕时间四表、设备播放记录、segments 播放整理闸列、标记表；主干线：asr_usage token 列。全部幂等，老线（≤38）已建的对象原样保留。 */
        val MIGRATION_43_44 = object : Migration(43, 44) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                // 屏幕时间四张表；事件唯一索引（at,pkg,type,cls）让重复拉取靠冲突忽略去重。
                // SQL 与实体 createSql 对齐：DEFAULT 与 PRIMARY KEY 写法保持 Room 新装形态，避免两路径 sqlite_master 文本分叉。
                db.execSQL("CREATE TABLE IF NOT EXISTS `screen_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at` INTEGER NOT NULL, `pkg` TEXT NOT NULL, `cls` TEXT NOT NULL, `type` INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_screen_events_at_pkg_type_cls` ON `screen_events` (`at`, `pkg`, `type`, `cls`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `screen_app_names` (`pkg` TEXT NOT NULL, `name` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`pkg`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `screen_pull_state` (`source` TEXT NOT NULL, `coveredUntil` INTEGER, `floor` INTEGER NOT NULL, PRIMARY KEY(`source`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `screen_coverage_gaps` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `source` TEXT NOT NULL, `fromAt` INTEGER NOT NULL, `untilAt` INTEGER NOT NULL, `code` TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_screen_coverage_gaps_source_fromAt` ON `screen_coverage_gaps` (`source`, `fromAt`)")
                // 设备播放记录表；untilAt 播放中为 null。SQL 与实体 createSql 对齐，避免两路径 sqlite_master 文本分叉。
                db.execSQL("CREATE TABLE IF NOT EXISTS `playback_rows` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionKey` TEXT NOT NULL, `pkg` TEXT NOT NULL, `name` TEXT NOT NULL, `title` TEXT, `subtitle` TEXT, `fromAt` INTEGER NOT NULL, `untilAt` INTEGER, `lastSeenAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_rows_fromAt` ON `playback_rows` (`fromAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_rows_sessionKey` ON `playback_rows` (`sessionKey`)")
                // 外放「仍要转写」片段的整理闸列；SQLite 无 ADD COLUMN IF NOT EXISTS，先查列再补。
                val hasSegments = db.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name='segments'").use { it.step() }
                if (hasSegments) {
                    val hasPlaybackDigest = db.prepare("SELECT 1 FROM pragma_table_info('segments') WHERE name = 'playbackDigest'").use { it.step() }
                    if (!hasPlaybackDigest) db.execSQL("ALTER TABLE segments ADD COLUMN playbackDigest INTEGER NOT NULL DEFAULT 0")
                }
                app.resound.marks.MarkSchema.create(db)
                val hasAsr = db.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name='asr_usage'").use { it.step() }
                if (hasAsr) {
                    val asrCols = db.prepare("PRAGMA table_info(asr_usage)").use { cursor ->
                        buildSet { while (cursor.step()) add(cursor.getText(1)) }
                    }
                    for (name in listOf("inputTokens", "outputTokens")) if (name !in asrCols) {
                        db.execSQL("ALTER TABLE asr_usage ADD COLUMN $name INTEGER NOT NULL DEFAULT 0")
                    }
                }
            }
        }

        /** 合线兜底：两线在 34–44 用同一版本号加了不同对象，真实设备可能停在任一“v34–v44”形态；
         *  本步把两侧全部变更幂等补齐（表用 IF NOT EXISTS、列走 pragma 守卫、回填按 UPDATE 重算），统一收口到 45。 */
        val MIGRATION_44_45 = object : Migration(44, 45) {
            override fun migrate(db: androidx.sqlite.SQLiteConnection) {
                // 主干表族：各 Schema.create 内部均为 CREATE TABLE IF NOT EXISTS，可重复执行。
                app.resound.importer.FolderRecordingSchema.create(db)
                app.resound.night.NightSchema.create(db)
                app.resound.marks.MarkSchema.create(db)
                // 场景会话 / 灵感轨迹（与 MIGRATION_40_41 / 42_43 同一 SQL）
                db.execSQL("""CREATE TABLE IF NOT EXISTS scene_sessions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    kind TEXT NOT NULL, source TEXT NOT NULL, startedAt INTEGER NOT NULL, endedAt INTEGER, reason TEXT)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scene_sessions_startedAt ON scene_sessions(startedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scene_sessions_kind ON scene_sessions(kind)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS idea_entries (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    ideaId INTEGER NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL, origin TEXT NOT NULL,
                    sourceLineId INTEGER, sourceSegmentId INTEGER, sourceItemId INTEGER, at INTEGER NOT NULL,
                    FOREIGN KEY(ideaId) REFERENCES ideas(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_idea_entries_ideaId ON idea_entries(ideaId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_idea_entries_at ON idea_entries(at)")
                // 屏幕时间四表 + 播放记录（与 MIGRATION_43_44 同一 SQL）
                db.execSQL("CREATE TABLE IF NOT EXISTS `screen_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at` INTEGER NOT NULL, `pkg` TEXT NOT NULL, `cls` TEXT NOT NULL, `type` INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_screen_events_at_pkg_type_cls` ON `screen_events` (`at`, `pkg`, `type`, `cls`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `screen_app_names` (`pkg` TEXT NOT NULL, `name` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`pkg`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `screen_pull_state` (`source` TEXT NOT NULL, `coveredUntil` INTEGER, `floor` INTEGER NOT NULL, PRIMARY KEY(`source`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `screen_coverage_gaps` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `source` TEXT NOT NULL, `fromAt` INTEGER NOT NULL, `untilAt` INTEGER NOT NULL, `code` TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_screen_coverage_gaps_source_fromAt` ON `screen_coverage_gaps` (`source`, `fromAt`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `playback_rows` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionKey` TEXT NOT NULL, `pkg` TEXT NOT NULL, `name` TEXT NOT NULL, `title` TEXT, `subtitle` TEXT, `fromAt` INTEGER NOT NULL, `untilAt` INTEGER, `lastSeenAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_rows_fromAt` ON `playback_rows` (`fromAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_rows_sessionKey` ON `playback_rows` (`sessionKey`)")
                // 列：两线全部增量列逐一守卫补齐
                fun hasColumn(table: String, name: String) = db.prepare(
                    "SELECT 1 FROM pragma_table_info('$table') WHERE name = '$name'").use { it.step() }
                fun hasTable(name: String) = db.prepare(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name='$name'").use { it.step() }
                if (hasTable("memory_items")) {
                    if (!hasColumn("memory_items", "recordedAt")) db.execSQL("ALTER TABLE memory_items ADD COLUMN recordedAt INTEGER")
                    if (!hasColumn("memory_items", "valueScore")) db.execSQL("ALTER TABLE memory_items ADD COLUMN valueScore INTEGER NOT NULL DEFAULT 0")
                }
                if (hasTable("topics")) {
                    if (!hasColumn("topics", "seriesKey")) db.execSQL("ALTER TABLE topics ADD COLUMN seriesKey TEXT")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_topics_seriesKey` ON `topics` (`seriesKey`)")
                    if (!hasColumn("topics", "seriesId")) db.execSQL("ALTER TABLE topics ADD COLUMN seriesId INTEGER")
                }
                if (hasTable("ideas") && !hasColumn("ideas", "noMergeIdsJson")) db.execSQL("ALTER TABLE ideas ADD COLUMN noMergeIdsJson TEXT NOT NULL DEFAULT '[]'")
                if (hasTable("segments")) {
                    if (!hasColumn("segments", "speakerIntervals")) db.execSQL("ALTER TABLE segments ADD COLUMN speakerIntervals TEXT")
                    if (!hasColumn("segments", "playbackDigest")) db.execSQL("ALTER TABLE segments ADD COLUMN playbackDigest INTEGER NOT NULL DEFAULT 0")
                }
                if (hasTable("transcript_lines") && !hasColumn("transcript_lines", "textEditedAt")) db.execSQL("ALTER TABLE transcript_lines ADD COLUMN `textEditedAt` INTEGER")
                if (hasTable("asr_usage")) {
                    for (name in listOf("inputTokens", "outputTokens")) if (!hasColumn("asr_usage", name)) {
                        db.execSQL("ALTER TABLE asr_usage ADD COLUMN $name INTEGER NOT NULL DEFAULT 0")
                    }
                }
                // nights 由上方 NightSchema.create 保证存在；老库已建者按列守卫补齐。
                for (name in listOf("kind", "windowId")) if (!hasColumn("nights", name)) {
                    db.execSQL("ALTER TABLE nights ADD COLUMN `$name` TEXT NOT NULL DEFAULT 'night'")
                }
                // 索引改组与幂等回填（UPDATE 重算结果一致，重复执行安全）
                db.execSQL("DROP INDEX IF EXISTS index_nights_date")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_nights_date_kind` ON `nights` (`date`, `kind`)")
                if (hasTable("memory_items") && hasTable("transcript_lines")) {
                    db.execSQL("UPDATE memory_items SET recordedAt = (SELECT startAt FROM transcript_lines WHERE transcript_lines.id = memory_items.lineId) WHERE lineId IS NOT NULL")
                }
                if (hasTable("memory_items")) db.execSQL("""UPDATE memory_items SET valueScore = MIN(100,
                    CASE category WHEN '约定' THEN 45 WHEN '待办' THEN 40 WHEN '备忘' THEN 25 ELSE 15 END
                    + CASE WHEN kind IN ('我答应别人','别人答应我') THEN 15 ELSE 0 END
                    + CASE WHEN dueAt IS NOT NULL THEN 15 ELSE 0 END
                    + CASE WHEN who IS NOT NULL AND who != '' THEN 10 ELSE 0 END
                    + CASE WHEN origin IS NOT NULL AND origin != 'digest' THEN 10 ELSE 0 END)""")
                if (hasTable("topics")) db.execSQL("""UPDATE topics SET seriesKey = REPLACE(REPLACE(LOWER(TRIM(name)), ' ', ''), '　', '')
                    WHERE name IS NOT NULL AND TRIM(name) != ''""")
            }
        }

        val MIGRATIONS get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
            MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17,
            MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25,
            MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33,
            MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36, MIGRATION_36_37, MIGRATION_37_38, MIGRATION_38_39, MIGRATION_39_40, MIGRATION_40_41, MIGRATION_41_42, MIGRATION_42_43,
            MIGRATION_43_44, MIGRATION_44_45)
    }
}

@Suppress("KotlinNoActualForExpect")
expect object CaptureDatabaseConstructor : RoomDatabaseConstructor<CaptureDatabase> {
    override fun initialize(): CaptureDatabase
}
