package app.resound.devices.plugins.d3200


/** All ranges are half-open device packet positions (20 ms per packet), never phone receipt times. */
internal object D3200HistoryCore {
    enum class Startup { SyncSaved, WaitForSave, StartRecording }
    fun startup(recording: Int, sync: Boolean, autoStart: Boolean): List<Startup> = buildList {
        if (sync) add(if (recording == 0) Startup.SyncSaved else Startup.WaitForSave)
        if (recording != 1 && autoStart) add(Startup.StartRecording)
    }
    data class FileEntry(val id: Long, val endSeconds: Long, val durationMillis: Long)
    data class Page(val files: List<FileEntry>, val current: Long, val count: Int)
    data class Range(val from: Long, val until: Long) {
        init { require(from >= 0 && until >= from) }
    }
    fun page(p: ByteArray): Page {
        require(p.size >= 2) { "离线录音列表不完整" }
        val count = D3200Protocol.u16(p, 0)
        require(count <= 10 && p.size == 2 + count * 12 + 8) { "离线录音列表长度异常" }
        val files = (0 until count).map { i ->
            val at = 2 + i * 12
            FileEntry(D3200Protocol.u32(p, at), D3200Protocol.u32(p, at + 4), D3200Protocol.u32(p, at + 8))
        }
        require(files.map { it.id }.distinct().size == files.size) { "离线录音列表存在重复条目" }
        return Page(files, D3200Protocol.u32(p, 2 + count * 12), count)
    }
    fun packets(transportBytes: Long): Long {
        require(transportBytes > 0 && transportBytes != 0xffffffffL && transportBytes % 166 == 0L) {
            "设备未提供完整的已保存录音长度"
        }
        return transportBytes / 166
    }
    fun resumeAt(count: Long) = (count - 1).coerceAtLeast(0)
    fun epoch(file: Long, now: Long): Long {
        val ms = file * 1000
        require(ms >= 1577836800000L && ms <= now + 86_400_000) { "录音豆文件时间异常，请先检查设备时钟" }
        return ms
    }
    fun merge(ranges: List<Range>): List<Range> {
        val result = mutableListOf<Range>()
        for (r in ranges.filter { it.until > it.from }.sortedBy { it.from }) {
            val last = result.lastOrNull()
            if (last != null && last.until >= r.from) result[result.lastIndex] = Range(last.from, maxOf(last.until, r.until))
            else result += r
        }
        return result
    }
    fun missing(total: Long, have: List<Range>): List<Range> {
        require(total >= 0)
        var at = 0L
        val out = mutableListOf<Range>()
        for (r in merge(have)) {
            if (r.from >= total) break
            if (r.from > at) out += Range(at, r.from)
            at = maxOf(at, r.until).coerceAtMost(total)
        }
        if (at < total) out += Range(at, total)
        return out
    }
    /** Keep queue admission and crash replay deterministic, with bounded import sizes. */
    fun pieces(missing: List<Range>): List<Range> = missing.flatMap { r ->
        val out = mutableListOf<Range>(); var at = r.from
        while (at < r.until) { val end = minOf(at + 15_000, r.until); out += Range(at, end); at = end }
        out
    }
}
