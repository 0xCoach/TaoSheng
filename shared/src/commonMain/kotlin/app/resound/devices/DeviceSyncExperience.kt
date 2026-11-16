package app.resound.devices

enum class DeviceSyncPhase { Waiting, Listing, Downloading, Verifying, Importing, Completed, Paused, Failed }
data class DeviceSyncTask(val key: String, val source: String, val bytes: Long?, val audioMillis: Long)
data class DeviceSyncDetail(
    val phase: DeviceSyncPhase,
    val currentName: String? = null,
    val currentBytes: Long = 0,
    val currentTotal: Long? = null,
    val tasks: Int = 0,
    val doneTasks: Int = 0,
    val sourceFiles: Int = 0,
    val receivedBytes: Long = 0,
    val totalBytes: Long? = null,
    val remainingAudioMillis: Long = 0,
    val downloadEtaMillis: Long? = null,
    val fromCache: Boolean = false,
)

/** Counts physical source bytes once while keeping per-range import tasks independent. */
class DeviceSyncMeter(private val tasks: List<DeviceSyncTask>) {
    private val sources = tasks.associateBy { it.source }
    private val received = mutableMapOf<String, Long>()
    private val done = mutableSetOf<String>()
    private var downloadStartedAt: Long? = null
    private var downloadElapsed = 0L
    private var networkBytes = 0L
    private var current: DeviceSyncTask? = null
    private var currentName: String? = null
    var detail = DeviceSyncDetail(DeviceSyncPhase.Listing)
        private set

    fun begin(task: String, name: String, durableBytes: Long, now: Long): DeviceSyncDetail {
        current = tasks.first { it.key == task }; currentName = name
        val row = requireNotNull(current)
        received[row.source] = durableBytes.coerceIn(0, row.bytes ?: Long.MAX_VALUE)
        return snapshot(DeviceSyncPhase.Downloading, now, fromCache = row.bytes != null && durableBytes == row.bytes)
    }
    fun bytes(value: Long, now: Long): DeviceSyncDetail {
        val row = requireNotNull(current); val actual = value.coerceIn(0, row.bytes ?: Long.MAX_VALUE)
        networkBytes += (actual - (received[row.source] ?: 0)).coerceAtLeast(0)
        received[row.source] = actual
        return snapshot(DeviceSyncPhase.Downloading, now)
    }
    fun phase(phase: DeviceSyncPhase, now: Long, fromCache: Boolean = false) = snapshot(phase, now, fromCache)
    fun completed(task: String, now: Long): DeviceSyncDetail {
        done += task
        return snapshot(DeviceSyncPhase.Importing, now)
    }
    private fun snapshot(phase: DeviceSyncPhase, now: Long, fromCache: Boolean = false): DeviceSyncDetail {
        val total = sources.values.takeIf { rows -> rows.all { it.bytes != null } }?.sumOf { it.bytes!! }
        val held = received.values.sum()
        if (phase == DeviceSyncPhase.Downloading && !fromCache) {
            if (downloadStartedAt == null) downloadStartedAt = now
        } else downloadStartedAt?.let { since ->
            downloadElapsed += (now-since).coerceAtLeast(0);downloadStartedAt = null
        }
        val elapsed = downloadElapsed + downloadStartedAt?.let { (now-it).coerceAtLeast(0) }.orZero()
        val remaining = total?.minus(held)?.coerceAtLeast(0)
        val eta = if (phase == DeviceSyncPhase.Downloading && elapsed >= 2_000 && networkBytes >= 32_768 && remaining != null)
            (remaining.toDouble() * elapsed / networkBytes).toLong() else null
        return DeviceSyncDetail(phase, currentName, current?.let { received[it.source] } ?: 0, current?.bytes,
            tasks.size, done.size, sources.size, held, total,
            tasks.filter { it.key !in done }.sumOf { it.audioMillis.coerceAtLeast(0) }, eta, fromCache).also { detail = it }
    }
    private fun Long?.orZero() = this ?: 0L
}
