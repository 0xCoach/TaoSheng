package app.resound.backup

import android.util.AtomicFile
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
    private const val DIRECTORY = "device-archives"
    private const val MAX_MANIFEST_BYTES = 1_048_576L
    private val hashPattern = Regex("[a-f0-9]{64}")
    private val manifestPattern = Regex("$DIRECTORY/[a-f0-9]{64}[.]json")

    private fun archivePath(hash: String, extension: String): String {
        require(hashPattern.matches(hash))
        return "$DIRECTORY/$hash.$extension"
    }

    private fun manifestPath(record: DeviceArchiveRecord) = archivePath(record.id, "json")

    private inline fun <T> inWorkspace(root: File, action: (File) -> T): T {
        val work = File(root, "device-archive-backup-${UUID.randomUUID()}")
        check(work.mkdirs())
        try {
            return action(work)
        } finally {
            work.deleteRecursively()
        }
    }

    /** Metadata discovery is not a readback receipt. Restore verifies the complete object later. */
    fun discover(store: VolumeStore, identity: String, destination: String, root: File): List<DeviceArchiveRecord> =
        inWorkspace(root) { work ->
            try {
                buildList {
                    val target = File(work, "manifest.json")
                    for (file in store.files(DIRECTORY)) {
                        if (!manifestPattern.matches(file.path)) continue
                        check(file.bytes in 1..MAX_MANIFEST_BYTES) { "归档清单大小异常" }
                        check(store.read(file.path, target)) { "独立归档清单不可读取" }
                        check(target.length() <= MAX_MANIFEST_BYTES)
                        val manifest = json.decodeFromString<DeviceArchiveRemoteManifest>(target.readText())
                        val record = manifest.record
                        check(manifest.format == 1 && file.path == manifestPath(record)) { "归档清单身份不匹配" }
                        require(hashPattern.matches(record.archiveSha256) && hashPattern.matches(record.sourceSha256))
                        val objectPath = archivePath(manifest.storedSha256, "bin")
                        if (record.deviceIdentity == identity) {
                            val receipt = DeviceArchiveBackupReceipt(destination, objectPath, file.path,
                                record.archiveSha256, record.archiveBytes, 0)
                            add(record.copy(imported = false, backup = receipt))
                        }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                FailureNotes.record("backup/DeviceArchiveBackup:discoverManifest", error)
                throw error
            }
        }

    fun upload(store: VolumeStore, record: DeviceArchiveRecord, source: File, passphrase: String?, destination: String,
               now: () -> Long, root: File): DeviceArchiveBackupReceipt = inWorkspace(root) { work ->
        require(matchesAudio(source, record.archiveBytes, record.archiveSha256))
        val encoded = passphrase?.let { secret ->
            File(work, "encrypted.bin").also { BackupEncryption.encrypt(source, it, secret) }
        } ?: source
        val storedHash = DeviceSourceCache.sha256(encoded)
        val receipt = DeviceArchiveBackupReceipt(destination, archivePath(storedHash, "bin"),
            manifestPath(record), record.archiveSha256, record.archiveBytes, 0)
        val manifest = DeviceArchiveRemoteManifest(record = record.copy(backup = null), encrypted = passphrase != null,
            storedSha256 = storedHash, storedBytes = encoded.length())
        val metadata = File(work, "manifest.json").apply { writeText(json.encodeToString(manifest)) }
        store.put(receipt.objectPath, encoded)
        store.put(receipt.manifestPath, metadata)
        verifyInto(store, record.copy(backup = receipt), passphrase, work)
        receipt.copy(verifiedAtMillis = now())
    }

    private fun matchesAudio(file: File, bytes: Long, sha256: String) =
        file.length() == bytes && DeviceSourceCache.sha256(file) == sha256

    private fun verifyInto(store: VolumeStore, record: DeviceArchiveRecord, passphrase: String?, work: File): File {
        val manifestPath = manifestPath(record)
        val manifest = try {
            val metadata = File(work, "readback.json")
            check(store.read(manifestPath, metadata)) { "独立归档清单不可读取" }
            json.decodeFromString<DeviceArchiveRemoteManifest>(metadata.readText())
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FailureNotes.record("backup/DeviceArchiveBackup:readbackManifest", error)
            throw error
        }
        val found = manifest.record
        check(manifest.format == 1 && found.id == record.id && found.deviceIdentity == record.deviceIdentity &&
            found.archiveSha256 == record.archiveSha256 && found.archiveBytes == record.archiveBytes &&
            found.sourceSha256 == record.sourceSha256) { "独立归档清单不匹配" }
        val objectPath = archivePath(manifest.storedSha256, "bin")
        record.backup?.let { receipt ->
            check(receipt.objectPath == objectPath && receipt.manifestPath == manifestPath) { "独立归档凭证已变化" }
        }

        val encoded = File(work, "readback.bin")
        check(store.read(objectPath, encoded)) { "独立归档原声不可读取" }
        check(matchesAudio(encoded, manifest.storedBytes, manifest.storedSha256))
        check(BackupEncryption.isEncrypted(encoded) == manifest.encrypted) { "归档加密声明不一致" }
        val plain = if (!manifest.encrypted) encoded else File(work, "plain.audio").also {
            BackupEncryption.decrypt(encoded, it, passphrase, null)
        }
        check(matchesAudio(plain, record.archiveBytes, record.archiveSha256)) { "独立归档读回校验失败" }
        return plain
    }

    fun verify(store: VolumeStore, record: DeviceArchiveRecord, passphrase: String?, root: File): Boolean =
        inWorkspace(root) { work ->
            verifyInto(store, record, passphrase, work)
            true
        }

    fun restore(store: VolumeStore, record: DeviceArchiveRecord, passphrase: String?, root: File, destination: File) {
        inWorkspace(root) { work ->
            val verified = verifyInto(store, record, passphrase, work)
            val atomic = AtomicFile(destination)
            val output = atomic.startWrite()
            try {
                verified.inputStream().use { it.copyTo(output) }
                atomic.finishWrite(output)
            } catch (error: Exception) {
                atomic.failWrite(output)
                throw error
            }
        }
    }
}
