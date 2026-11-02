package app.resound.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/** 只用于本机恢复提交，不进入用户备份；删除任一来源原话时一并作废。 */
@Entity(tableName = "digest_pending")
data class PendingDigest(
    @PrimaryKey val id: String,
    val lineIds: String,
    val content: String,
    val day: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val thingRevision: Long?,
) {
    fun ids(): List<Long> = lineIds.split(',').map(String::toLong)
}

internal object PendingDigestSchema {
    fun create(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS digest_pending (id TEXT NOT NULL PRIMARY KEY, lineIds TEXT NOT NULL, content TEXT NOT NULL, day TEXT NOT NULL, promptTokens INTEGER NOT NULL, completionTokens INTEGER NOT NULL, thingRevision INTEGER)")
        install(db)
    }
    private fun install(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS digest_pending_source_deleted AFTER DELETE ON transcript_lines BEGIN
            DELETE FROM digest_pending WHERE instr(',' || lineIds || ',', ',' || OLD.id || ',') > 0;
        END""")
    }
    val callback = object : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) = install(db)
    }
}
