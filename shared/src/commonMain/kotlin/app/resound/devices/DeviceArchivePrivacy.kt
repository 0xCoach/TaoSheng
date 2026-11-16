package app.resound.devices

import kotlinx.serialization.Serializable

/** A privacy request erases whole original files when any audio overlaps the inclusive range. */
fun deviceArchiveOverlaps(start: Long?, duration: Long, from: Long, until: Long): Boolean {
    require(from <= until)
    if (start == null) return true // Unknown dates must not silently retain possibly private audio.
    val length = duration.coerceAtLeast(0)
    val end = if (start > Long.MAX_VALUE - length) Long.MAX_VALUE else start + length
    return start <= until && (end > from || length == 0L && start >= from)
}

/** Keep a minimal local fence so queued downloads cannot recreate erased archives after restart. */
@Serializable
data class DeviceArchiveErasure(val from: Long, val until: Long, val archiveIds: Set<String> = emptySet()) {
    fun excludes(id: String?, start: Long?, duration: Long): Boolean =
        (id != null && id in archiveIds) || (from <= until && deviceArchiveOverlaps(start,duration,from,until))
}
