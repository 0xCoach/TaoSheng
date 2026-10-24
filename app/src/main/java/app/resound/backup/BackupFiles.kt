package app.resound.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.resound.data.CaptureDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** 固定英文标识与历史前缀，完全不依赖界面名称。 */
internal object BackupNames {
    private val pattern = Regex("(?:resound|回响)-(\\d{4}-\\d{2}-\\d{2}-\\d{4})(?:-(\\d{5})-[a-f0-9]{8})?\\.zip(\\.enc)?")
    private const val ID = "\\d{4}-\\d{2}-\\d{2}-\\d{4}-\\d{5}-[a-f0-9]{8}"
    private val modern = Regex("resound-(full|inc)-($ID)(?:__($ID))?\\.zip(\\.enc)?")
    fun create(at: Long, encrypted: Boolean, baseName: String? = null): String {
        val id = SimpleDateFormat("yyyy-MM-dd-HHmm-ssSSS", Locale.ROOT).format(Date(at)) + "-" + UUID.randomUUID().toString().take(8)
        val base = baseName?.let {
            require(metadata(it)?.full == true && it.startsWith("resound-full-") && it.endsWith(".enc") == encrypted)
            "__" + it.removePrefix("resound-full-").removeSuffix(".enc").removeSuffix(".zip")
        }.orEmpty()
        return "resound-${if (baseName == null) "full" else "inc"}-$id$base" + if (encrypted) ".zip.enc" else ".zip"
    }

    fun metadata(name: String, bytes: Long = 0): RemoteBackup? {
        modern.matchEntire(name)?.let { match ->
            val full = match.groupValues[1] == "full"
            val base = match.groupValues[3]
            if (full != base.isEmpty()) return null
            val suffix = if (name.endsWith(".enc")) ".zip.enc" else ".zip"
            val date = metadata("resound-${match.groupValues[2]}$suffix", bytes) ?: return null
            val baseName = if (full) null else "resound-full-$base$suffix"
            if (baseName != null && metadata(baseName) == null) return null
            return date.copy(name = name, full = full, baseName = baseName)
        }
        val match = pattern.matchEntire(name) ?: return null
        val seconds = match.groupValues[2]
        val stamp = match.groupValues[1] + if (seconds.isEmpty()) "" else "-$seconds"
        val format = SimpleDateFormat(if (seconds.isEmpty()) "yyyy-MM-dd-HHmm" else "yyyy-MM-dd-HHmm-ssSSS", Locale.ROOT)
        format.isLenient = false
        val position = java.text.ParsePosition(0)
        val date = format.parse(stamp, position) ?: return null
        if (position.index != stamp.length) return null
        return RemoteBackup(name, date.time, bytes, name.endsWith(".enc"))
    }
}

internal data class GeneratedBackup(val file: File, val name: String, val at: Long, val committed: () -> Unit = {})

/** 单份导出保持自包含；定时/立即备份按目的端自己的基准生成。 */
internal fun generateBackup(context: Context, database: CaptureDatabase, workspace: File, passphrase: String?,
    channel: String? = null, available: List<RemoteBackup> = emptyList()): GeneratedBackup {
    check(workspace.isDirectory || workspace.mkdirs())
    val at = System.currentTimeMillis()
    val zip = File(workspace, "resound-backup.zip")
    val content = File(workspace, "content").apply { check(mkdirs()) }
    BackupArchive.create(context, database, content, zip, at)
    return IncrementalBackup.generate(context, workspace, content, zip, at, passphrase, channel, available)
}

internal object BackupRetention {
    fun names(backups: List<RemoteBackup>, keep: Int): Set<String> {
        require(keep > 0)
        val points = backups.sortedWith(compareByDescending<RemoteBackup> { it.at }.thenByDescending { it.name }).take(keep)
        return points.map { it.name }.toSet() + points.mapNotNull { it.baseName }
    }
}

