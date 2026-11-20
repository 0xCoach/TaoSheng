package app.resound.push

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** SAF 操作通过小接口隔离，可用内存存储验证读回/失败，不访问用户文件夹。 */
internal interface FolderDocuments {
    fun directories(path: String)
    fun write(path: String): OutputStream
    fun read(path: String): InputStream?
    fun delete(path: String)
    fun rename(from: String, to: String)
}
internal class SafFolderDocuments(private val context: Context, tree: String) : FolderDocuments {
    private val uri = Uri.parse(tree)
    private fun root(): DocumentFile {
        check(context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission })
        return checkNotNull(DocumentFile.fromTreeUri(context, uri)).also { check(it.exists() && it.isDirectory && it.canWrite()) }
    }
    private fun parent(path: String, create: Boolean): Pair<DocumentFile?, String> {
        val parts = path.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." || '\\' in it })
        var dir: DocumentFile? = root()
        for (part in parts.dropLast(1)) {
            val current = dir ?: return null to parts.last()
            dir = current.findFile(part) ?: if (create) checkNotNull(current.createDirectory(part)) else null
            check(dir == null || dir.isDirectory)
        }
        return dir to parts.last()
    }
    override fun directories(path: String) { parent(if (path.isBlank()) "_" else "$path/_", true) }
    override fun write(path: String): OutputStream {
        val (dir, name) = parent(path, true)
        val file = dir!!.findFile(name) ?: checkNotNull(dir.createFile("application/octet-stream", name))
        check(file.isFile)
        return checkNotNull(context.contentResolver.openOutputStream(file.uri, "wt"))
    }
    override fun read(path: String): InputStream? {
        val (dir, name) = parent(path, false)
        val file = dir?.findFile(name) ?: return null
        check(file.isFile)
        return checkNotNull(context.contentResolver.openInputStream(file.uri))
    }
    override fun delete(path: String) {
        val (dir, name) = parent(path, false)
        dir?.findFile(name)?.let { check(it.isFile && it.delete()) }
    }
    override fun rename(from: String, to: String) {
        require(from.substringBeforeLast('/', "") == to.substringBeforeLast('/', ""))
        val (dir, name) = parent(from, false)
        val file = checkNotNull(dir?.findFile(name))
        val target = to.substringAfterLast('/')
        check(file.renameTo(target) && file.name == target)
    }
}
internal class LocalFolderStorage(private val docs: FolderDocuments) : FolderStorage {
    constructor(context: Context, tree: String) : this(SafFolderDocuments(context, tree))
    private fun <T> available(block: () -> T): T = try { block() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { throw PushFailure("文件夹不可用，请重新选择") }
    override fun directories(path: String) = available { docs.directories(path) }
    override fun delete(path: String) = available { docs.delete(path) }
    override fun put(path: String, content: String, state: PushSyncState) = text(path, content)
    fun audio(path: String, file: File, checkActive: () -> Unit = {}) = writeVerified(path, { file.inputStream() }, checkActive)
    fun text(path: String, content: String) = writeVerified(path, { content.byteInputStream(Charsets.UTF_8) })
    private fun writeVerified(path: String, source: () -> InputStream, checkActive: () -> Unit = {}) = available {
        val part = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } + ".${path.substringAfterLast('/')}.part"
        val expected = MessageDigest.getInstance("SHA-256")
        source().use { input -> docs.write(part).use { out ->
            val buffer = ByteArray(65536)
            while (true) { checkActive(); val n = input.read(buffer); if (n < 0) break; expected.update(buffer, 0, n); out.write(buffer, 0, n) }
        } }
        val actual = MessageDigest.getInstance("SHA-256")
        checkNotNull(docs.read(part)).use { input ->
            val buffer = ByteArray(65536)
            while (true) { checkActive(); val n = input.read(buffer); if (n < 0) break; actual.update(buffer, 0, n) }
        }
        check(expected.digest().contentEquals(actual.digest()))
        checkActive(); docs.delete(path); docs.rename(part, path)
    }
    fun send(format: PushFormat, message: PushMessage) = available {
        val daily = message.memory
        val date = daily?.getString("date") ?: java.time.Instant.ofEpochMilli(message.at).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
        val path = if (daily == null) "推送记录/$date.md" else "$date.md"
        val text = daily?.getString("markdown") ?: (docs.read(path)?.bufferedReader()?.use { it.readText() }.orEmpty().trimEnd() +
            "\n\n## ${app.resound.OutputTime().moment(message.at)} ${message.title}\n\n${message.rendered(format)}\n")
        text(path, text)
    }
}
