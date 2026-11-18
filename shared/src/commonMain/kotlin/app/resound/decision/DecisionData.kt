package app.resound.decision

import androidx.room.*
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow

/**
 * 决策记忆：一条明确的决定，带生效状态与修订链。
 * 新决定替代旧决定时旧条目标为 Superseded 但保留原文——过期的决定不能再被当作当前立场。
 * topicKey 是归一化主题指纹，用于同主题冲突检测。
 */
@Entity(tableName = "decisions", indices = [Index("status"), Index("projectId"), Index("topicKey"), Index("createdAt")])
data class DecisionRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val detail: String,
    /** 见 DecisionStatus：Active / Superseded / Rejected / Withdrawn */
    val status: String = DecisionStatus.Active.name,
    /** 可信度：Sure（明确说过）/ Likely（推断）/ Tentative（随口） */
    val confidence: String = "Sure",
    /** 归一化主题指纹，同主题冲突检测用 */
    val topicKey: String = "",
    val projectId: Long? = null,
    /** 否决原因（Rejected 时必填语义，不强制） */
    val rejectedReason: String? = null,
    /** 被哪条新决定替代 */
    val supersededBy: Long? = null,
    /** 证据：原话行 / 提取项 / 事件 / 灵感 */
    val sourceLineId: Long? = null,
    val sourceItemId: Long? = null,
    val sourceEventId: Long? = null,
    val sourceIdeaId: Long? = null,
    /** 原话快照（转写重做后仍可引证） */
    val sourceText: String? = null,
    val decidedAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

@Dao
abstract class DecisionDao {
    @Transaction open suspend fun <T> transaction(block: suspend () -> T): T = block()

    @Insert abstract suspend fun insert(row: DecisionRow): Long
    @Update abstract suspend fun update(row: DecisionRow)

    @Query("SELECT * FROM decisions WHERE id = :id") abstract suspend fun decision(id: Long): DecisionRow?
    @Query("SELECT * FROM decisions WHERE id = :id") abstract fun observeDecision(id: Long): Flow<DecisionRow?>

    @Query("SELECT * FROM decisions WHERE deleted = 0 AND status = 'Active' ORDER BY decidedAt DESC, id DESC")
    abstract fun observeActive(): Flow<List<DecisionRow>>

    @Query("SELECT * FROM decisions WHERE deleted = 0 AND status != 'Active' ORDER BY updatedAt DESC, id DESC")
    abstract fun observeHistory(): Flow<List<DecisionRow>>

    @Query("SELECT * FROM decisions WHERE deleted = 0 AND projectId = :projectId ORDER BY decidedAt DESC, id DESC")
    abstract fun observeForProject(projectId: Long): Flow<List<DecisionRow>>

    /** 由某条提取项导入过的决定（判重，含墓碑防复活） */
    @Query("SELECT * FROM decisions WHERE sourceItemId = :itemId LIMIT 1")
    abstract suspend fun bySourceItem(itemId: Long): DecisionRow?

    /** 同主题现存生效决定：冲突检测——同一 topicKey 只能有一条 Active */
    @Query("SELECT * FROM decisions WHERE deleted = 0 AND status = 'Active' AND topicKey = :topicKey AND id != :excludeId")
    abstract suspend fun activeSameTopic(topicKey: String, excludeId: Long = 0): List<DecisionRow>

    @Query("""SELECT * FROM decisions WHERE deleted = 0
        AND (title LIKE :pattern ESCAPE '\' OR detail LIKE :pattern ESCAPE '\' OR rejectedReason LIKE :pattern ESCAPE '\')
        ORDER BY decidedAt DESC, id DESC LIMIT 200""")
    abstract fun search(pattern: String): Flow<List<DecisionRow>>

    @Query("UPDATE decisions SET status = :status, updatedAt = :at WHERE id = :id AND deleted = 0")
    abstract suspend fun setStatus(id: Long, status: String, at: Long): Int

    @Query("UPDATE decisions SET status = 'Superseded', supersededBy = :byId, updatedAt = :at WHERE id = :id AND status = 'Active'")
    abstract suspend fun supersede(id: Long, byId: Long, at: Long): Int

    @Query("UPDATE decisions SET status = 'Rejected', rejectedReason = :reason, updatedAt = :at WHERE id = :id AND deleted = 0")
    abstract suspend fun reject(id: Long, reason: String?, at: Long): Int

    @Query("UPDATE decisions SET projectId = :projectId, updatedAt = :at WHERE id = :id AND deleted = 0")
    abstract suspend fun setProject(id: Long, projectId: Long?, at: Long)

    @Query("UPDATE decisions SET deleted = 1, updatedAt = :at WHERE id = :id")
    abstract suspend fun delete(id: Long, at: Long)

    @Query("SELECT COUNT(*) FROM decisions WHERE deleted = 0 AND status = 'Active'")
    abstract fun observeActiveCount(): Flow<Int>
}

object DecisionSchema {
    fun create(db: SQLiteConnection) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS decisions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            title TEXT NOT NULL, detail TEXT NOT NULL, status TEXT NOT NULL, confidence TEXT NOT NULL,
            topicKey TEXT NOT NULL, projectId INTEGER, rejectedReason TEXT, supersededBy INTEGER,
            sourceLineId INTEGER, sourceItemId INTEGER, sourceEventId INTEGER, sourceIdeaId INTEGER,
            sourceText TEXT, decidedAt INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
            deleted INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_decisions_status ON decisions(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_decisions_projectId ON decisions(projectId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_decisions_topicKey ON decisions(topicKey)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_decisions_createdAt ON decisions(createdAt)")
    }
}

/** 生效状态：Active=当前有效；Superseded=已被新决定替代；Rejected=已否决（带原因）；Withdrawn=本人撤回。 */
enum class DecisionStatus(val label: String) {
    Active("生效中"), Superseded("已替代"), Rejected("已否决"), Withdrawn("已撤回");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: Active
    }
}

enum class DecisionConfidence(val label: String) {
    Sure("明确"), Likely("推断"), Tentative("随口");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: Sure
    }
}

object DecisionRules {
    /** 主题指纹：取标题+关键词归一化；同主题的新旧决定靠它发现冲突。 */
    fun topicKey(text: String): String {
        val normalized = StringBuilder(text.length)
        for (c in text.lowercase()) if (c.isLetterOrDigit()) normalized.append(c)
        return normalized.toString().take(48)
    }

    /**
     * 冲突判定：同 topicKey 的生效决定互斥。
     * 返回需要提示用户处理的冲突对：新决定进来时同主题的 Active 旧决定。
     */
    fun conflicts(existing: List<DecisionRow>, topicKey: String): List<DecisionRow> =
        if (topicKey.isEmpty()) emptyList() else existing.filter { it.status == DecisionStatus.Active.name && it.topicKey == topicKey }

    /** 一句话摘要判断这是否是"随口想法"而非承诺：含"也许/可能/随便说说/回头再说"等降级为 Tentative。 */
    fun guessConfidence(text: String): DecisionConfidence {
        val tentative = listOf("也许", "可能", "说不定", "随便", "回头再", "以后再说", "考虑一下", "未必")
        val sure = listOf("决定", "就这么定", "确定", "一定", "必须", "不做", "就用", "放弃", "否决")
        return when {
            tentative.any { it in text } -> DecisionConfidence.Tentative
            sure.any { it in text } -> DecisionConfidence.Sure
            else -> DecisionConfidence.Likely
        }
    }
}
