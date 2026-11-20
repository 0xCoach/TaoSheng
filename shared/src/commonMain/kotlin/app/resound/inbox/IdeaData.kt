package app.resound.inbox

import androidx.room.*
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow

/**
 * 灵感收件箱：独立的一条灵感，可由大模型整理出的“灵感”提取项生成，也可手动标记。
 * sourceLineId / sourceSegmentId / sourceEventId 始终指回原始录音与句子，保证可溯源。
 */
@Entity(tableName = "ideas", indices = [Index("status"), Index("createdAt"), Index("projectId"), Index(value = ["fingerprint"]), Index("sourceLineId"), Index("sourceItemId")])
data class IdeaRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String,
    /** 见 IdeaStatus：待研究 / 研究中 / 已验证 / 已放弃 / 已完成 */
    val status: String = IdeaStatus.Research.name,
    /** 自由类别（产品 / 内容 / 商业 / 技术 / 生活 / 其他），手动与规则分类共用 */
    val category: String = "其他",
    val tagsJson: String = "[]",
    /** 归一化内容指纹，重复灵感检测用 */
    val fingerprint: String = "",
    /** 由整理提取项生成时指回 memory_items.id；手动新建为 null */
    val sourceItemId: Long? = null,
    /** 手动从某句原话标记时指回 transcript_lines.id */
    val sourceLineId: Long? = null,
    val sourceSegmentId: Long? = null,
    val sourceEventId: Long? = null,
    /** 原话快照（转写可能被重做，保留当时的原话） */
    val sourceText: String? = null,
    /** 聚合到同一条灵感的提取项编号列表 */
    val sourceItemIdsJson: String = "[]",
    /** 被合并进哪条灵感（重复灵感），合并后本条不再单独展示 */
    val mergedInto: Long? = null,
    /** 转为项目后记录项目编号；挂到项目下但未必由它转化也复用此字段 */
    val projectId: Long? = null,
    /** 转化去向：Todo / Project / null */
    val convertedKind: String? = null,
    val convertedId: Long? = null,
    /** 用户标记过“不相关”的疑似重复灵感编号；不再出现在相关建议里 */
    @ColumnInfo(defaultValue = "[]") val noMergeIdsJson: String = "[]",
    val createdAt: Long,
    val updatedAt: Long,
    val statusChangedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

/**
 * 灵感发展轨迹：每一次追加、状态变化、合并、转化都是独立一行，
 * 各自保留来源（原话/片段/提取项）、时间与原始文本——不覆盖、不合并旧内容。
 */
@Entity(tableName = "idea_entries", indices = [Index("ideaId"), Index("at")], foreignKeys = [
    ForeignKey(entity = IdeaRow::class, parentColumns = ["id"], childColumns = ["ideaId"], onDelete = ForeignKey.CASCADE)])
data class IdeaEntryRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ideaId: Long,
    /** Seed / Note / Line / Merged / Status / Todo / Project */
    val kind: String,
    /** 本条追加的原始文本或事件描述快照 */
    val text: String,
    /** 产生方：User / Digest / System——AI 整理结果与用户原话分开保存 */
    val origin: String,
    val sourceLineId: Long? = null,
    val sourceSegmentId: Long? = null,
    val sourceItemId: Long? = null,
    val at: Long,
)

@Dao
abstract class IdeaDao {
    @Transaction open suspend fun <T> transaction(block: suspend () -> T): T = block()

    @Insert abstract suspend fun insert(row: IdeaRow): Long
    @Update abstract suspend fun update(row: IdeaRow)

    @Query("SELECT * FROM ideas WHERE id = :id") abstract suspend fun idea(id: Long): IdeaRow?
    @Query("SELECT * FROM ideas WHERE id = :id") abstract fun observeIdea(id: Long): Flow<IdeaRow?>

    @Query("""SELECT * FROM ideas WHERE deleted = 0 AND mergedInto IS NULL
        ORDER BY CASE status WHEN 'Research' THEN 0 WHEN 'Studying' THEN 1 WHEN 'Verified' THEN 2 WHEN 'Done' THEN 3 ELSE 4 END,
        updatedAt DESC, id DESC""")
    abstract fun observeActive(): Flow<List<IdeaRow>>

    @Query("SELECT * FROM ideas WHERE deleted = 0 AND mergedInto IS NULL AND status = :status ORDER BY updatedAt DESC, id DESC")
    abstract fun observeByStatus(status: String): Flow<List<IdeaRow>>

    @Query("""SELECT * FROM ideas WHERE deleted = 0 AND mergedInto IS NULL
        ORDER BY CASE status WHEN 'Research' THEN 0 WHEN 'Studying' THEN 1 WHEN 'Verified' THEN 2 WHEN 'Done' THEN 3 ELSE 4 END,
        updatedAt DESC, id DESC""")
    abstract suspend fun activeList(): List<IdeaRow>