/** 仅在此层接触 SAF；测试使用 DocumentFile.fromFile 和临时目录，不接触外部存储。 */
internal class LocalBackupFiles(
    private val context: Context,
    private val resolveTree: (Uri) -> DocumentFile? = { DocumentFile.fromTreeUri(context, it) },
    private val persist: (Uri) -> Unit = {
        context.contentResolver.takePersistableUriPermission(it,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    },
) {
    fun select(uri: Uri): String = access {
        persist(uri)
        folder(uri).name ?: "Resound"
    }
    private fun folder(uri: Uri): DocumentFile = access {
        val file = resolveTree(uri)
        if (file == null || !file.isDirectory || !file.canRead() || !file.canWrite()) throw BackupFailure(INACCESSIBLE)
        file
    }
    fun list(uri: Uri): List<RemoteBackup> = access {
        folder(uri).listFiles().filter { it.isFile }.mapNotNull { BackupNames.metadata(it.name.orEmpty(), it.length()) }
            .sortedWith(compareByDescending<RemoteBackup> { it.at }.thenByDescending { it.name })
    }
    fun source(tree: Uri, name: String): Uri = access {
        if (BackupNames.metadata(name) == null) throw BackupFailure("备份文件名无效")
        folder(tree).findFile(name)?.takeIf { it.isFile }?.uri ?: throw BackupFailure(INACCESSIBLE)
    }
    fun write(tree: Uri, backup: GeneratedBackup) = access {
        val folder = folder(tree)
        // 先完整写入并核对，再发布正式名称；失败绝不清理已有备份。
        val part = folder.createFile("application/octet-stream", "${backup.name}.part") ?: throw BackupFailure(INACCESSIBLE)
        try {
            export(backup.file, part.uri)
            if (!part.renameTo(backup.name) || part.name != backup.name) throw BackupFailure("本机备份文件命名失败，请重新选择文件夹")
        } catch (e: Exception) { part.delete(); throw e }
    }
    fun retain(tree: Uri, keep: Int): Int = access {
        val folder = folder(tree)
        val backups = list(tree).filter { it.name.startsWith("resound-") }
        val retained = BackupRetention.names(backups, keep)
        backups.filter { it.name !in retained }.forEach {
            if (folder.findFile(it.name)?.delete() != true) throw BackupFailure("本机旧备份清理失败，请检查文件夹权限")
        }
        list(tree).size
    }
    fun export(file: File, target: Uri) {
        context.contentResolver.openOutputStream(target, "wt")?.use { output ->
            file.inputStream().use { it.copyTo(output) }
        } ?: throw BackupFailure(INACCESSIBLE)
        // 关闭流后回读核对，失败时不执行保留清理或清除恢复快照。
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(target)?.use { input ->
            val buffer = ByteArray(65536)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        } ?: throw BackupFailure(INACCESSIBLE)
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != BackupArchive.sha(file)) throw BackupFailure("本机备份写入校验失败，请重试")
    }
    fun read(source: Uri, target: File) {
        context.contentResolver.openInputStream(source)?.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(65536)
                var total = 0L
                while (true) {
                    val size = input.read(buffer); if (size < 0) break
                    total += size
                    if (total > 2L * 1024 * 1024 * 1024) throw BackupFailure("备份文件过大")
                    output.write(buffer, 0, size)
                }
            }
        } ?: throw BackupFailure(INACCESSIBLE)
    }
    fun volumes(tree: Uri): VolumeStore = object : VolumeStore {
        private fun locate(path: String, create: Boolean = false): DocumentFile? = access {
            val base = path.removeSuffix(".part").removeSuffix(".previous")
            require(volumePath(base) || ('/' !in base && base.startsWith("resound-") && BackupNames.metadata(base) != null))
            var parent = folder(tree)
            val pieces = path.split('/')
            for (piece in pieces.dropLast(1)) {
                parent = parent.findFile(piece) ?: if (create) parent.createDirectory(piece)
                    ?: throw BackupFailure(INACCESSIBLE) else return@access null
                require(parent.isDirectory)
            }
            parent.findFile(pieces.last()) ?: if (create) parent.createFile("application/octet-stream", pieces.last()) else null
        }
        override fun read(path: String, target: File): Boolean = access {
            val file = locate(path) ?: locate("$path.previous") ?: return@access false
            this@LocalBackupFiles.read(file.uri, target); true
        }
        override fun exists(path: String): Boolean = access { locate(path)?.isFile == true || locate("$path.previous")?.isFile == true }
        override fun put(path: String, source: File): Unit = access {
            val name = path.substringAfterLast('/')
            // 两阶段改名。读者在正式文件缺失的窗口读取 previous；新文件先回读校验。
            val recovery = locate("$path.previous")
            if (locate(path) == null && recovery != null) check(recovery.renameTo(name))
            locate("$path.part")?.let { check(it.delete()) }
            val part = locate("$path.part", true) ?: throw BackupFailure(INACCESSIBLE)
            try {
                export(source, part.uri)
                locate("$path.previous")?.let { check(it.delete()) }
                locate(path)?.let { check(it.renameTo("$name.previous")) }
                check(part.renameTo(name) && part.name == name)
                locate("$path.previous")?.let { check(it.delete()) }
            } catch (e: Exception) {
                if (locate(path) == null) locate("$path.previous")?.renameTo(name)
                part.takeIf { it.name == "$name.part" }?.delete()
                throw e
            }
        }
        override fun delete(path: String) = access {
            for (name in listOf(path, "$path.previous", "$path.part")) {
                val target = locate(name); check(target == null || target.delete())
            }
        }
        override fun copy(from: String, to: String): Unit = access {
            val temporary = File.createTempFile("volume-copy-", ".tmp", context.cacheDir)
            try { check(read(from, temporary)); put(to, temporary) } finally { temporary.delete() }
        }
        override fun legacy() = list(tree).filter { it.name.startsWith("resound-") }.map { it.name }
    }
    private inline fun <T> access(block: () -> T): T = try { block() }
        catch (e: BackupFailure) { throw e }
        catch (_: Exception) { throw BackupFailure(INACCESSIBLE) }
    companion object { const val INACCESSIBLE = "本机文件夹无法访问，请重新选择" }
}
