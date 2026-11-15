package app.resound.devices

import app.resound.diagnostics.FailureNotes

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
internal fun deviceTransferKey(device: String, file: DeviceFile, from: Long, to: Long): String {
    val identity = "$device|${file.id}|${file.bytes}|${file.startedAt}|${file.durationMillis}|$from|$to"
    return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
}
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
    private val newestFirst: Boolean = false,
    private val elapsedMillis: () -> Long = { 0L },
    private val onDetail: (DeviceSyncDetail) -> Unit = {},
    private val onVerifiedSource: suspend (DeviceFile, File, String) -> Unit = { _, _, _ -> },
    private val onImportedSource: suspend (DeviceFile) -> Unit = {},
    private val needsArchive: suspend (DeviceFile) -> Boolean = { false },
) {
    private val plugins = plugins.associateBy { it.id }
    private val mutableProgress = MutableStateFlow<BackfillProgress?>(null)
    val progress: StateFlow<BackfillProgress?> = mutableProgress.asStateFlow()
    private var meter: DeviceSyncMeter? = null
    private var detail = DeviceSyncDetail(DeviceSyncPhase.Waiting)
    private fun show(value: DeviceSyncDetail) {
        detail = value; onDetail(value)
        mutableProgress.value = mutableProgress.value?.copy(files = value.tasks, doneFiles = value.doneTasks,
            bytes = value.totalBytes ?: value.receivedBytes, doneBytes = value.receivedBytes)
    }
    private fun phase(value: DeviceSyncPhase, fromCache: Boolean = false) =
        show(meter?.phase(value, elapsedMillis(), fromCache) ?: DeviceSyncDetail(value))
    companion object { private val queue = Mutex() }

    suspend fun onLinked(card: DeviceCard, link: DeviceLink, gapStart: Long?, gapEnd: Long?, clockOffsetMillis: Long?) =
        sync(card, link, gapStart, gapEnd, clockOffsetMillis, false)
    suspend fun importAll(card: DeviceCard, link: DeviceLink, clockOffsetMillis: Long?) =
        sync(card, link, null, null, clockOffsetMillis, true)
    suspend fun importSelected(card: DeviceCard, link: DeviceLink, clockOffsetMillis: Long?, fileIds: Set<String>) {
        require(fileIds.isNotEmpty())
        sync(card,link,null,null,clockOffsetMillis,true,fileIds)
    }

    private suspend fun problem(message: String) = db.capture().event(EventEntity(at = System.currentTimeMillis(),
        type = "DeviceSync", detail = message, batteryPercent = null, charging = null))

    private suspend fun sync(card: DeviceCard, link: DeviceLink, start: Long?, end: Long?, offset: Long?, all: Boolean,
                             selectedFileIds: Set<String>? = null) =
        withContext(Dispatchers.IO) { queue.withLock {
            meter = null
            phase(DeviceSyncPhase.Waiting)
            if (card.offline != OfflineMode.Files) return@withLock
            val plugin = plugins[card.filePlugin]
            if (plugin == null) { problem("未注册文件插件"); return@withLock }
            try {
                phase(DeviceSyncPhase.Listing)
                root.mkdirs()
                plugin.open(link)
                val previous = db.deviceSync().rows(deviceKey).toMutableList()
                // 首次配对不列举旧录音；进程重启仍恢复未完成任务。
                if (!all && start == null && end == null && previous.none { !it.imported }) {
                    phase(DeviceSyncPhase.Completed);return@withLock
                }
                val files = plugin.list(link).filter { selectedFileIds == null || it.id in selectedFileIds }
                if (all || start != null || end != null) for (file in files) {
                    try {
                        val slice = deviceSlice(file, start, end, offset, (plugin as? DeviceFileDetails)?.modifiedAt(file)) ?: continue
                        val covered = previous.filter { !it.key.startsWith("archive-") && it.fileId == file.id && it.size == file.bytes && it.startedAt == file.startedAt && it.duration == file.durationMillis }
                        var ranges = listOf(slice.from to slice.to)
                        for (old in covered) ranges = ranges.flatMap { (a, b) ->
                            if (old.toMillis <= a || old.fromMillis >= b) listOf(a to b)
                            else listOf(a to minOf(b, old.fromMillis), maxOf(a, old.toMillis) to b).filter { it.first < it.second }
                        }
                        for ((from, to) in ranges) {
                            val key = deviceTransferKey(deviceKey, file, from, to)
                            val row = DeviceTransfer(key, deviceKey, file.id, file.name, file.bytes, file.startedAt,
                                file.durationMillis, file.format.name, from, to, slice.start + from - slice.from)
                            db.deviceSync().save(row); previous += row
                        }
                    } catch (e: IllegalArgumentException) { problem(e.message ?: "文件时间无效") }
                }
                // Archival is independent of VAD/live coverage. Already-imported files may still
                // need their complete source archived, without importing duplicate timeline audio.
                for (file in files) if (needsArchive(file) && db.deviceSync().rows(deviceKey).none {
                        !it.imported && it.fileId == file.id && it.size == file.bytes && it.startedAt == file.startedAt && it.duration == file.durationMillis }) {
                    val slice = deviceSlice(file,null,null,offset,(plugin as? DeviceFileDetails)?.modifiedAt(file)) ?: continue
                    val key = "archive-${deviceSourceKey(deviceKey,file)}"
                    db.deviceSync().save(DeviceTransfer(key,deviceKey,file.id,file.name,file.bytes,file.startedAt,
                        file.durationMillis,file.format.name,0,requireNotNull(file.durationMillis),slice.start))
                }
                val pending = db.deviceSync().rows(deviceKey).filter { !it.deleted && !it.imported &&
                    (selectedFileIds == null || it.fileId in selectedFileIds) }
                val jobs = if (newestFirst) pending.sortedByDescending { it.phoneStart } else pending
                meter = DeviceSyncMeter(jobs.map { row ->
                    val file = files.firstOrNull { it.id == row.fileId && it.bytes == row.size && it.startedAt == row.startedAt && it.durationMillis == row.duration }
                    DeviceSyncTask(row.key, file?.let { deviceSourceKey(deviceKey,it) } ?: row.key, row.size, if(row.key.startsWith("archive-")) 0 else row.toMillis-row.fromMillis)
                })
                mutableProgress.value = BackfillProgress(jobs.size, 0, jobs.distinctBy { it.fileId to (it.size to it.startedAt) }.sumOf { it.size ?: 0 }, 0)
                var failed = false
                for (job in jobs) {
                    val file = files.firstOrNull { it.id == job.fileId && it.bytes == job.size && it.durationMillis == job.duration && it.startedAt == job.startedAt }
                    if (file == null) { failed = true; continue }
                    try {
                        transfer(plugin, link, file, job)
                        onImportedSource(file)
                        show(meter!!.completed(job.key, elapsedMillis()))
                    }
                    catch (e: CancellationException) { throw e }
                    catch (caught: app.resound.devices.driver.DeviceException) {
                        failed = true;problem("传输通道中断，已保留进度")
                        if (caught.code in setOf(app.resound.devices.driver.ErrorCode.Disconnected,app.resound.devices.driver.ErrorCode.Timeout)) throw caught
                        FailureNotes.record("devices/DeviceSync.kt:transfer",caught)
                    }
                    catch (caught: Exception) {
                        FailureNotes.record("devices/DeviceSync.kt:transfer",caught)
                        failed = true; problem("设备文件补传失败，已保留源文件，重连后可重试")
                    }
                }
                phase(if (failed) DeviceSyncPhase.Failed else DeviceSyncPhase.Completed)
            } catch (e: CancellationException) { phase(DeviceSyncPhase.Paused); throw e }
            catch (e: app.resound.devices.driver.DeviceException) { phase(DeviceSyncPhase.Failed);throw e }
            catch (caught: Exception) {
                FailureNotes.record("devices/DeviceSync.kt:listing",caught)
                phase(DeviceSyncPhase.Failed); problem("设备文件列表或连接失败，已保留源文件")
            }
            finally { mutableProgress.value = null }
        } }

    private suspend fun transfer(plugin: FileSourcePlugin, link: DeviceLink, file: DeviceFile, initial: DeviceTransfer) {
        var row = initial
        val cache = DeviceSourceCache(root, deviceSourceKey(deviceKey,file))
        val local = cache.file
        val peers = db.deviceSync().rows(deviceKey).filter { it.fileId == file.id && it.size == file.bytes && it.startedAt == file.startedAt && it.duration == file.durationMillis && it.format == file.format.name }
        // Adopt an earlier per-range cache without discarding already committed bytes.
        if (!local.exists()) peers.sortedByDescending { it.received }.firstOrNull { File(root,it.key).isFile }?.let { old ->
            check(File(root,old.key).renameTo(local)) { "无法迁移补传缓存" }
        }
        val reusable = cache.verified() && file.bytes?.let { it == local.length() } != false
        if (!reusable && local.isFile && (cache.hasReceipt() || file.bytes?.let { local.length() >= it } == true)) cache.discard()
        val durable = if (reusable) local.length() else deviceDurablePrefix(local.takeIf(File::isFile)?.length() ?: 0,
            peers.maxOfOrNull { it.received } ?: 0, file.bytes, plugin.caps.resume)
        show(meter!!.begin(row.key,file.name,durable,elapsedMillis()))
        if (!row.imported) {
            var valid = reusable
            val reused = valid
            for (attempt in 0 until 3) {
                if (valid) break
                cache.invalidate()
                val committed = db.deviceSync().rows(deviceKey).filter { it.fileId == file.id && it.size == file.bytes && it.startedAt == file.startedAt && it.duration == file.durationMillis && it.format == file.format.name }.maxOfOrNull { it.received } ?: 0
                val resume = deviceDurablePrefix(local.takeIf(File::isFile)?.length() ?: 0, committed, file.bytes, plugin.caps.resume)
                RandomAccessFile(local, "rw").use { output ->
                    output.setLength(resume); output.seek(resume)
                    plugin.download(link, file, resume).collect { bytes ->
                        output.write(bytes); output.fd.sync()
                        row = row.copy(received = output.length()); db.deviceSync().save(row)
                        show(meter!!.bytes(row.received,elapsedMillis()))
                    }
                }
                phase(DeviceSyncPhase.Verifying)
                valid = if (plugin.caps.checksum) {
                    val algorithm = requireNotNull((plugin as? DeviceFileDetails)?.checksumAlgorithm(file)) { "缺少校验算法" }
                    val digest = MessageDigest.getInstance(algorithm)
                    local.inputStream().use { input -> val buffer = ByteArray(65536); while (true) {
                        val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n)
                    } }
                    plugin.checksum(link, file)?.contentEquals(digest.digest()) == true
                } else (plugin as? DeviceFileDetails)?.validSize(file, local) ?: (file.bytes == local.length())
                if (valid) break
                cache.discard(); row = row.copy(received = 0); db.deviceSync().save(row)
            }
            check(valid) { "三次校验失败" }
            val sourceHash = cache.commit()
            onVerifiedSource(file,local,sourceHash)
            if (row.key.startsWith("archive-")) {
                db.deviceSync().save(row.copy(imported = true))
                discardIfFinished(cache,file)
                return
            }
            phase(DeviceSyncPhase.Importing,reused)
            val fingerprint = audioFingerprint(local)
            // 全文件指纹只在全量导入后登记；切片分别去重，避免吞掉同文件以后的断开区间。
            if (db.folderImports().contains(fingerprint)) {
                // 共享指纹包含“已排队”，不能据此认定安全落库并删除设备原件。
                db.deviceSync().save(row.copy(imported = true, deleted = true))
                discardIfFinished(cache,file)
                return
            } else {
                val slice = DeviceSlice(row.fromMillis, row.toMillis, row.phoneStart)
                ingest(local, file, slice, row.key)
            }
            row = row.copy(imported = true); db.deviceSync().save(row)
        }
        // Capability is not deletion policy. Only the archive/backup retention coordinator can delete.
        discardIfFinished(cache,file)
    }

    private suspend fun discardIfFinished(cache: DeviceSourceCache, file: DeviceFile) {
        if (db.deviceSync().rows(deviceKey).none { it.fileId == file.id && it.size == file.bytes && it.startedAt == file.startedAt &&
                it.duration == file.durationMillis && it.format == file.format.name && !it.imported }) cache.discard()
    }
}