    @Query("SELECT * FROM ideas WHERE deleted = 0 AND mergedInto IS NULL AND projectId = :projectId ORDER BY updatedAt DESC, id DESC")
    abstract fun observeForProject(projectId: Long): Flow<List<IdeaRow>>

    @Query("SELECT * FROM ideas WHERE deleted = 0 AND mergedInto IS NULL AND projectId = :projectId ORDER BY updatedAt DESC, id DESC")
    abstract suspend fun forProject(projectId: Long): List<IdeaRow>

    /** 关联回填：link() 时把 ideas.projectId 一并写上（只填 NULL），与灵感页双写口径一致 */
    @Query("UPDATE ideas SET projectId = :projectId, updatedAt = :at WHERE id = :id AND deleted = 0 AND projectId IS NULL")
    abstract suspend fun adoptForProject(projectId: Long, id: Long, at: Long): Int

    @Query("SELECT * FROM ideas WHERE deleted = 0 AND mergedInto IS NULL AND sourceItemId = :itemId LIMIT 1")
    abstract suspend fun bySourceItem(itemId: Long): IdeaRow?

    /** 含墓碑与被合并的：同步时防止删掉/合并过的灵感被再次创建 */
    @Query("SELECT * FROM ideas WHERE sourceItemId = :itemId LIMIT 1")
    abstract suspend fun anyBySourceItem(itemId: Long): IdeaRow?

    @Query("SELECT * FROM ideas WHERE sourceLineId = :lineId AND deleted = 0 AND mergedInto IS NULL")
    abstract suspend fun bySourceLine(lineId: Long): List<IdeaRow>

    /** 同一音频片段在窗口内已建过灵感：快捷"记灵感"的幂等依据 */
    @Query("SELECT * FROM ideas WHERE sourceSegmentId = :segmentId AND createdAt > :since AND deleted = 0 LIMIT 1")
    abstract suspend fun bySourceSegmentWindow(segmentId: Long, since: Long): IdeaRow?

    /** 指纹精确命中：完全重复 */
    @Query("SELECT * FROM ideas WHERE deleted = 0 AND mergedInto IS NULL AND fingerprint = :fingerprint AND id != :excludeId")
    abstract suspend fun sameFingerprint(fingerprint: String, excludeId: Long = 0): List<IdeaRow>

    /** 前缀模糊命中：内容开头相同视为疑似重复，由界面确认合并 */
    @Query("""SELECT * FROM ideas WHERE deleted = 0 AND mergedInto IS NULL AND id != :excludeId
        AND (title LIKE :pattern ESCAPE '\' OR content LIKE :pattern ESCAPE '\') ORDER BY updatedAt DESC LIMIT 20""")
    abstract suspend fun similar(pattern: String, excludeId: Long = 0): List<IdeaRow>

    @Query("""SELECT * FROM ideas WHERE deleted = 0 AND mergedInto IS NULL
        AND (title LIKE :pattern ESCAPE '\' OR content LIKE :pattern ESCAPE '\' OR tagsJson LIKE :pattern ESCAPE '\' OR category LIKE :pattern ESCAPE '\')
        ORDER BY updatedAt DESC, id DESC LIMIT 200""")
    abstract fun search(pattern: String): Flow<List<IdeaRow>>

    @Query("""UPDATE ideas SET status = :status, statusChangedAt = :at, updatedAt = :at
        WHERE id = :id AND deleted = 0""")
    abstract suspend fun setStatus(id: Long, status: String, at: Long): Int

    @Query("UPDATE ideas SET projectId = :projectId, updatedAt = :at WHERE id = :id AND deleted = 0")
    abstract suspend fun setProject(id: Long, projectId: Long?, at: Long)

    @Query("UPDATE ideas SET convertedKind = :kind, convertedId = :convertedId, updatedAt = :at WHERE id = :id")
    abstract suspend fun markConverted(id: Long, kind: String, convertedId: Long, at: Long)

    /** 把 source 合并进 target：source 不再单列，其来源提取项并入 target 的聚合列表 */
    @Query("UPDATE ideas SET mergedInto = :target, updatedAt = :at WHERE id = :source")
    abstract suspend fun mergeInto(source: Long, target: Long, at: Long)

    @Query("UPDATE ideas SET sourceItemIdsJson = :ids, updatedAt = :at WHERE id = :id")
    abstract suspend fun updateSourceItems(id: Long, ids: String, at: Long)

    @Query("UPDATE ideas SET deleted = 1, updatedAt = :at WHERE id = :id")
    abstract suspend fun delete(id: Long, at: Long)

    @Query("UPDATE ideas SET noMergeIdsJson = :ids, updatedAt = :at WHERE id = :id")
    abstract suspend fun setNoMergeIds(id: Long, ids: String, at: Long)

    @Query("UPDATE ideas SET updatedAt = :at WHERE id = :id")
    abstract suspend fun touch(id: Long, at: Long)

    @Insert abstract suspend fun insertEntry(row: IdeaEntryRow): Long

