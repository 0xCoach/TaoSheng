package app.resound.followup

import androidx.room.*
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow

/**
 * 主动跟进项：一个"说过要做但还没下文"的承诺/待办/灵感。
 * kind=Suggestion 的条目是 AI 建议，绝不能当用户承诺对待——UI 与提醒都要区分开。
 */
@Entity(tableName = "followups", indices = [Index("status"), Index("kind"), Index("createdAt"), Index("nextCheckAt")])
data class FollowupRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 见 FollowupKind：Commitment / Todo / Idea / Suggestion */
    val kind: String,
    /** 关联对象：item / idea / decision / line / capture */
    val refKind: String? = null,
    val refId: Long? = null,
    val title: String,
    val detail: String = "",
    /** 证据原话 */
    val sourceText: String? = null,
    val sourceLineId: Long? = null,
    /** 见 FollowupStatus：Open / Done / Snoozed / Dismissed */
    val status: String = FollowupStatus.Open.name,
    val createdAt: Long,
    /** 上次提醒时间，限频用 */
    val lastRemindedAt: Long = 0,
    val remindCount: Int = 0,
    /** 下次该检查的时点（承诺跟踪的节拍器） */
    val nextCheckAt: Long = 0,
    val snoozedUntil: Long = 0,
    val resolvedAt: Long? = null,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

@Dao
abstract class FollowupDao {
    @Transaction open suspend fun <T> transaction(block: suspend () -> T): T = block()

    @Insert abstract suspend fun insert(row: FollowupRow): Long
    @Update abstract suspend fun update(row: FollowupRow)

    @Query("SELECT * FROM followups WHERE id = :id") abstract suspend fun followup(id: Long): FollowupRow?
    @Query("SELECT * FROM followups WHERE id = :id") abstract fun observeFollowup(id: Long): Flow<FollowupRow?>

    @Query("SELECT * FROM followups WHERE deleted = 0 AND status = 'Open' ORDER BY nextCheckAt ASC, createdAt ASC")
    abstract fun observeOpen(): Flow<List<FollowupRow>>

    @Query("SELECT * FROM followups WHERE deleted = 0 AND status != 'Open' ORDER BY updatedAt DESC, id DESC LIMIT 200")
    abstract fun observeClosed(): Flow<List<FollowupRow>>

    /** 到期该检查的开放项 */
    @Query("SELECT * FROM followups WHERE deleted = 0 AND status = 'Open' AND nextCheckAt <= :now ORDER BY nextCheckAt ASC")
    abstract suspend fun due(now: Long): List<FollowupRow>

    /** 复盘候选：反复提醒（≥minReminds 次）却一直没推进的开放项 */
    @Query("SELECT * FROM followups WHERE deleted = 0 AND status = 'Open' AND remindCount >= :minReminds ORDER BY remindCount DESC, createdAt ASC")
    abstract fun observeStalled(minReminds: Int): Flow<List<FollowupRow>>

    /** 今天已提醒过的数量：每日限频 */
    @Query("SELECT COUNT(*) FROM followups WHERE deleted = 0 AND lastRemindedAt >= :dayStart")
    abstract suspend fun remindedToday(dayStart: Long): Int

    /** 同来源去重：同一 ref 只保留一条 Open */
    @Query("SELECT * FROM followups WHERE deleted = 0 AND status = 'Open' AND refKind = :refKind AND refId = :refId LIMIT 1")
    abstract suspend fun openByRef(refKind: String, refId: Long): FollowupRow?

    @Query("SELECT * FROM followups WHERE deleted = 0 AND status = 'Open' AND kind = :kind ORDER BY createdAt ASC")
    abstract suspend fun openByKind(kind: String): List<FollowupRow>

    @Query("UPDATE followups SET status = :status, resolvedAt = :resolvedAt, updatedAt = :at WHERE id = :id AND deleted = 0")
    abstract suspend fun setStatus(id: Long, status: String, resolvedAt: Long?, at: Long): Int

    @Query("UPDATE followups SET lastRemindedAt = :at, remindCount = remindCount + 1, nextCheckAt = :nextCheckAt, updatedAt = :at WHERE id = :id")
    abstract suspend fun markReminded(id: Long, at: Long, nextCheckAt: Long): Int

