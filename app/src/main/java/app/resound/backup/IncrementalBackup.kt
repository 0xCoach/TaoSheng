package app.resound.backup

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** 页号从 0 开始。补丁始终相对于完整快照，内存占用仅两个页缓冲区。 */
internal object DatabasePages {
    data class Header(val pageSize: Int, val pages: Long, val version: Int, val autoVacuum: Int)
    fun header(file: File): Header = RandomAccessFile(file, "r").use { input ->
        val magic = ByteArray(16).also(input::readFully)
        require(magic.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)))
        val size = input.readUnsignedShort().let { if (it == 1) 65536 else it }
        require(size in 512..65536 && size and (size - 1) == 0 && file.length() % size == 0L)
        input.seek(60); val version = input.readInt()
        input.seek(52); val largestRoot = input.readInt()
        input.seek(64); val incremental = input.readInt()
        Header(size, file.length() / size, version, if (largestRoot == 0) 0 else if (incremental == 0) 1 else 2)
    }
    fun diff(base: File, today: File, patch: File): List<Long> {
        val old = header(base); val current = header(today)
        require(old.pageSize == current.pageSize && old.autoVacuum == current.autoVacuum)
        val changed = mutableListOf<Long>()
        RandomAccessFile(base, "r").use { before -> RandomAccessFile(today, "r").use { after ->
            patch.outputStream().buffered().use { output ->
                val a = ByteArray(current.pageSize); val b = ByteArray(current.pageSize)
                for (page in 0 until current.pages) {
                    after.readFully(b)
                    if (page < old.pages) before.readFully(a)
                    if (page >= old.pages || !a.contentEquals(b)) { changed += page; output.write(b) }
                }
            }
        } }
        return changed
    }
    fun apply(base: File, patch: File, target: File, info: JSONObject) {
        val size = info.getInt("pageSize"); val pages = info.getLong("totalPages")
        require(size == header(base).pageSize && pages > 0 && pages <= 2L * 1024 * 1024 * 1024 / size)
        val changed = info.getJSONArray("changedPages")
        require(patch.length() == changed.length().toLong() * size)
        base.copyTo(target, overwrite = true)
        RandomAccessFile(target, "rw").use { out -> patch.inputStream().buffered().use { input ->
            out.setLength(pages * size)
            val buffer = ByteArray(size); val reader = java.io.DataInputStream(input)
            var previous = -1L
            repeat(changed.length()) { i ->
                val page = changed.getLong(i)
                require(page > previous && page < pages)
                previous = page
                reader.readFully(buffer); out.seek(page * size); out.write(buffer)
            }
        } }
        require(BackupArchive.sha(target) == info.getString("resultSha256"))
    }
}

