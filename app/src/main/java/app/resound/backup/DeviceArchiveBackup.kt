package app.resound.backup

import app.resound.devices.DeviceArchiveRecord
import app.resound.devices.DeviceArchiveBackupReceipt
import app.resound.devices.DeviceArchiveRemoteManifest
import app.resound.devices.DeviceSourceCache
import app.resound.diagnostics.FailureNotes
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID

/** Separate namespace: normal history retention/GC never removes full-source archives. */
internal object DeviceArchiveBackup {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun valid(hash: String): String { require(hash.matches(Regex("[a-f0-9]{64}"))); return hash }
    private fun objectPath(storedSha256: String) = "device-archives/${valid(storedSha256)}.bin"
    private fun manifestPath(record: DeviceArchiveRecord) = "device-archives/${valid(record.id)}.json"
    private fun workspace(root: File) = File(root,"device-archive-backup-${UUID.randomUUID()}").apply { check(mkdirs()) }
    /** Metadata discovery is not a readback receipt. Restore verifies the complete object later. */
    fun discover(store: VolumeStore, identity: String, destination: String, root: File): List<DeviceArchiveRecord> {
        val work = workspace(root)
        return try {
            store.files("device-archives").filter { it.path.matches(Regex("device-archives/[a-f0-9]{64}[.]json")) }.mapNotNull { file ->
                val target = File(work,"manifest.json")
                check(file.bytes in 1..1_048_576) { "归档清单大小异常" }
                check(store.read(file.path,target)) { "独立归档清单不可读取" }
                check(target.length() <= 1_048_576)
                val manifest = json.decodeFromString<DeviceArchiveRemoteManifest>(target.readText())
                val record = manifest.record
                check(manifest.format == 1 && file.path == manifestPath(record)) { "归档清单身份不匹配" }
                valid(record.archiveSha256);valid(record.sourceSha256);valid(manifest.storedSha256)
                if (record.deviceIdentity != identity) return@mapNotNull null
                record.copy(imported = false,backup = DeviceArchiveBackupReceipt(destination,objectPath(manifest.storedSha256),
                    file.path,record.archiveSha256,record.archiveBytes,0))
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { FailureNotes.record("backup/DeviceArchiveBackup:discoverManifest",e);throw e }
        finally { work.deleteRecursively() }
    }
    fun upload(store: VolumeStore, record: DeviceArchiveRecord, source: File, passphrase: String?, destination: String,
               now: () -> Long, root: File): DeviceArchiveBackupReceipt {
        require(source.length() == record.archiveBytes && DeviceSourceCache.sha256(source) == record.archiveSha256)
        val work = workspace(root)
        try {
            val encoded = if (passphrase == null) source else File(work,"encrypted.bin").also { BackupEncryption.encrypt(source,it,passphrase) }
            val manifest = DeviceArchiveRemoteManifest(record = record.copy(backup = null),encrypted = passphrase != null,
                storedSha256 = DeviceSourceCache.sha256(encoded),storedBytes = encoded.length())
            val metadata = File(work,"manifest.json").apply { writeText(json.encodeToString(manifest)) }
            store.put(objectPath(manifest.storedSha256),encoded)
            store.put(manifestPath(record),metadata)
            // A PUT/ETag/existence check is not proof of a recoverable full audio copy.
            verifyInto(store,record.copy(backup = null),passphrase,work)
            return DeviceArchiveBackupReceipt(destination,objectPath(manifest.storedSha256),manifestPath(record),record.archiveSha256,
                record.archiveBytes,now())
        } finally { work.deleteRecursively() }
    }
    private fun verifyInto(store: VolumeStore, record: DeviceArchiveRecord, passphrase: String?, work: File): File {
        val manifest = try {
            val metadata = File(work,"readback.json");check(store.read(manifestPath(record),metadata)) { "独立归档清单不可读取" }
            json.decodeFromString<DeviceArchiveRemoteManifest>(metadata.readText())
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { FailureNotes.record("backup/DeviceArchiveBackup:readbackManifest",e);throw e }
        check(manifest.format == 1 && manifest.record.id == record.id && manifest.record.deviceIdentity == record.deviceIdentity &&
            manifest.record.archiveSha256 == record.archiveSha256 && manifest.record.archiveBytes == record.archiveBytes &&
            manifest.record.sourceSha256 == record.sourceSha256) { "独立归档清单不匹配" }
        record.backup?.let { check(it.objectPath == objectPath(manifest.storedSha256) && it.manifestPath == manifestPath(record)) { "独立归档凭证已变化" } }
        val objectFile = File(work,"readback.bin");check(store.read(objectPath(manifest.storedSha256),objectFile)) { "独立归档原声不可读取" }
        check(objectFile.length() == manifest.storedBytes && DeviceSourceCache.sha256(objectFile) == manifest.storedSha256)
        check(BackupEncryption.isEncrypted(objectFile) == manifest.encrypted) { "归档加密声明不一致" }
        val plain = if (manifest.encrypted) File(work,"plain.audio").also { BackupEncryption.decrypt(objectFile,it,passphrase,null) } else objectFile
        check(plain.length() == record.archiveBytes && DeviceSourceCache.sha256(plain) == record.archiveSha256) { "独立归档读回校验失败" }
        return plain
    }
    fun verify(store: VolumeStore, record: DeviceArchiveRecord, passphrase: String?, root: File): Boolean {
        val work = workspace(root)
        return try { verifyInto(store,record,passphrase,work); true } finally { work.deleteRecursively() }
    }
    fun restore(store: VolumeStore, record: DeviceArchiveRecord, passphrase: String?, root: File, destination: File) {
        val work = workspace(root)
        try {
            val plain = verifyInto(store,record,passphrase,work)
            val atomic = android.util.AtomicFile(destination); val output = atomic.startWrite()
            try { plain.inputStream().use { it.copyTo(output) }; atomic.finishWrite(output) }
            catch(e: Exception) { atomic.failWrite(output);throw e }
        } finally { work.deleteRecursively() }
    }
}
