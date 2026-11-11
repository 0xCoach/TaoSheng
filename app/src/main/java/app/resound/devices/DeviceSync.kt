package app.resound.devices

import app.resound.data.CaptureDatabase
import app.resound.data.EventEntity
import app.resound.importer.audioFingerprint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

internal data class DeviceSlice(val from: Long, val to: Long, val start: Long)
internal fun deviceSlice(file: DeviceFile, gapStart: Long?, gapEnd: Long?, offset: Long?, modified: Long?): DeviceSlice? {
    val duration = requireNotNull(file.durationMillis) { "缺少录音时长" }
    require(duration > 0)
    if (gapStart == null && gapEnd == null) return DeviceSlice(0, duration,
        file.startedAt?.plus(offset ?: 0) ?: requireNotNull(modified) { "缺少录音时间" } - duration)
    val startedAt = file.startedAt
    val start = if (offset != null && startedAt != null) startedAt + offset
        else requireNotNull(modified) { "缺少可靠的文件修改时间" } - duration
    require(gapStart != null && gapEnd != null && gapEnd > gapStart)
    if (start >= gapEnd || start + duration <= gapStart) return null
    val from = (gapStart - start - 2000).coerceAtLeast(0)
    val to = (gapEnd - start + 2000).coerceAtMost(duration)
    return DeviceSlice(from, to, start + from)
}