internal object IncrementalBackup {
    const val MISSING_BASE = "这份增量对应的完整备份已损坏或丢失"
    private const val WEEK = 7L * 24 * 60 * 60 * 1000
    private const val METHOD = "locked-copy-wal-v1"
    private fun manifest(zip: File): JSONObject = ZipFile(zip).use { archive ->
        val entry = requireNotNull(archive.getEntry("manifest.json"))
        archive.getInputStream(entry).use { input ->
            val bytes = input.readBytesLimited(4 * 1024 * 1024)
            JSONObject(bytes.toString(Charsets.UTF_8))
        }
    }
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer); if (count < 0) break
            require(output.size() + count <= limit)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private fun files(manifest: JSONObject): Map<String, JSONObject> {
        val array = manifest.getJSONArray("files")
        val result = linkedMapOf<String, JSONObject>()
        repeat(array.length()) { i ->
            val item = array.getJSONObject(i); val path = item.getString("path")
            require(BackupArchive.allowed(path) && result.put(path, item) == null)
        }
        require(BackupArchive.DATABASE in result)
        return result
    }
    internal fun reusable(base: File, metadata: JSONObject, today: File, at: Long): Boolean = runCatching {
        val before = DatabasePages.header(base); val after = DatabasePages.header(today)
        at - metadata.getLong("createdAt") in 0 until WEEK && metadata.getString("snapshotMethod") == METHOD &&
            before.version == after.version && before.pageSize == after.pageSize && before.autoVacuum == after.autoVacuum &&
            BackupArchive.sha(base) == files(metadata).getValue(BackupArchive.DATABASE).getString("sha256")
    }.getOrDefault(false)

    fun generate(context: Context, workspace: File, content: File, fullZip: File, at: Long, passphrase: String?,
        channel: String?, available: List<RemoteBackup>): GeneratedBackup {
        val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)
        val cache = File(context.filesDir, "backup-base")
        val current = manifest(fullZip)
        val database = File(content, BackupArchive.DATABASE)
        val baseName = channel?.let { prefs.getString("base-$it", null) }
        val base = baseName?.let { File(cache, "$it.db") }
        val metadata = baseName?.let { runCatching { JSONObject(File(cache, "$it.json").readText()) }.getOrNull() }
        var chosen = fullZip
        var dependency: String? = null
        if (baseName != null && base != null && metadata != null &&
            available.any { it.name == baseName && it.full && it.encrypted == (passphrase != null) } &&
            reusable(base, metadata, database, at)) {
            val delta = File(workspace, "incremental.zip")
            pack(baseName, base, metadata, content, current, delta)
            // 比较实际压缩归档大小，包括文件差异及头信息；加密固定开销不影响主要判断。
            if (delta.length() <= fullZip.length() / 2) { chosen = delta; dependency = baseName }
        }
        val name = BackupNames.create(at, passphrase != null, dependency)
        val output = BackupEncryption.prepare(chosen, passphrase)
        return GeneratedBackup(output, name, at) {
            // 目的端已发布成功才切换基准；失败保留上一套可用基准。
            if (channel != null && dependency == null) {
                check(cache.isDirectory || cache.mkdirs())
                val dbFile = File(cache, "$name.db")
                val jsonFile = File(cache, "$name.json")
                atomicCopy(database, dbFile)
                current.put("snapshotMethod", METHOD)
                val atomic = AtomicFile(jsonFile)
                val stream = atomic.startWrite()
                try { stream.write(current.toString().toByteArray()); atomic.finishWrite(stream) }
                catch (e: Exception) { atomic.failWrite(stream); throw e }
                check(prefs.edit().putString("base-$channel", name).commit())
                val retained = listOfNotNull(prefs.getString("base-cloud", null), prefs.getString("base-local", null))
                cache.listFiles()?.filter { file -> retained.none { file.name == "$it.db" || file.name == "$it.json" } }
                    ?.forEach { it.delete() }
            }
        }
    }
    private fun atomicCopy(source: File, destination: File) {
        val atomic = AtomicFile(destination); val stream = atomic.startWrite()
        try { source.inputStream().use { it.copyTo(stream) }; atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    internal fun pack(baseName: String, base: File, baseManifest: JSONObject, content: File, current: JSONObject, zip: File) {
        val patch = File(zip.parentFile, "${zip.name}.pages")
        try {
            val today = File(content, BackupArchive.DATABASE)
            val pages = DatabasePages.diff(base, today, patch)
            val header = DatabasePages.header(today)
            val before = files(baseManifest); val after = files(current)
            val changed = after.keys.filter { it != BackupArchive.DATABASE && before[it]?.getString("sha256") != after.getValue(it).getString("sha256") }
            val deleted = before.keys - after.keys
            val info = JSONObject(current.toString()).put("format", 2).put("baseName", baseName)
                .put("baseSha256", BackupArchive.sha(base)).put("pageSize", header.pageSize).put("totalPages", header.pages)
                .put("changedPages", JSONArray(pages)).put("resultSha256", BackupArchive.sha(today))
                .put("deleted", JSONArray(deleted)).put("changedFiles", JSONArray(changed))
            ZipOutputStream(zip.outputStream().buffered()).use { out ->
                fun entry(path: String, file: File) {
                    out.putNextEntry(ZipEntry(path)); file.inputStream().use { it.copyTo(out) }; out.closeEntry()
                }
                entry("database.pages", patch)
                changed.forEach { entry(it, File(content, it)) }
                out.putNextEntry(ZipEntry("manifest.json")); out.write(info.toString().toByteArray()); out.closeEntry()
            }
        } finally { patch.delete() }
    }

    /** 先解密，再完整重建并校验，调用方随后复用原迁移、快照、替换和重启流程。 */
    fun verify(source: File, verified: File, workspace: File, expectedVersion: Int, stored: String?, supplied: String?,
        fetchBase: ((String, File) -> Unit)? = null, expectedName: String? = null) {
        fun plain(file: File, name: String): File = if (BackupEncryption.isEncrypted(file))
            File(workspace, name).also { BackupEncryption.decrypt(file, it, stored, supplied) } else file
        try {
            val zip = plain(source, "selected.zip")
            val info = manifest(zip)
            if (info.getInt("format") == 1) {
                require(expectedName == null || BackupNames.metadata(expectedName)?.full == true)
                BackupArchive.verify(zip, verified, expectedVersion)
                return
            }
            require(info.getInt("format") == 2)
            val baseName = info.getString("baseName")
            require(BackupNames.metadata(baseName)?.full == true && baseName.startsWith("resound-full-"))
            if (expectedName != null) require(BackupNames.metadata(expectedName)?.baseName == baseName)
            val download = File(workspace, "base.download")
            try { (fetchBase ?: throw BackupFailure(MISSING_BASE))(baseName, download) }
            catch (e: Exception) { android.util.Log.w("ResoundBackup", "取基准失败: ${e.javaClass.simpleName}: ${e.message}"); throw BackupFailure(MISSING_BASE) }
            // 口令错误保持原提示；解密后的基准损坏使用稳定的依赖错误。
            val baseZip = plain(download, "base.zip")
            val baseStage = File(workspace, "base")
            try {
                BackupArchive.verify(baseZip, baseStage, expectedVersion)
                val actual = BackupArchive.sha(File(baseStage, BackupArchive.DATABASE))
                if (actual != info.getString("baseSha256")) android.util.Log.w("ResoundBackup", "基准数据库校验码不符: ${actual.take(12)} ≠ ${info.getString("baseSha256").take(12)}")
                require(actual == info.getString("baseSha256"))
            } catch (e: Exception) { android.util.Log.w("ResoundBackup", "基准校验失败: ${e.javaClass.simpleName}: ${e.message}"); throw BackupFailure(MISSING_BASE) }
            val delta = File(workspace, "delta")
            BackupArchive.extract(zip, delta, incremental = true)
            val before = files(JSONObject(File(baseStage, "manifest.json").readText())); val after = files(info)
            fun paths(key: String): Set<String> {
                val array = info.getJSONArray(key)
                val list = (0 until array.length()).map { array.getString(it) }
                require(list.distinct().size == list.size && list.all { BackupArchive.allowed(it) && it != BackupArchive.DATABASE })
                return list.toSet()
            }
            val changed = paths("changedFiles"); val deleted = paths("deleted")
            require(deleted == before.keys - after.keys)
            require(changed == after.keys.filter { it != BackupArchive.DATABASE && before[it]?.getString("sha256") != after.getValue(it).getString("sha256") }.toSet())
            val actual = delta.walkTopDown().filter { it.isFile }.map { it.relativeTo(delta).invariantSeparatorsPath }.toSet()
            require(actual == changed + setOf("manifest.json", "database.pages"))
            require(baseStage.renameTo(verified))
            DatabasePages.apply(File(verified, BackupArchive.DATABASE), File(delta, "database.pages"),
                File(workspace, "restored.db"), info)
            File(workspace, "restored.db").copyTo(File(verified, BackupArchive.DATABASE), overwrite = true)
            deleted.forEach { require(File(verified, it).delete()) }
            changed.forEach { File(delta, it).copyTo(File(verified, it).apply { parentFile!!.mkdirs() }, overwrite = true) }
            info.put("format", 1)
            File(verified, "manifest.json").writeText(info.toString())
            BackupArchive.verifyContent(verified, expectedVersion)
        } catch (e: Exception) {
            verified.deleteRecursively()
            throw (e as? BackupFailure ?: BackupFailure("备份校验失败，未恢复数据"))
        }
    }
}