    @Query("SELECT * FROM idea_entries WHERE ideaId = :ideaId ORDER BY at, id")
    abstract fun observeEntries(ideaId: Long): Flow<List<IdeaEntryRow>>

    @Query("SELECT * FROM idea_entries WHERE ideaId IN (:ideaIds) ORDER BY at, id")
    abstract suspend fun entriesFor(ideaIds: List<Long>): List<IdeaEntryRow>

    @Query("SELECT COUNT(*) FROM ideas WHERE deleted = 0 AND mergedInto IS NULL")
    abstract fun observeCount(): Flow<Int>
}

object IdeaSchema {
    fun create(db: SQLiteConnection) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS ideas (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            title TEXT NOT NULL, content TEXT NOT NULL, status TEXT NOT NULL, category TEXT NOT NULL,
            tagsJson TEXT NOT NULL, fingerprint TEXT NOT NULL, sourceItemId INTEGER, sourceLineId INTEGER,
            sourceSegmentId INTEGER, sourceEventId INTEGER, sourceText TEXT, sourceItemIdsJson TEXT NOT NULL,
            mergedInto INTEGER, projectId INTEGER, convertedKind TEXT, convertedId INTEGER,
            noMergeIdsJson TEXT NOT NULL DEFAULT '[]',
            createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, statusChangedAt INTEGER, deleted INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS idea_entries (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            ideaId INTEGER NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL, origin TEXT NOT NULL,
            sourceLineId INTEGER, sourceSegmentId INTEGER, sourceItemId INTEGER, at INTEGER NOT NULL,
            FOREIGN KEY(ideaId) REFERENCES ideas(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_idea_entries_ideaId ON idea_entries(ideaId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_idea_entries_at ON idea_entries(at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_ideas_status ON ideas(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_ideas_createdAt ON ideas(createdAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_ideas_projectId ON ideas(projectId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_ideas_fingerprint ON ideas(fingerprint)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_ideas_sourceLineId ON ideas(sourceLineId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_ideas_sourceItemId ON ideas(sourceItemId)")
    }
}

/** 灵感状态机；放弃后可回到待研究，其余单向推进。 */
enum class IdeaStatus(val label: String) {
    Research("待研究"), Studying("研究中"), Verified("已验证"), Done("已完成"), Abandoned("已放弃");

    companion object {
        fun of(name: String?): IdeaStatus = entries.firstOrNull { it.name == name } ?: Research
        /** 允许的状态迁移；Abandoned 只能回 Research */
        fun canMove(from: IdeaStatus, to: IdeaStatus): Boolean = when (from) {
            Research -> to in setOf(Studying, Verified, Done, Abandoned)
            Studying -> to in setOf(Research, Verified, Done, Abandoned)
            Verified -> to in setOf(Studying, Done, Abandoned)
            Done -> to in setOf(Studying)
            Abandoned -> to == Research
        }
        fun next(from: IdeaStatus): List<IdeaStatus> = entries.filter { canMove(from, it) }
    }
}

object IdeaRules {
    /** 归一化指纹：小写、去空白与常见标点。中英文混排也能稳定去重。 */
    fun fingerprint(text: String): String {
        val normalized = StringBuilder(text.length)
        for (c in text.lowercase()) {
            if (c.isLetterOrDigit()) normalized.append(c)
        }
        return normalized.toString().take(96)
    }

    /** LIKE 模式转义（\ % _），与 Dao 中 ESCAPE '\' 配套。 */
    fun likePattern(query: String): String {
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return "%$escaped%"
    }

    /** 前缀相似度用的模式：取内容前若干个有效字符做前缀匹配。 */
    fun prefixPattern(content: String): String {
        val head = content.trim().take(24)
        val escaped = head.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return "$escaped%"
    }

    /** 规则分类：无 AI 时按关键词粗分；返回建议类别。 */
    fun suggestCategory(text: String): String {
        val groups = listOf(
            "产品" to listOf("产品", "功能", "用户", "需求", "交互", "界面", "App", "应用", "体验"),
            "商业" to listOf("商业", "收入", "变现", "客户", "市场", "定价", "成本", "赚", "生意", "创业"),
            "内容" to listOf("内容", "视频", "文章", "写作", "选题", "标题", "文案", "公众号", "小红书", "账号"),
            "技术" to listOf("技术", "代码", "算法", "模型", "接口", "架构", "开发", "程序", "服务器"),
            "生活" to listOf("生活", "家人", "健康", "旅行", "吃", "运动", "孩子"),
        )
        var best = "其他"
        var bestScore = 0
        for ((category, words) in groups) {
            val score = words.count { it in text }
            if (score > bestScore) { bestScore = score; best = category }
        }
        return best
    }

    /** 两个灵感是否疑似重复：指纹相同，或前缀一致且长度相近。 */
    fun isDuplicate(fpA: String, fpB: String): Boolean =
        fpA.isNotEmpty() && fpB.isNotEmpty() && (fpA == fpB || fpA.startsWith(fpB.take(40)) || fpB.startsWith(fpA.take(40)))
}
