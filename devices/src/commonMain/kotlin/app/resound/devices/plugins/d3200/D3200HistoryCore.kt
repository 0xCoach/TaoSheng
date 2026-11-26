package app.resound.devices.plugins.d3200


/** All ranges are half-open device packet positions (20 ms per packet), never phone receipt times. */
internal object D3200HistoryCore {
    private const val MAX_PAGE_FILES = 10
    private const val ENTRY_BYTES = 12
    private const val PAGE_TRAILER_BYTES = 8
    private const val MAX_IMPORT_PACKETS = 15_000L

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
    fun page(payload: ByteArray): Page {
        require(payload.size >= 2) { "离线录音列表不完整" }
        val count = D3200Protocol.u16(payload, 0)
        val listEnd = 2 + count * ENTRY_BYTES
        require(count <= MAX_PAGE_FILES && payload.size == listEnd + PAGE_TRAILER_BYTES) { "离线录音列表长度异常" }
        val ids = HashSet<Long>(count)
        val entries = buildList(count) {
            for (at in 2 until listEnd step ENTRY_BYTES) {
                val id = D3200Protocol.u32(payload, at)
                require(ids.add(id)) { "离线录音列表存在重复条目" }
                add(FileEntry(id, D3200Protocol.u32(payload, at + 4), D3200Protocol.u32(payload, at + 8)))
            }
        }
        return Page(entries, D3200Protocol.u32(payload, listEnd), count)
    }
    fun packets(transportBytes: Long): Long {
        val size = D3200Protocol.TRANSPORT_PACKET_BYTES
        require(transportBytes > 0 && transportBytes != 0xffffffffL && transportBytes % size == 0L) {
            "设备未提供完整的已保存录音长度"
        }
        return transportBytes / size
    }
    fun resumeAt(count: Long) = (count - 1).coerceAtLeast(0)
    fun epoch(file: Long, now: Long): Long {
        val ms = file * 1000
        require(ms >= 1577836800000L && ms <= now + 86_400_000) { "录音豆文件时间异常，请先检查设备时钟" }
        return ms
    }
    fun merge(ranges: List<Range>): List<Range> {
        val sorted = ranges.filter { it.from < it.until }.sortedBy(Range::from)
        if (sorted.isEmpty()) return emptyList()
        var start = sorted.first().from
        var end = sorted.first().until
        return buildList {
            for (i in 1..sorted.lastIndex) {
                val range = sorted[i]
                if (range.from > end) {
                    add(Range(start, end))
                    start = range.from
                    end = range.until
                } else {
                    end = maxOf(end, range.until)
                }
            }
            add(Range(start, end))
        }
    }
    fun missing(total: Long, have: List<Range>): List<Range> {
        require(total >= 0)
        return buildList {
            var availableThrough = 0L
            for (range in have.sortedBy(Range::from)) {
                if (availableThrough == total || range.from >= total) break
                if (range.from == range.until || range.until <= availableThrough) continue
                if (availableThrough < range.from) add(Range(availableThrough, range.from))
                availableThrough = minOf(total, range.until)
            }
            if (availableThrough < total) add(Range(availableThrough, total))
        }
    }
    /** Keep queue admission and crash replay deterministic, with bounded import sizes. */
    fun pieces(missing: List<Range>): List<Range> = buildList {
        for (range in missing) {
            var start = range.from
            while (start < range.until) {
                val end = start + minOf(MAX_IMPORT_PACKETS, range.until - start)
                add(Range(start, end))
                start = end
            }
        }
    }
}