    @Query("UPDATE followups SET nextCheckAt = :nextCheckAt, updatedAt = :at WHERE id = :id")
    abstract suspend fun setNextCheck(id: Long, nextCheckAt: Long, at: Long)

    @Query("UPDATE followups SET snoozedUntil = :until, status = 'Snoozed', updatedAt = :at WHERE id = :id")
    abstract suspend fun snooze(id: Long, until: Long, at: Long)

    /** 稍后提醒到期后自动回到 Open 由引擎处理 */
    @Query("UPDATE followups SET status = 'Open', nextCheckAt = :next, updatedAt = :at WHERE snoozedUntil <= :now AND status = 'Snoozed'")
    abstract suspend fun reopenSnoozed(now: Long, next: Long, at: Long): Int

    @Query("UPDATE followups SET deleted = 1, updatedAt = :at WHERE id = :id")
    abstract suspend fun delete(id: Long, at: Long)

    @Query("SELECT COUNT(*) FROM followups WHERE deleted = 0 AND status = 'Open'")
    abstract fun observeOpenCount(): Flow<Int>
}

object FollowupSchema {
    fun create(db: SQLiteConnection) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS followups (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            kind TEXT NOT NULL, refKind TEXT, refId INTEGER, title TEXT NOT NULL, detail TEXT NOT NULL,
            sourceText TEXT, sourceLineId INTEGER, status TEXT NOT NULL, createdAt INTEGER NOT NULL,
            lastRemindedAt INTEGER NOT NULL DEFAULT 0, remindCount INTEGER NOT NULL DEFAULT 0,
            nextCheckAt INTEGER NOT NULL DEFAULT 0, snoozedUntil INTEGER NOT NULL DEFAULT 0,
            resolvedAt INTEGER, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_followups_status ON followups(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_followups_kind ON followups(kind)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_followups_createdAt ON followups(createdAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_followups_nextCheckAt ON followups(nextCheckAt)")
    }
}

enum class FollowupKind(val label: String) {
    /** 用户明确说要做的事 */
    Commitment("承诺"),
    /** 待办事项超时未完成 */
    Todo("待办"),
    /** 灵感长期未推进 */
    Idea("灵感"),
    /** AI 或系统建议——不是承诺，提醒时单独标注 */
    Suggestion("建议");
}

enum class FollowupStatus(val label: String) {
    Open("待跟进"), Done("已完成"), Snoozed("稍后"), Dismissed("不再跟进");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: Open
    }
}

object FollowupRules {
    /** 默认检查节拍：每 24h 回看一次 */
    const val CHECK_INTERVAL_MS = 24L * 3600 * 1000
    /** 同一事项两次提醒最小间隔：4 小时 */
    const val REMIND_INTERVAL_MS = 4L * 3600 * 1000
    /** 每日提醒上限：宁可少不可滥 */
    const val DAILY_LIMIT = 3
    /** 同一事项提醒次数上限，超过则降频到每周 */
    const val MAX_REMIND_COUNT = 3

    /** 计算下一次检查时点；提醒多了自动降频。 */
    fun nextCheckAt(remindCount: Int, now: Long): Long =
        now + if (remindCount >= MAX_REMIND_COUNT) CHECK_INTERVAL_MS * 7 else CHECK_INTERVAL_MS

    /** 现在能不能提醒它：限频 + 次数上限 + Snoozed 保护。 */
    fun canRemind(row: FollowupRow, now: Long, remindedToday: Int): Boolean {
        if (row.status != FollowupStatus.Open.name) return false
        if (remindedToday >= DAILY_LIMIT) return false
        if (now - row.lastRemindedAt < REMIND_INTERVAL_MS) return false
        if (row.snoozedUntil > now) return false
        return true
    }

    /** 提醒文案：AI 建议必须明确标出不是用户自己说的。 */
    fun reminderText(row: FollowupRow): String = when (FollowupKind.entries.firstOrNull { it.name == row.kind }) {
        FollowupKind.Suggestion -> "（系统建议，不是你的承诺）${row.title}"
        FollowupKind.Idea -> "灵感放了很久：${row.title}"
        FollowupKind.Todo -> "待办还没完成：${row.title}"
        else -> "你说过要做：${row.title}"
    }
}
