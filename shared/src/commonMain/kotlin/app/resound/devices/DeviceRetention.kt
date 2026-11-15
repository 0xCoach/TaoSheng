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

fun deviceArchiveEnrolled(record: DeviceArchiveRecord, settings: DeviceRetentionSettings, now: Long): Boolean =
    record.id in settings.adoptedHistory || (settings.enabledAtMillis?.let { since ->
        record.startedAtMillis?.let { start -> start >= since && start+record.durationMillis <= now+300_000 } == true
    } == true)

fun deviceRetentionBlock(record: DeviceArchiveRecord, settings: DeviceRetentionSettings, now: Long,
                         currentIdentity: String, installation: String, backupDestination: String?,
                         deviceStopped: Boolean, battery: Int?, automatic: Boolean = true): DeviceRetentionBlock {
    if (automatic && !settings.enabled) return DeviceRetentionBlock.Disabled
    if (record.deletion in setOf(DeviceDeleteState.Gone,DeviceDeleteState.RemovedElsewhere)) return DeviceRetentionBlock.AlreadyGone
    if (record.deletion != DeviceDeleteState.Present) return DeviceRetentionBlock.ResultNeedsReconciliation
    if (record.installation != installation) return DeviceRetentionBlock.DifferentInstallation
    if (record.deviceIdentity != currentIdentity) return DeviceRetentionBlock.WrongDevice
    if (record.protected) return DeviceRetentionBlock.Protected
    val recordedEnd = record.startedAtMillis?.plus(record.durationMillis)
    // First discovery of an old file after enabling must not opt that old file in.
    if (!deviceArchiveEnrolled(record,settings,now))
        return DeviceRetentionBlock.HistoricalNotAdopted
    fun hash(value: String) = value.length == 64 && value.all { it in "0123456789abcdef" }
    if (record.sourceBytes <= 0 || record.archiveBytes <= 0 || record.decodedSamples <= 0 ||
        !hash(record.sourceSha256) || !hash(record.archiveSha256)) return DeviceRetentionBlock.IncompleteArchive
    if (!record.imported) return DeviceRetentionBlock.ImportPending
    val backup = record.backup ?: return DeviceRetentionBlock.IndependentBackupRequired
    if (backup.archiveSha256 != record.archiveSha256 || backup.archiveBytes != record.archiveBytes ||
        backup.verifiedAtMillis <= 0 || backup.manifestPath.isBlank() || backup.objectPath.isBlank())
        return DeviceRetentionBlock.IndependentBackupRequired
    if (backupDestination == null || backup.destination != backupDestination) return DeviceRetentionBlock.BackupChanged
    if (!deviceStopped) return DeviceRetentionBlock.RecordingOrUnknown
    if (battery == null || battery !in 30..100) return DeviceRetentionBlock.LowBatteryOrUnknown
    val closed = recordedEnd?.takeIf { it > 0 && it <= now + 300_000 } ?: record.firstSeenClosedAtMillis
    if (now < closed) return DeviceRetentionBlock.RetentionPeriod
    val delay = settings.cleanupDelayHours
    if (delay == null && now - closed < settings.keepDays * 86_400_000L) return DeviceRetentionBlock.RetentionPeriod
    if (now < backup.verifiedAtMillis || now - backup.verifiedAtMillis < (delay ?: settings.observationHours) * 3_600_000L)
        return DeviceRetentionBlock.ObservationPeriod
    return DeviceRetentionBlock.None
}

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
