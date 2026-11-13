package app.resound.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

@Entity(tableName = "ask_conversations")
data class AskConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val title: String,
)

@Entity(tableName = "ask_messages", indices = [Index("conversationId")], foreignKeys = [
    ForeignKey(entity = AskConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE),
])
data class AskMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val text: String,
    val sourcesJson: String,
    val at: Long,
    val error: String?,
    val actionJson: String? = null,
)

@Dao
abstract class AskDao {
    @Query("SELECT * FROM ask_conversations WHERE id = :id")
    abstract suspend fun conversation(id: Long): AskConversationEntity?

    @Query("""SELECT c.id, c.title,
        (SELECT substr(CASE WHEN m.text = '' THEN coalesce(m.error, '') ELSE m.text END, 1, 120) FROM ask_messages m WHERE m.conversationId=c.id ORDER BY m.id DESC LIMIT 1) AS preview,
        (SELECT MAX(m.at) FROM ask_messages m WHERE m.conversationId=c.id) AS updatedAt
        FROM ask_conversations c WHERE EXISTS(SELECT 1 FROM ask_messages m WHERE m.conversationId=c.id)
        AND (:query = '' OR instr(lower(c.title), lower(:query)) > 0 OR EXISTS(
            SELECT 1 FROM ask_messages m WHERE m.conversationId=c.id AND instr(lower(m.text), lower(:query)) > 0))
        ORDER BY updatedAt DESC, c.id DESC LIMIT :limit""")
    abstract fun history(query: String, limit: Int): kotlinx.coroutines.flow.Flow<List<app.resound.ask.AskConversation>>

    @Query("UPDATE ask_conversations SET title = :title WHERE id = :id")
    abstract suspend fun rename(id: Long, title: String)

    @Query("DELETE FROM ask_conversations WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("SELECT * FROM ask_conversations ORDER BY id DESC LIMIT 1")
    abstract suspend fun latest(): AskConversationEntity?

    @Query("SELECT * FROM ask_messages WHERE conversationId = :conversationId ORDER BY id")
    abstract suspend fun messages(conversationId: Long): List<AskMessageEntity>

    @Query("SELECT * FROM ask_messages WHERE id = :id")
    abstract suspend fun message(id: Long): AskMessageEntity?

    @Query("UPDATE ask_messages SET actionJson = :action WHERE id = :id")
    abstract suspend fun setAction(id: Long, action: String)

    @Insert abstract suspend fun insertConversation(row: AskConversationEntity): Long
    @Insert abstract suspend fun insertMessage(row: AskMessageEntity): Long

    @Query("UPDATE ask_conversations SET title = :title WHERE id = :id AND title = ''")
    abstract suspend fun nameConversation(id: Long, title: String)

    /** 用户问题和最终回答原子落库；流式中间态从不写表。 */
    @Transaction
    open suspend fun saveExchange(user: AskMessageEntity, assistant: AskMessageEntity): Pair<Long, Long> =
        insertMessage(user) to insertMessage(assistant)
}
