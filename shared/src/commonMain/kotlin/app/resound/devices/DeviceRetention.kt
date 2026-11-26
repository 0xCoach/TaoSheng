package app.resound.devices

import kotlinx.serialization.Serializable

@Serializable
data class DeviceArchiveOptions(val archiveEnabled: Boolean = false,
                                val retention: DeviceRetentionSettings = DeviceRetentionSettings(cleanupDelayHours = 0),
                                val installation: String? = null)
fun DeviceArchiveOptions.forInstallation(current: String): DeviceArchiveOptions =
    if (installation == current) this else copy(archiveEnabled = false,
        retention = retention.copy(enabled = false,enabledAtMillis = null,adoptedHistory = emptySet()),installation = current)

@Serializable
data class DeviceRetentionSettings(
    val enabled: Boolean = false,
    val enabledAtMillis: Long? = null,
    val keepDays: Int = 7,
    val observationHours: Int = 24,
    val adoptedHistory: Set<String> = emptySet(),
    /** Null preserves the older recording-age + backup-observation policy when loading existing settings. */
    val cleanupDelayHours: Int? = null,
) {
    init {
        require(keepDays in setOf(3,7,14,30)); require(observationHours >= 24)
        require(cleanupDelayHours == null || cleanupDelayHours in setOf(0,24,72,168))
    }
}
@Serializable
data class DeviceArchiveBackupReceipt(
    val destination: String,
    val objectPath: String,
    val manifestPath: String,
    val archiveSha256: String,
    val archiveBytes: Long,
    val verifiedAtMillis: Long,
)
@Serializable
data class DeviceArchiveRemoteManifest(val format: Int = 1, val record: DeviceArchiveRecord,
    val encrypted: Boolean, val storedSha256: String, val storedBytes: Long)
@Serializable
enum class DeviceDeleteState { Present, Intent, Uncertain, Gone, RemovedElsewhere }
@Serializable
data class DeviceArchiveRecord(
    val id: String,
    val driver: String,
    val deviceIdentity: String,
    val fileId: String,
    val startedAtMillis: Long?,
    val durationMillis: Long,
    val firstSeenClosedAtMillis: Long,
    val sourceBytes: Long,
    val sourceSha256: String,
    val archiveBytes: Long,
    val archiveSha256: String,
    val archiveFormat: String,
    val decodedSamples: Long,
    val archivedAtMillis: Long,
    val installation: String,
    val imported: Boolean = false,
    val protected: Boolean = false,
    val backup: DeviceArchiveBackupReceipt? = null,
    val deletion: DeviceDeleteState = DeviceDeleteState.Present,
    val deletionAtMillis: Long? = null,
    val error: String? = null,
    val localDeviceHandle: String = "",
)
enum class DeviceRetentionBlock { None, Disabled, Protected, HistoricalNotAdopted, IncompleteArchive, ImportPending,
    IndependentBackupRequired, BackupChanged, WrongDevice, RecordingOrUnknown, LowBatteryOrUnknown,
    RetentionPeriod, ObservationPeriod, AlreadyGone, ResultNeedsReconciliation, DifferentInstallation }

fun deviceArchiveEnrolled(record: DeviceArchiveRecord, settings: DeviceRetentionSettings, now: Long): Boolean {
    if (settings.adoptedHistory.contains(record.id)) return true
    val enabledAt = settings.enabledAtMillis ?: return false
    val began = record.startedAtMillis ?: return false
    // 重新发现旧文件时不自动纳入清理。设备时钟允许五分钟偏差。
    return began >= enabledAt && began + record.durationMillis <= now + CLOCK_TOLERANCE_MS
}

fun deviceRetentionBlock(record: DeviceArchiveRecord, settings: DeviceRetentionSettings, now: Long,
                         currentIdentity: String, installation: String, backupDestination: String?,
                         deviceStopped: Boolean, battery: Int?, automatic: Boolean = true): DeviceRetentionBlock = when {
    automatic && !settings.enabled -> DeviceRetentionBlock.Disabled
    record.deletion == DeviceDeleteState.Gone || record.deletion == DeviceDeleteState.RemovedElsewhere -> DeviceRetentionBlock.AlreadyGone
    record.deletion != DeviceDeleteState.Present -> DeviceRetentionBlock.ResultNeedsReconciliation
    record.installation != installation -> DeviceRetentionBlock.DifferentInstallation
    record.deviceIdentity != currentIdentity -> DeviceRetentionBlock.WrongDevice
    record.protected -> DeviceRetentionBlock.Protected
    !deviceArchiveEnrolled(record, settings, now) -> DeviceRetentionBlock.HistoricalNotAdopted
    !record.hasCompleteArchive() -> DeviceRetentionBlock.IncompleteArchive
    !record.imported -> DeviceRetentionBlock.ImportPending
    else -> backupAndDeviceBlock(record, settings, now, backupDestination, deviceStopped, battery)
}

private fun DeviceArchiveRecord.hasCompleteArchive(): Boolean {
    fun validChecksum(value: String) = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
    return sourceBytes > 0 && archiveBytes > 0 && decodedSamples > 0 && validChecksum(sourceSha256) && validChecksum(archiveSha256)
}

private fun backupAndDeviceBlock(
    record: DeviceArchiveRecord,
    settings: DeviceRetentionSettings,
    now: Long,
    destination: String?,
    stopped: Boolean,
    battery: Int?,
): DeviceRetentionBlock {
    val receipt = record.backup
    if (receipt == null || receipt.archiveSha256 != record.archiveSha256 || receipt.archiveBytes != record.archiveBytes ||
        receipt.verifiedAtMillis <= 0 || receipt.manifestPath.isBlank() || receipt.objectPath.isBlank()) {
        return DeviceRetentionBlock.IndependentBackupRequired
    }
    if (receipt.destination != destination || destination == null) return DeviceRetentionBlock.BackupChanged
    if (!stopped) return DeviceRetentionBlock.RecordingOrUnknown
    if (battery == null || battery !in 30..100) return DeviceRetentionBlock.LowBatteryOrUnknown

    val end = record.startedAtMillis?.plus(record.durationMillis)
    val finished = if (end != null && end > 0 && end <= now + CLOCK_TOLERANCE_MS) end else record.firstSeenClosedAtMillis
    val delayHours = settings.cleanupDelayHours
    val age = now - finished
    if (now < finished || delayHours == null && age < settings.keepDays * DAY_MS) return DeviceRetentionBlock.RetentionPeriod
    val elapsed = now - receipt.verifiedAtMillis
    if (now < receipt.verifiedAtMillis || elapsed < (delayHours ?: settings.observationHours) * HOUR_MS) {
        return DeviceRetentionBlock.ObservationPeriod
    }
    return DeviceRetentionBlock.None
}

private const val CLOCK_TOLERANCE_MS = 300_000L
private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 86_400_000L

fun deviceImportCoverageComplete(duration: Long, ranges: List<Pair<Long,Long>>): Boolean {
    if (duration <= 0) return false
    var until = 0L
    for ((from,to) in ranges.sortedBy { it.first }) {
        if (from < 0 || to < from || from > until) return false
        until = maxOf(until,to)
        if (until >= duration) return true
    }
    return false
}