/** 每个物理设备一个实例，deviceKey 传地址；所有实例共享串行传输锁。调用方在断开时取消调用协程。 */
internal class DeviceSync(
    private val deviceKey: String,
    private val root: File,
    private val db: CaptureDatabase,
    plugins: List<FileSourcePlugin>,
    private val ingest: suspend (File, DeviceFile, DeviceSlice, String) -> Unit,
) {
    private val plugins = plugins.associateBy { it.id }
    private val mutableProgress = MutableStateFlow<BackfillProgress?>(null)
    val progress: StateFlow<BackfillProgress?> = mutableProgress.asStateFlow()
    companion object { private val queue = Mutex() }

    suspend fun onLinked(card: DeviceCard, link: DeviceLink, gapStart: Long?, gapEnd: Long?, clockOffsetMillis: Long?) =
        sync(card, link, gapStart, gapEnd, clockOffsetMillis, false)
    suspend fun importAll(card: DeviceCard, link: DeviceLink, clockOffsetMillis: Long?) =
        sync(card, link, null, null, clockOffsetMillis, true)

    private suspend fun problem(message: String) = db.capture().event(EventEntity(at = System.currentTimeMillis(),
        type = "DeviceSync", detail = message, batteryPercent = null, charging = null))

    private suspend fun sync(card: DeviceCard, link: DeviceLink, start: Long?, end: Long?, offset: Long?, all: Boolean) =
        withContext(Dispatchers.IO) { queue.withLock {
            if (card.offline != OfflineMode.Files) return@withLock
            val plugin = plugins[card.filePlugin]
            if (plugin == null) { problem("未注册文件插件"); return@withLock }
            try {
                root.mkdirs()
                plugin.open(link)
                val previous = db.deviceSync().rows(deviceKey).toMutableList()
                // 首次配对不列举旧录音；进程重启仍恢复未完成任务。
                if (!all && start == null && end == null && previous.none { !it.imported || (!it.deleted && plugin.caps.delete && it.fromMillis == 0L && it.toMillis == it.duration) }) return@withLock
                val files = plugin.list(link)
                if (all || start != null || end != null) for (file in files) {
                    try {
                        val slice = deviceSlice(file, start, end, offset, (plugin as? DeviceFileDetails)?.modifiedAt(file)) ?: continue
                        val covered = previous.filter { it.fileId == file.id && it.size == file.bytes && it.startedAt == file.startedAt && it.duration == file.durationMillis }
                        var ranges = listOf(slice.from to slice.to)
                        for (old in covered) ranges = ranges.flatMap { (a, b) ->
                            if (old.toMillis <= a || old.fromMillis >= b) listOf(a to b)
                            else listOf(a to minOf(b, old.fromMillis), maxOf(a, old.toMillis) to b).filter { it.first < it.second }
                        }
                        for ((from, to) in ranges) {
                            val identity = "$deviceKey|${file.id}|${file.bytes}|${file.startedAt}|${file.durationMillis}|$from|$to"
                            val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
                            val row = DeviceTransfer(key, deviceKey, file.id, file.name, file.bytes, file.startedAt,
                                file.durationMillis, file.format.name, from, to, slice.start + from - slice.from)
                            db.deviceSync().save(row); previous += row
                        }
                    } catch (e: IllegalArgumentException) { problem(e.message ?: "文件时间无效") }
                }
                val jobs = db.deviceSync().rows(deviceKey).filter { !it.deleted && (!it.imported ||
                    (plugin.caps.delete && it.fromMillis == 0L && it.toMillis == it.duration)) }
                mutableProgress.value = BackfillProgress(jobs.size, 0, jobs.sumOf { it.size ?: 0 }, 0)
                for (job in jobs) {
                    val file = files.firstOrNull { it.id == job.fileId && it.bytes == job.size && it.durationMillis == job.duration && it.startedAt == job.startedAt } ?: continue
                    try {
                        transfer(plugin, link, file, job)
                        mutableProgress.value = mutableProgress.value?.let { it.copy(doneFiles = it.doneFiles + 1) }
                    }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { problem("设备文件补传失败，已保留源文件，重连后可重试") }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { problem("设备文件列表或连接失败，已保留源文件") }
            finally { mutableProgress.value = null }
        } }

    private suspend fun transfer(plugin: FileSourcePlugin, link: DeviceLink, file: DeviceFile, initial: DeviceTransfer) {
        var row = initial
        val completedBytes = mutableProgress.value?.doneBytes ?: 0
        val local = File(root, row.key)
        if (!row.imported) {
            var valid = false
            for (attempt in 0 until 3) {
                val resume = if (plugin.caps.resume && local.isFile) minOf(row.received, local.length()) else 0
                RandomAccessFile(local, "rw").use { output ->
                    output.setLength(resume); output.seek(resume)
                    plugin.download(link, file, resume).collect { bytes ->
                        output.write(bytes); output.fd.sync()
                        row = row.copy(received = output.length()); db.deviceSync().save(row)
                        mutableProgress.value = mutableProgress.value?.let {
                            val received = completedBytes + row.received
                            it.copy(doneBytes = received, bytes = maxOf(it.bytes, received))
                        }
                    }
                }
                valid = if (plugin.caps.checksum) {
                    val algorithm = requireNotNull((plugin as? DeviceFileDetails)?.checksumAlgorithm(file)) { "缺少校验算法" }
                    val digest = MessageDigest.getInstance(algorithm)
                    local.inputStream().use { input -> val buffer = ByteArray(65536); while (true) {
                        val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n)
                    } }
                    plugin.checksum(link, file)?.contentEquals(digest.digest()) == true
                } else (plugin as? DeviceFileDetails)?.validSize(file, local) ?: (file.bytes == local.length())
                if (valid) break
                local.delete(); row = row.copy(received = 0); db.deviceSync().save(row)
            }
            check(valid) { "三次校验失败" }
            val fingerprint = audioFingerprint(local)
            // 全文件指纹只在全量导入后登记；切片分别去重，避免吞掉同文件以后的断开区间。
            if (db.folderImports().contains(fingerprint)) {
                // 共享指纹包含“已排队”，不能据此认定安全落库并删除设备原件。
                db.deviceSync().save(row.copy(imported = true, deleted = true))
                local.delete()
                return
            } else {
                val slice = DeviceSlice(row.fromMillis, row.toMillis, row.phoneStart)
                ingest(local, file, slice, row.key)
            }
            row = row.copy(imported = true); db.deviceSync().save(row)
        }
        // 部分导入不能删掉尚未存入手机的其余录音。
        if (plugin.caps.delete && row.fromMillis == 0L && row.toMillis == file.durationMillis) {
            plugin.delete(link, file); row = row.copy(deleted = true); db.deviceSync().save(row)
        }
        local.delete()
    }
}
