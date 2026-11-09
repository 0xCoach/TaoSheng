package app.resound.backup

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import java.util.UUID

/** 只查询本应用拥有的下载文件；不申请存储权限、不接管别的应用或旧安装的文件。 */
internal class DownloadsBackupFiles(private val context: Context) : VolumeStore {
    private val resolver = context.contentResolver
    private val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val streams = LocalBackupFiles(context)
    // 安装标识不进备份：换装后不与旧安装/外部拷贝的同名对象发生改名冲突。
    internal val rootPath = installationRoot(context)
    private data class Entry(val uri: Uri, val name: String, val bytes: Long, val modified: Long)

    private fun entries(directory: String): List<Entry> {
        val columns = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE, MediaStore.Downloads.DATE_MODIFIED, MediaStore.Downloads.OWNER_PACKAGE_NAME)
        val path = rootPath + if (directory.isEmpty()) "" else "$directory/"
        return resolver.query(collection, columns,
            "${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.OWNER_PACKAGE_NAME} = ? AND ${MediaStore.Downloads.IS_PENDING} = 0",
            arrayOf(path, context.packageName), null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    // 再核对归属，避免兼容层忽略 selection 时误删外部文件。
                    if (cursor.getString(4) == context.packageName) add(Entry(
                        ContentUris.withAppendedId(collection, cursor.getLong(0)), cursor.getString(1),
                        cursor.getLong(2), cursor.getLong(3) * 1000))
                }
            }
        } ?: throw java.io.IOException("Downloads query returned no cursor")
    }
    private fun validate(path: String) {
        require(volumePath(path) || ('/' !in path && BackupNames.metadata(path) != null))
    }
    private fun locate(path: String): Entry? = entries(path.substringBeforeLast('/', ""))
        .singleOrNull { it.name == path.substringAfterLast('/') }
    fun source(name: String): Uri {
        require(BackupNames.metadata(name) != null)
        return locate(name)?.uri ?: throw BackupFailure("这份本机备份已不存在，请刷新列表")
    }
    override fun read(path: String, target: File): Boolean {
        validate(path)
        val entry = locate(path) ?: locate("$path.previous") ?: return false
        streams.read(entry.uri, target)
        return true
    }
    override fun exists(path: String): Boolean {
        validate(path)
        return locate(path) != null || locate("$path.previous") != null
    }
    private fun remove(entry: Entry) {
        check(resolver.delete(entry.uri, "${MediaStore.Downloads.OWNER_PACKAGE_NAME} = ?",
            arrayOf(context.packageName)) == 1) { "Downloads delete failed" }
    }
    private fun publish(uri: Uri, name: String, directory: String) {
        check(resolver.update(uri, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.IS_PENDING, 0)
        }, null, null) == 1) { "Downloads rename failed" }
        check(entries(directory).any { it.uri == uri && it.name == name }) { "Downloads renamed to unexpected name" }
    }
    override fun put(path: String, source: File) {
        validate(path)
        val directory = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        // 覆盖可变日分卷时先留下 previous；中断后的读取仍能取回完整旧文件。
        if (locate(path) == null) locate("$path.previous")?.let { publish(it.uri, name, directory) }
        val uri = resolver.insert(collection, ContentValues().apply {
            put(MediaStore.Downloads.RELATIVE_PATH, rootPath + if (directory.isEmpty()) "" else "$directory/")
            put(MediaStore.Downloads.DISPLAY_NAME, "$name-${UUID.randomUUID()}.part")
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }) ?: throw java.io.IOException("Downloads insert returned null")
        var published = false
        try {
            streams.export(source, uri)
            locate("$path.previous")?.let(::remove)
            locate(path)?.let { publish(it.uri, "$name.previous", directory) }
            publish(uri, name, directory)
            published = true
            locate("$path.previous")?.let(::remove)
        } catch (e: Exception) {
            // 清理失败不覆盖最初的错误；已发布的新文件不能在清理旧文件失败时删掉。
            if (!published) {
                runCatching { resolver.delete(uri, null, null) }
                runCatching { if (locate(path) == null) locate("$path.previous")?.let { publish(it.uri, name, directory) } }
            }
            BackupDiagnostics.record(context, "downloads-write", e)
            throw e
        }
    }
    override fun delete(path: String) {
        validate(path)
        for (name in listOf(path, "$path.previous")) locate(name)?.let(::remove)
    }
    override fun copy(from: String, to: String) {
        val temporary = File.createTempFile("downloads-copy-", ".tmp", context.cacheDir)
        try { check(read(from, temporary)); put(to, temporary) } finally { temporary.delete() }
    }
    override fun legacy() = entries("").map { it.name }.filter { BackupNames.metadata(it) != null }
    override fun files(directory: String): List<VolumeFile> {
        require(directory in setOf("history", "objects"))
        return entries(directory).filter { volumePath("$directory/${it.name}") }
            .map { VolumeFile("$directory/${it.name}", it.bytes, it.modified) }
    }
    companion object {
        const val ROOT = "Download/涛声备份/"
        const val LABEL = "下载/涛声备份"
        @Synchronized private fun installationRoot(context: Context): String {
            val file = File(context.noBackupFilesDir, "downloads-backup-installation")
            val id = if (file.exists()) file.readText() else UUID.randomUUID().toString().also { file.writeText(it) }
            require(id.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")))
            return "$ROOT$id/"
        }
    }
}
