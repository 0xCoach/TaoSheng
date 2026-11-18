package app.resound.collect

import androidx.room.*
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow

/**
 * 统一收集箱：系统分享进来的网页/文字/图片，与语音记录同库存放。
 * 图片只存私有目录副本，OCR 结果单独落字段（本地 ML Kit，没识别出就留空）。
 */
@Entity(tableName = "capture_items", indices = [Index("kind"), Index("status"), Index("createdAt"), Index("ideaId"), Index("projectId")])
data class CaptureItemRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 见 CaptureKind：Url / Text / Image / File */
    val kind: String,
    /** 原始内容：URL 原文 / 文本 / 文件路径 */
    val body: String,
    val title: String = "",
    val mime: String? = null,
    /** 图片本地副本路径（filesDir/inbox/ 下） */
    val localPath: String? = null,
    /** 本地 OCR 结果；为空表示没识别或还没跑 */
    val ocrText: String? = null,
    /** 分享来源包名（如能取到） */
    val sourceApp: String? = null,
    /** 用户补记的一句话（"研究这个产品的差异化"） */
    val note: String? = null,
    /** 关联去向 */
    val ideaId: Long? = null,
    val projectId: Long? = null,
    /** 关联的语音记录（同一天说的话可挂上来） */
    val lineId: Long? = null,
    /** 见 CollectStatus：Inbox / Linked / Archived */
    val status: String = CollectStatus.Inbox.name,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)

@Dao
abstract class CaptureItemDao {
    @Transaction open suspend fun <T> transaction(block: suspend () -> T): T = block()

    @Insert abstract suspend fun insert(row: CaptureItemRow): Long
    @Update abstract suspend fun update(row: CaptureItemRow)

    @Query("SELECT * FROM capture_items WHERE id = :id") abstract suspend fun item(id: Long): CaptureItemRow?
    @Query("SELECT * FROM capture_items WHERE id = :id") abstract fun observeItem(id: Long): Flow<CaptureItemRow?>

    @Query("SELECT * FROM capture_items WHERE deleted = 0 AND status = 'Inbox' ORDER BY createdAt DESC, id DESC")
    abstract fun observeInbox(): Flow<List<CaptureItemRow>>

    @Query("SELECT * FROM capture_items WHERE deleted = 0 ORDER BY createdAt DESC, id DESC LIMIT 500")
    abstract fun observeAll(): Flow<List<CaptureItemRow>>

    /** 同 URL/内容去重 */
    @Query("SELECT * FROM capture_items WHERE deleted = 0 AND kind = :kind AND body = :body LIMIT 1")
    abstract suspend fun sameBody(kind: String, body: String): CaptureItemRow?

    @Query("""SELECT * FROM capture_items WHERE deleted = 0
        AND (title LIKE :pattern ESCAPE '\' OR body LIKE :pattern ESCAPE '\' OR ocrText LIKE :pattern ESCAPE '\' OR note LIKE :pattern ESCAPE '\')
        ORDER BY createdAt DESC, id DESC LIMIT 200""")
    abstract fun search(pattern: String): Flow<List<CaptureItemRow>>

    @Query("UPDATE capture_items SET status = :status, updatedAt = :at WHERE id = :id AND deleted = 0")
    abstract suspend fun setStatus(id: Long, status: String, at: Long): Int

    @Query("UPDATE capture_items SET ocrText = :ocrText, updatedAt = :at WHERE id = :id")
    abstract suspend fun setOcr(id: Long, ocrText: String?, at: Long)

    @Query("UPDATE capture_items SET ideaId = :ideaId, status = 'Linked', updatedAt = :at WHERE id = :id")
    abstract suspend fun linkIdea(id: Long, ideaId: Long, at: Long)

    @Query("UPDATE capture_items SET projectId = :projectId, status = 'Linked', updatedAt = :at WHERE id = :id")
    abstract suspend fun linkProject(id: Long, projectId: Long, at: Long)

    @Query("UPDATE capture_items SET lineId = :lineId, updatedAt = :at WHERE id = :id")
    abstract suspend fun linkLine(id: Long, lineId: Long, at: Long)

    @Query("UPDATE capture_items SET note = :note, updatedAt = :at WHERE id = :id")
    abstract suspend fun setNote(id: Long, note: String?, at: Long)

    @Query("UPDATE capture_items SET deleted = 1, updatedAt = :at WHERE id = :id")
    abstract suspend fun delete(id: Long, at: Long)

    @Query("SELECT COUNT(*) FROM capture_items WHERE deleted = 0 AND status = 'Inbox'")
    abstract fun observeInboxCount(): Flow<Int>
}

object CaptureItemSchema {
    fun create(db: SQLiteConnection) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS capture_items (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            kind TEXT NOT NULL, body TEXT NOT NULL, title TEXT NOT NULL, mime TEXT, localPath TEXT,
            ocrText TEXT, sourceApp TEXT, note TEXT, ideaId INTEGER, projectId INTEGER, lineId INTEGER,
            status TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
            deleted INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_capture_items_kind ON capture_items(kind)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_capture_items_status ON capture_items(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_capture_items_createdAt ON capture_items(createdAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_capture_items_ideaId ON capture_items(ideaId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_capture_items_projectId ON capture_items(projectId)")
    }
}

enum class CaptureKind(val label: String) {
    Url("链接"), Text("文字"), Image("图片"), File("文件");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: Text
    }
}

enum class CollectStatus(val label: String) {
    Inbox("收件箱"), Linked("已关联"), Archived("已归档");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: Inbox
    }
}

object CollectRules {
    private val urlRegex = Regex("""https?://\S+""")

    /** 分享文本里抽出第一个 URL；没有就 null。 */
    fun extractUrl(text: String): String? = urlRegex.find(text)?.value?.trimEnd('。', '，', '；', ')', '】')

    /** 判断分享内容类型：文本里含 URL 且主体是链接 → Url，否则 Text。 */
    fun classify(text: String): CaptureKind =
        if (extractUrl(text)?.let { text.trim().removePrefix(it).length < 24 } == true) CaptureKind.Url else CaptureKind.Text

    /** URL 标题兜底：取域名当标题。 */
    fun urlTitle(url: String): String =
        url.removePrefix("https://").removePrefix("http://").substringBefore('/').take(40)
}
