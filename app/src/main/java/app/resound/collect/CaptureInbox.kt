package app.resound.collect

import app.resound.platform.NowMillis
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
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

    /** 处理分享 Intent，返回入库条目数；sourceApp 由 Activity 的 referrer/callingActivity 传入。 */
    @Suppress("DEPRECATION")
    suspend fun receive(intent: Intent, sourceApp: String? = null): Int = withContext(io) {
        var count = 0
        val app = sourceApp
        val texts = buildList {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let(::add)
            intent.getStringExtra(Intent.EXTRA_SUBJECT)?.let { if (it.isNotBlank()) add(it) }
            intent.clipData?.let { clip -> repeat(clip.itemCount) { clip.getItemAt(it).text?.toString()?.let(::add) } }
        }
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
        count
    }

    private suspend fun saveText(text: String, app: String?): Int {
        val kind = CollectRules.classify(text)
        val body = if (kind == CaptureKind.Url) CollectRules.extractUrl(text)!! else text.trim()
        if (dao.sameBody(kind.name, body) != null) return 0
        val title = if (kind == CaptureKind.Url) CollectRules.urlTitle(body) else text.trim().take(24)
        val id = dao.insert(CaptureItemRow(kind = kind.name, body = body, title = title,
            note = if (kind == CaptureKind.Url) text.trim().removePrefix(body).trim().ifEmpty { null } else null,
            // 文本自带正文→已提取；纯 URL 只有元数据→诚实停在已保存
            extracted = if (kind == CaptureKind.Text) ExtractState.Extracted.name else ExtractState.Saved.name,
            sourceApp = app, createdAt = now.now(), updatedAt = now.now()))
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
        val id = dao.insert(CaptureItemRow(kind = CaptureKind.Image.name, body = target.absolutePath, title = "图片",
            mime = mime, localPath = target.absolutePath, sourceApp = app, createdAt = now.now(), updatedAt = now.now()))
        onEvent("capture_arrived")
        scope.launch(io) { runOcr(id, target) }
        return 1
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
