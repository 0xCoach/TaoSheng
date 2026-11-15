package app.resound.devices

import android.content.Context
import android.util.AtomicFile
import app.resound.data.CaptureDatabase
import app.resound.devices.driver.*
import app.resound.backup.AndroidBackupRepository
import app.resound.importer.ImportDecoder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Files, decoding and persistence adapter. Retention eligibility stays in shared. */
internal class DeviceArchiveRepository(
    private val context: Context,
    private val db: CaptureDatabase,
    private val clock: ClockPort,
    private val backup: () -> AndroidBackupRepository?,
    private val encode: (String,File,File) -> Boolean,
    private val importArchive: suspend (DeviceArchiveRecord,File) -> Unit,
) {
    private val root = File(context.filesDir,"device-archives").apply { mkdirs() }
    private val gate = Mutex()
    private val cleanupGate = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val installFile = File(context.noBackupFilesDir,"device-archive-installation")
    val installation = if (installFile.isFile) installFile.readText() else UUID.randomUUID().toString().also {
        installFile.parentFile!!.mkdirs(); installFile.writeText(it)
    }
    private val rows = MutableStateFlow(load())
    val records = rows.asStateFlow()
    private val configurations = MutableStateFlow<Map<String,DeviceArchiveOptions>>(emptyMap())
    val options = configurations.asStateFlow()
    private val messages = MutableStateFlow<Map<String,String>>(emptyMap())
    val status = messages.asStateFlow()
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun safe(value: String): String { require(value.matches(Regex("[a-f0-9]{64}"))); return value }
    private fun manifest(id: String) = AtomicFile(File(root,"${safe(id)}.json"))
    fun audio(record: DeviceArchiveRecord) = File(root,"${safe(record.archiveSha256)}.audio")
    private fun load() = root.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}[.]json([.]bak)?")) }
        .map { if (it.name.endsWith(".bak")) File(it.path.removeSuffix(".bak")) else it }.distinctBy { it.name }.mapNotNull { file ->
        runCatching { json.decodeFromString<DeviceArchiveRecord>(AtomicFile(file).openRead().bufferedReader().use { it.readText() }) }
            .getOrNull()?.takeIf { it.id == file.name.removeSuffix(".json") }
    }
    fun configuration(identity: String): DeviceArchiveOptions {
        configurations.value[identity]?.let { return it }
        val file = AtomicFile(File(root,"options-${hash(identity)}.json"))
        val value = runCatching { json.decodeFromString<DeviceArchiveOptions>(file.openRead().bufferedReader().use { it.readText() }) }.getOrDefault(DeviceArchiveOptions()).forInstallation(installation)
        configurations.update { it + (identity to value) }; return value
    }
    private fun write(file: AtomicFile, text: String) {
        val output = file.startWrite()
        try { output.write(text.toByteArray()); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }
    suspend fun configure(identity: String, value: DeviceArchiveOptions) = withContext(Dispatchers.IO) { gate.withLock {
        val saved = value.copy(installation = installation)
        write(AtomicFile(File(root,"options-${hash(identity)}.json")),json.encodeToString(saved))
        configurations.update { it + (identity to saved) }
        val enabled = root.listFiles().orEmpty().filter { it.name.matches(Regex("options-[a-f0-9]{64}[.]json")) }.any { candidate ->
            runCatching { json.decodeFromString<DeviceArchiveOptions>(AtomicFile(candidate).openRead().bufferedReader().use { it.readText() })
                .forInstallation(installation).retention.enabled }.getOrDefault(false)
        }
        DeviceRetentionScheduler.configure(context,enabled)
        if (saved.retention.enabled) DeviceRetentionScheduler.runNow(context)
    } }
    private fun saveLocked(value: DeviceArchiveRecord) {
        write(manifest(value.id),json.encodeToString(value))
        rows.update { list -> list.filterNot { it.id == value.id } + value }
    }
    private suspend fun update(id: String, transform: (DeviceArchiveRecord) -> DeviceArchiveRecord) = gate.withLock {
        rows.value.firstOrNull { it.id == id }?.let { saveLocked(transform(it)) }
    }
    private fun message(identity: String, text: String) { messages.update { it + (identity to text) } }
    fun operationStopped(identity: String, cancelled: Boolean) {
        message(identity,if(cancelled) "操作已取消，请查看各项结果" else "操作未完成，请查看错误和各项结果")
    }
    suspend fun needsArchive(identity: String, source: DeviceFile): Boolean = withContext(Dispatchers.IO) {
        configuration(identity).archiveEnabled && rows.value.none { record ->
            record.deviceIdentity == identity && record.fileId == source.id && record.sourceBytes == source.bytes &&
                record.startedAtMillis == source.startedAt && record.durationMillis == source.durationMillis &&
                record.installation == installation && audio(record).isFile && DeviceSourceCache.sha256(audio(record)) == record.archiveSha256
        }
    }
    suspend fun archive(driver: String, identity: String, handle: String, source: DeviceFile, raw: File, sourceHash: String) = withContext(Dispatchers.IO) {
        if (!configuration(identity).archiveEnabled) return@withContext
        val id = hash("$driver|$identity|${source.id}|${source.bytes}|${source.startedAt}|${source.durationMillis}|${source.format}|$sourceHash")
        rows.value.firstOrNull { it.id == id }?.let { old ->
            if (old.installation == installation && audio(old).isFile && DeviceSourceCache.sha256(audio(old)) == old.archiveSha256) return@withContext
        }
        message(identity,"正在保存和校验完整原声")
        val temp = File(root,".$id.part")
        try {
            val converted = encode(driver,raw,temp)
            if (!converted) raw.copyTo(temp,true)
            var samples = 0L
            if (!converted && source.format == FileFormat.Pcm16) {
                require(temp.length()%2==0L); samples = temp.length()/2
            } else ImportDecoder().decode(temp,consume = { pcm -> samples += pcm.size })
            val expected = requireNotNull(source.durationMillis)
            require(samples > 0 && samples / 16 >= expected - 100) { "完整归档的音频时长不足" }
            val sha = DeviceSourceCache.sha256(temp); val target = File(root,"$sha.audio")
            if (!target.isFile || DeviceSourceCache.sha256(target) != sha) {
                val output = AtomicFile(target); val stream = output.startWrite()
                try { temp.inputStream().use { it.copyTo(stream) }; output.finishWrite(stream) }
                catch (e: Exception) { output.failWrite(stream); throw e }
            }
            gate.withLock {
                val old = rows.value.firstOrNull { it.id == id }
                saveLocked(DeviceArchiveRecord(id,driver,identity,source.id,source.startedAt,expected,clock.nowEpochMillis(),
                    raw.length(),sourceHash,target.length(),sha,if(converted) FileFormat.Ogg.name else source.format.name,
                    samples,clock.nowEpochMillis(),installation,protected = old?.protected ?: false,
                    deletion = old?.deletion ?: DeviceDeleteState.Present,localDeviceHandle = handle))
            }
            message(identity,"完整原声已归档；导入与独立备份分别确认")
        } finally { temp.delete() }
    }
    suspend fun refreshImports(identity: String, fileId: String? = null) = withContext(Dispatchers.IO) {
        // Do not treat a fingerprint shortcut or a seeded coverage row as a completed decode/import receipt.
        for (record in rows.value.filter { it.deviceIdentity == identity && !it.imported && (fileId == null || it.fileId == fileId) }) {
            val candidates = db.deviceSync().rows(record.localDeviceHandle).filter {
                it.fileId == record.fileId && it.size == record.sourceBytes && it.startedAt == record.startedAtMillis && it.duration == record.durationMillis
            }
            val ranges = candidates.filter { row ->
                val pending = UUID.nameUUIDFromBytes(row.key.toByteArray()).toString()
                db.imports().byPending(pending)?.let { it.ingestComplete && it.error == null } == true
            }.map { it.fromMillis to it.toMillis }
            if (deviceImportCoverageComplete(record.durationMillis,ranges)) update(record.id) { it.copy(imported = true) }
        }
        if (configuration(identity).retention.enabled) DeviceRetentionScheduler.runNow(context)
    }
    suspend fun backupPending(identity: String) {
        val settings = configuration(identity).retention
        if (!settings.enabled) return
        for (record in rows.value.filter { it.deviceIdentity == identity && it.installation == installation && it.imported && it.backup == null &&
                deviceArchiveEnrolled(it,settings,clock.nowEpochMillis()) }) {
            currentCoroutineContext().ensureActive();backUp(record.id)
        }
    }
    suspend fun protect(id: String, value: Boolean) = withContext(Dispatchers.IO) {
        update(id) {
            check(it.deletion !in setOf(DeviceDeleteState.Intent,DeviceDeleteState.Uncertain)) { "清理已提交或结果待核对，保护状态未更改" }
            it.copy(protected = value)
        }
    }
    suspend fun backUp(id: String) = withContext(Dispatchers.IO) {
        val record = rows.value.first { it.id == id }; check(record.installation == installation)
        val local = audio(record); check(local.isFile && local.length() == record.archiveBytes && DeviceSourceCache.sha256(local) == record.archiveSha256)
        message(record.deviceIdentity,"正在独立备份并读回校验")
        val receipt = requireNotNull(backup()) { "请先配置独立备份（USB 电脑或云端）" }.backupDeviceArchive(record,local,clock::nowEpochMillis)
        update(id) { it.copy(backup = receipt,error = null) }; message(record.deviceIdentity,"完整原声已独立备份并读回校验")
        if (configuration(record.deviceIdentity).retention.enabled) DeviceRetentionScheduler.runNow(context)
    }
    suspend fun restore(id: String, fromIndependentBackup: Boolean = false) = withContext(Dispatchers.IO) {
        val record = rows.value.first { it.id == id }; val local = audio(record)
        if (fromIndependentBackup || !local.isFile || DeviceSourceCache.sha256(local) != record.archiveSha256)
            requireNotNull(backup()) { "本机原声缺失，请配置原独立备份目的地" }.restoreDeviceArchive(record,local)
        check(local.isFile && DeviceSourceCache.sha256(local) == record.archiveSha256)
        importArchive(record,local)
        update(id) { it.copy(imported = true) }
        message(record.deviceIdentity,if(fromIndependentBackup) "已从独立备份完整读回并导入涛声；设备原件状态不变" else "本机完整归档已导入涛声；设备原件状态不变")
    }
    /** Non-destructive same-source check using the user's current BLE / fast-transfer preference. */
    suspend fun verifyDeviceSource(id: String, connection: DriverConnection) = withContext(Dispatchers.IO) {
        val record = rows.value.first { it.id == id }
        check(connection.identity.scope == IdScope.Global && connection.identity.id.value == record.deviceIdentity) { "设备稳定身份不匹配" }
        val files = requireNotNull(connection.files) { "设备不支持读取原件" }
        connection.controls.refresh()
        check(connection.status.value.recording == RecordingState.Stopped) { "请先停止并保存设备录音" }
        val file = files.list().singleOrNull { it.id == record.fileId } ?: error("设备原件已不在，完整归档仍保留")
        check(file.bytes == record.sourceBytes && file.durationMillis == record.durationMillis && file.start.epochMillis == record.startedAtMillis) { "设备原件身份变化，未继续读取" }
        val hash = MessageDigest.getInstance("SHA-256");var received=0L;var shown=clock.monotonicMillis()
        message(record.deviceIdentity,"正在整段读取设备原件并核对 SHA-256，不会导入或删除")
        files.read(file,0).collect { bytes ->
            hash.update(bytes);received += bytes.size
            if(clock.monotonicMillis()-shown>=1000) {
                message(record.deviceIdentity,"正在核对设备原件：$received / ${record.sourceBytes} 字节")
                shown=clock.monotonicMillis()
            }
        }
        val actual=hash.digest().joinToString("") { "%02x".format(it) }
        check(received == record.sourceBytes && actual == record.sourceSha256) { "设备原件与完整归档凭证不一致，未更改任何原件" }
        message(record.deviceIdentity,"设备原件整段 SHA-256 与归档凭证一致；未重复导入、未删除")
    }
    fun backupDestination() = backup()?.deviceArchiveDestination()
    suspend fun discoverBackup(identity: String) = withContext(Dispatchers.IO) {
        val recovered = requireNotNull(backup()) { "请先配置原独立备份目的地" }.discoverDeviceArchives(identity)
        gate.withLock {
            for (record in recovered) if (rows.value.none { it.id == record.id }) saveLocked(record)
        }
        message(identity,"已读取独立备份清单；从归档导入时会重新下载并校验完整原声，不继承自动清理资格")
    }
    suspend fun cleanup(connection: DriverConnection, automatic: Boolean = true, selectedIds: Set<String>? = null) = withContext(Dispatchers.IO) {
        cleanupGate.withLock {
            val identity = connection.identity.id.value
            if (connection.identity.scope != IdScope.Global) { message(identity,"设备没有全局稳定身份，未执行自动清理");return@withLock }
            val settings = configuration(identity).retention
            if (automatic && !settings.enabled) return@withLock
            (connection as? DeviceFileMaintenanceConnection)?.prepareFileMaintenance()
            val files = connection.files ?: return@withLock
            if (!files.caps.delete && automatic) return@withLock
            check(files.caps.delete) { "设备未开放单文件删除" }
            connection.controls.refresh()
            if (connection.status.value.recording != RecordingState.Stopped) { message(identity,"设备尚未停止保存，已跳过清理");return@withLock }
            var removed = 0
            for (prior in rows.value.filter { it.deviceIdentity == identity && (selectedIds == null || it.id in selectedIds) }) {
                ensureActive()
                val currentFiles = files.list()
                val target = currentFiles.firstOrNull { it.id == prior.fileId }
                if (prior.deletion in setOf(DeviceDeleteState.Intent,DeviceDeleteState.Uncertain)) {
                    if (target == null) update(prior.id) { it.copy(deletion = DeviceDeleteState.Gone,deletionAtMillis = clock.nowEpochMillis(),error = null) }
                    else message(identity,"上次清理结果待核对，未重发删除命令")
                    continue
                }
                val current = rows.value.first { it.id == prior.id }
                val state = connection.status.value
                val block = deviceRetentionBlock(current,configuration(identity).retention,clock.nowEpochMillis(),identity,installation,
                    backupDestination(),state.recording == RecordingState.Stopped,state.batteries.firstOrNull { it.part == 1 }?.percent,automatic)
                if (block != DeviceRetentionBlock.None) continue
                if (target == null) { update(current.id) { it.copy(deletion = DeviceDeleteState.RemovedElsewhere) }; continue }
                check(target.bytes == current.sourceBytes && target.durationMillis == current.durationMillis && target.start.epochMillis == current.startedAtMillis) { "设备文件身份变化，取消清理" }
                check(audio(current).isFile && DeviceSourceCache.sha256(audio(current)) == current.archiveSha256) { "本机完整归档不可用" }
                check(backup()?.verifyDeviceArchiveBackup(current) == true) { "独立备份需要重新验证" }
                val digest = MessageDigest.getInstance("SHA-256"); var size = 0L
                files.read(target,0).collect { bytes -> digest.update(bytes); size += bytes.size }
                val sourceHash = digest.digest().joinToString("") { "%02x".format(it) }
                check(size == current.sourceBytes && sourceHash == current.sourceSha256) { "设备原件与归档不一致，取消清理" }
                val intent = gate.withLock {
                    val latest = rows.value.first { it.id == current.id }
                    if (deviceRetentionBlock(latest,configuration(identity).retention,clock.nowEpochMillis(),identity,installation,
                        backupDestination(),connection.status.value.recording == RecordingState.Stopped,
                        connection.status.value.batteries.firstOrNull { it.part == 1 }?.percent,automatic) != DeviceRetentionBlock.None) null
                    else latest.copy(deletion = DeviceDeleteState.Intent,deletionAtMillis = clock.nowEpochMillis()).also { saveLocked(it) }
                } ?: continue
                try {
                    files.confirm(target,sourceHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
                    files.delete(target)
                    val gone = files.list().none { it.id == target.id }
                    if (gone) removed++
                    update(intent.id) { it.copy(deletion = if (gone) DeviceDeleteState.Gone else DeviceDeleteState.Uncertain,
                        error = if (gone) null else "收到回执，但文件仍存在，等待核对") }
                } catch (e: Exception) {
                    withContext(NonCancellable) { update(intent.id) { it.copy(deletion = DeviceDeleteState.Uncertain,error = "删除结果待核对，未自动重发") } }
                    throw e
                }
            }
            message(identity,"清理检查完成：本次已删除 $removed 条设备原件；未满足条件的已跳过，完整归档和独立备份保留")
        }
    }
}
