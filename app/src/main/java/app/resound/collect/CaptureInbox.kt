package app.resound.collect

import app.resound.AppGraph
import app.resound.auth.Capability
import app.resound.platform.NowMillis
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 统一收集箱：浏览器/小红书/X 等分享进来的链接、文字、图片统一入库，
 * 图片落私有目录副本后跑本地 ML Kit 中文识字（不出手机），可再关联灵感/项目/原话。
 */
class CaptureInbox(
    private val context: Context,
    private val dao: CaptureItemDao,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val now: NowMillis,
    private val onEvent: suspend (key: String) -> Unit = {},
    /** 主库文件路径（CaptureDatabase.filePath）；为 null 时跳过独立连接落库验证。 */
    private val dbPath: () -> String? = { null },
) {
    fun observeInbox(): Flow<List<CaptureItemRow>> = dao.observeInbox()
    fun observeAll(): Flow<List<CaptureItemRow>> = dao.observeAll()
    fun observeInboxCount(): Flow<Int> = dao.observeInboxCount()
    fun observe(id: Long): Flow<CaptureItemRow?> = dao.observeItem(id)
    fun search(query: String): Flow<List<CaptureItemRow>> = dao.search(app.resound.inbox.IdeaRules.likePattern(query))

    /** 判断 intent 是不是要进收集箱的分享（文本/链接/图片），音频走原有导入通道。 */
    fun accepts(intent: Intent?): Boolean {
        if (intent?.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return false
        val type = intent?.type ?: return intent?.getStringExtra(Intent.EXTRA_TEXT) != null
        return type.startsWith("text/") || type.startsWith("image/")
    }

    /**
     * 处理分享 Intent，返回入库条目数；sourceApp 由 Activity 的 referrer/callingActivity 传入。
     * 收单挂到应用存活期 scope：分享后 Activity 被系统回收/界面协程取消时，
     * 「复制文件→入库」仍要跑完，否则分享内容会静默丢失（断网复测实锤过孤儿文件）。
     */
    @Suppress("DEPRECATION")
    suspend fun receive(intent: Intent, sourceApp: String? = null): Int = scope.async(io) {
        // AUTH：系统分享入口同样过门禁——未授权不写个人数据。
        if (AppGraph.isAuthReady && !AppGraph.auth.allowed(Capability.ReadPersonalData)) return@async 0
        var count = 0
        val app = sourceApp
        val texts = buildList {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let(::add)
            intent.getStringExtra(Intent.EXTRA_SUBJECT)?.let { if (it.isNotBlank()) add(it) }
            intent.clipData?.let { clip -> repeat(clip.itemCount) { clip.getItemAt(it).text?.toString()?.let(::add) } }
        }
        android.util.Log.d("CaptureInbox", "receive act=${intent.action} type=${intent.type} texts=${texts.size} src=$app")
        reconcile()
        for (text in texts.distinct().filter { it.isNotBlank() }) {
            count += saveText(text, app)
        }
        val uris = buildList {
            when (intent.action) {
                Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(::add)
                Intent.ACTION_SEND_MULTIPLE -> addAll(intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty())
            }
            intent.clipData?.let { clip -> repeat(clip.itemCount) { clip.getItemAt(it).uri?.let(::add) } }
        }.distinct()
        for (uri in uris) {
            val mime = intent.type ?: context.contentResolver.getType(uri) ?: "image/*"
            if (mime.startsWith("image/")) count += saveImage(uri, mime, app)
        }
        intent.action = null // 消费掉，避免旋转/重建后重复入库
        android.util.Log.d("CaptureInbox", "receive done count=$count uris=${uris.size}")
        count
    }.await()

    private suspend fun saveText(text: String, app: String?): Int {
        val kind = CollectRules.classify(text)
        val body = if (kind == CaptureKind.Url) CollectRules.extractUrl(text)!! else text.trim()
        if (dao.sameBody(kind.name, body) != null) return 0
        val title = if (kind == CaptureKind.Url) CollectRules.urlTitle(body) else text.trim().take(24)
        val id = commit(CaptureItemRow(kind = kind.name, body = body, title = title,
            note = if (kind == CaptureKind.Url) text.trim().removePrefix(body).trim().ifEmpty { null } else null,
            // 文本自带正文→已提取；纯 URL 只有元数据→诚实停在已保存
            extracted = if (kind == CaptureKind.Text) ExtractState.Extracted.name else ExtractState.Saved.name,
            sourceApp = app, createdAt = now.now(), updatedAt = now.now()))
        if (id < 0) return 0
        onEvent("capture_arrived"); return 1
    }

    private suspend fun saveImage(uri: Uri, mime: String, app: String?): Int {
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        if (bytes == null || bytes.isEmpty()) return 0
        // 文件名即内容指纹：同一张图再分享一次命中已有条目，不重复入库
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val dir = File(context.filesDir, "inbox").apply { mkdirs() }
        val target = File(dir, "cap_$hash.img")
        if (dao.imageAt(target.absolutePath) != null) return 0
        if (!target.exists()) runCatching { target.writeBytes(bytes) }
        if (!target.exists() || target.length() == 0L) { target.delete(); return 0 }
        val id = commit(CaptureItemRow(kind = CaptureKind.Image.name, body = target.absolutePath, title = "图片",
            mime = mime, localPath = target.absolutePath, sourceApp = app, createdAt = now.now(), updatedAt = now.now()))
        if (id < 0) return 0
        onEvent("capture_arrived")
        scope.launch(io) { runOcr(id, target) }
        return 1
    }

    // ── 持久化收单：spool 先行 + 独立连接回读验证 + 启动对账 ──
    // 动机（真机实锤）：Room 挂起 insert 曾「返回成功但数据没落盘」——连接池若持有泄漏的
    // 未提交事务，新写入只是挂在其下的 SAVEPOINT，外层永不提交则数据消失。
    // 同一连接回读会看到自己的未提交数据骗过自己，必须开第二条 SQLite 连接验证。

    private val spoolDir get() = File(context.filesDir, "capture-spool").apply { mkdirs() }

    private fun spoolWrite(row: CaptureItemRow): File {
        val f = File(spoolDir, "${now.now()}_${java.util.UUID.randomUUID().toString().substring(0, 8)}.json")
        val o = org.json.JSONObject()
            .put("kind", row.kind).put("body", row.body).put("title", row.title)
            .put("mime", row.mime ?: org.json.JSONObject.NULL)
            .put("localPath", row.localPath ?: org.json.JSONObject.NULL)
            .put("note", row.note ?: org.json.JSONObject.NULL)
            .put("sourceApp", row.sourceApp ?: org.json.JSONObject.NULL)
            .put("extracted", row.extracted)
            .put("createdAt", row.createdAt).put("updatedAt", row.updatedAt)
        f.writeText(o.toString())
        return f
    }

    private fun spoolRead(f: File): CaptureItemRow? = runCatching {
        val o = org.json.JSONObject(f.readText())
        CaptureItemRow(kind = o.getString("kind"), body = o.getString("body"),
            title = o.optString("title"), mime = o.optNullableStr("mime"),
            localPath = o.optNullableStr("localPath"), note = o.optNullableStr("note"),
            sourceApp = o.optNullableStr("sourceApp"), extracted = o.optString("extracted").ifEmpty { ExtractState.Saved.name },
            createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt"))
    }.getOrNull()

    private fun org.json.JSONObject.optNullableStr(k: String): String? =
        if (has(k) && !isNull(k)) getString(k) else null

    /** 用与 Room 无关的第二条连接回读：只能看到真正提交的数据。返回 null 表示验证无法执行。 */
    private fun persisted(id: Long): Boolean? {
        val path = dbPath() ?: return null
        return runCatching {
            android.database.sqlite.SQLiteDatabase.openDatabase(path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT COUNT(*) FROM capture_items WHERE id=$id", null).use { c ->
                    c.moveToFirst() && c.getLong(0) > 0
                }
            }
        }.getOrNull()
    }

    /** 先落 spool 再 insert；独立连接读不到行 → 视为未提交，spool 留待下次对账。返回真实行号或 -1。 */
    private suspend fun commit(row: CaptureItemRow): Long {
        val ticket = spoolWrite(row)
        val id = dao.insert(row)
        val ok = persisted(id)
        when (ok) {
            true, null -> ticket.delete() // 已证实落盘 / 无验证手段（默认放行，单元测试走这条路）
            false -> android.util.Log.w("CaptureInbox",
                "insert id=$id 独立连接读不到（疑写入挂在未提交事务下），保留 spool ${ticket.name}")
        }
        return if (ok == false) -1L else id
    }

    /** 启动与收单前对账：spool 里未被去重命中的条目重插；已有行直接清票。 */
    suspend fun reconcile() = withContext(io) {
        spoolDir.listFiles()?.forEach { f ->
            val row = spoolRead(f) ?: run { f.delete(); return@forEach }
            val known = if (row.kind == CaptureKind.Image.name) dao.imageAt(row.localPath.orEmpty()) != null
                else dao.sameBody(row.kind, row.body) != null
            if (known || commit(row) >= 0) f.delete()
        }
    }

    /** 本地 ML Kit 中文识字；识别失败保持 ocrText 为 null，原图保留，可再点重试。 */
    suspend fun retryOcr(id: Long) = withContext(io) {
        val path = dao.item(id)?.takeIf { it.kind == CaptureKind.Image.name }?.localPath ?: return@withContext
        runOcr(id, File(path))
    }

    private suspend fun runOcr(id: Long, file: File) {
        runCatching {
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            val image = InputImage.fromFilePath(context, Uri.fromFile(file))
            val result = kotlinx.coroutines.suspendCancellableCoroutine<String> { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it.text) { } }
                    .addOnFailureListener { cont.resume("") { } }
            }
            if (result.isNotBlank()) dao.setOcr(id, result, now.now())
        }
    }

    suspend fun setNote(id: Long, note: String?) = withContext(io) { dao.setNote(id, note, now.now()) }
    suspend fun linkIdea(id: Long, ideaId: Long) = withContext(io) { dao.linkIdea(id, ideaId, now.now()) }
    suspend fun linkProject(id: Long, projectId: Long) = withContext(io) { dao.linkProject(id, projectId, now.now()) }
    suspend fun linkLine(id: Long, lineId: Long) = withContext(io) { dao.linkLine(id, lineId, now.now()) }
    suspend fun archive(id: Long) = withContext(io) { dao.setStatus(id, CollectStatus.Archived.name, now.now()) }
    suspend fun delete(id: Long) = withContext(io) {
        dao.item(id)?.localPath?.let { runCatching { File(it).delete() } }
        dao.delete(id, now.now())
    }
}
