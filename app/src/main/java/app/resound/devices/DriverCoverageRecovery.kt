package app.resound.devices

import app.resound.data.CaptureDatabase
import app.resound.devices.driver.DriverStore
import app.resound.devices.driver.MillisRange
import app.resound.devices.core.coveredFromSavedAudio
import kotlinx.coroutines.flow.first
import java.io.File

internal suspend fun seedLiveCoverage(db: CaptureDatabase, device: String, file: DeviceFile, ranges: List<MillisRange>) {
    val duration = file.durationMillis ?: return
    val start = file.startedAt ?: return
    for (range in ranges) {
        val from = range.fromMillis.coerceIn(0,duration)
        val to = range.toMillis.coerceIn(0,duration)
        if (from >= to) continue
        val key = deviceTransferKey(device,file,from,to)
        db.deviceSync().save(DeviceTransfer(key,device,file.id,file.name,file.bytes,file.startedAt,
            file.durationMillis,file.format.name,from,to,start+from,imported=true))
    }
}

/** 进程中断时从已经落盘的人声片段和 spool 恢复覆盖。未知时钟偏差只使用能证明的交集。 */
internal suspend fun recoverDriverCoverage(db: CaptureDatabase, spool: File, file: DeviceFile,
    store: DriverStore): List<MillisRange> {
    val duration = file.durationMillis ?: return emptyList()
    val ranges = mutableListOf<MillisRange>()
    for (key in store.keys().filter { it.startsWith("audioAnchor:") }) {
        val fields = store.getString(key)?.split('|', limit = 4) ?: continue
        if (fields.size != 4 || fields[3] != file.id) continue
        val offset = fields[0].toLongOrNull() ?: continue
        val lower = fields[1].toLongOrNull() ?: continue
        val upper = fields[2].toLongOrNull() ?: continue
        if (offset < 0 || upper < lower || upper - lower > 60_000) continue
        val sourceId = key.removePrefix("audioAnchor:")
        fun add(start: Long, end: Long) {
            val unitMillis = if (file.format == FileFormat.Opus) 20L else 1L
            coveredFromSavedAudio(duration,offset,lower,upper,start,end,unitMillis)?.let(ranges::add)
        }
        val queryStart = (lower - offset - 60_000).coerceAtLeast(0)
        val queryEnd = upper - offset + duration + 60_000
        db.capture().segments(queryStart,queryEnd).first().filter { it.sourceId == sourceId }.forEach {
            add(it.startAt,it.endAt)
        }
        spool.listFiles().orEmpty().filter { it.extension == "source" }.forEach { meta ->
            if (runCatching { meta.readText() }.getOrNull() == sourceId) {
                val start = meta.name.substringBefore('_').toLongOrNull() ?: return@forEach
                val pcm = File(meta.parentFile,meta.nameWithoutExtension+".pcm")
                val active = File(meta.parentFile,meta.nameWithoutExtension+".recording")
                val audio = if (pcm.exists()) pcm else active
                if (audio.exists()) add(start,start+audio.length()/32)
            }
        }
    }
    return ranges
}
