package app.resound.importer

import androidx.room.*
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

@Entity(tableName = "import_fingerprints")
internal data class ImportFingerprint(@PrimaryKey val fingerprint: String, val seenAt: Long)

@Entity(tableName = "folder_observations", primaryKeys = ["folderId", "documentId"])
internal data class FolderObservation(val folderId: String, val documentId: String, val size: Long,
    val modified: Long, val ignored: Boolean, val fingerprint: String? = null)

@Dao
internal interface FolderImportDao {
    @Query("SELECT EXISTS(SELECT 1 FROM import_fingerprints WHERE fingerprint = :key)")
    suspend fun contains(key: String): Boolean
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun remember(row: ImportFingerprint)
    @Query("SELECT * FROM folder_observations WHERE folderId = :id")
    suspend fun observations(id: String): List<FolderObservation>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun observe(rows: List<FolderObservation>)
    @Query("DELETE FROM folder_observations WHERE folderId = :id") suspend fun clearObservations(id: String)
}

/** 流式保留首尾各 64 KiB；兼容不能 seek 的 SAF provider，内存不随录音长度增长。 */
internal fun audioFingerprint(input: InputStream): String {
    val window = 64 * 1024
    val head = ByteArray(window)
    val tail = ByteArray(window)
    val buffer = ByteArray(window)
    var size = 0L
    var first = 0
    var position = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        val keep = minOf(count, window - first)
        buffer.copyInto(head, first, 0, keep); first += keep
        val part = minOf(count, window - position)
        buffer.copyInto(tail, position, 0, part)
        if (count > part) buffer.copyInto(tail, 0, part, count)
        position = (position + count) % window
        size += count
    }
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(head, 0, first)
    if (size < window) digest.update(tail, 0, size.toInt())
    else { digest.update(tail, position, window - position); digest.update(tail, 0, position) }
    return "$size:" + digest.digest().joinToString("") { "%02x".format(it) }
}
internal fun audioFingerprint(channel: java.nio.channels.FileChannel): String {
    val size = channel.size()
    val length = minOf(size, 64 * 1024L).toInt()
    val digest = MessageDigest.getInstance("SHA-256")
    for (offset in listOf(0L, (size - length).coerceAtLeast(0))) {
        val buffer = java.nio.ByteBuffer.allocate(length)
        channel.position(offset)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw java.io.IOException("文件仍在写入，稍后重试")
        }
        digest.update(buffer.array())
    }
    if (channel.size() != size) throw java.io.IOException("文件仍在写入，稍后重试")
    return "$size:" + digest.digest().joinToString("") { "%02x".format(it) }
}
internal fun audioFingerprint(file: File): String = file.inputStream().use { audioFingerprint(it.channel) }
