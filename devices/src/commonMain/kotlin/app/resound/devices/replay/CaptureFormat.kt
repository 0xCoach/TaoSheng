package app.resound.devices.replay

data class CaptureHeader(val driver: String, val recordedAt: Long, val note: String = "") {
    init {
        require(Regex("[a-z0-9:-]{1,64}").matches(driver))
        require(recordedAt >= 0 && '\n' !in note && '\r' !in note)
    }
    fun line() = "#taoecho-capture v1 driver=$driver recordedAt=$recordedAt" +
        if (note.isEmpty()) "" else " note=$note"
}

internal fun ByteArray.hex() = if (isEmpty()) "-" else
    joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

internal fun String.unhex(): ByteArray {
    if (this == "-") return byteArrayOf()
    require(isNotEmpty() && length % 2 == 0 && all { it in "0123456789abcdefABCDEF" }) { "非法十六进制字节" }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

internal data class CaptureRecord(
    val line: Int, val at: Long, val kind: Char, val service: String = "-",
    val characteristic: String = "-", val bytes: ByteArray = byteArrayOf(),
    val limit: Int = 20, val mode: Char? = null,
) {
    fun describe() = "$kind $service $characteristic ${bytes.hex()}"
}

internal class CaptureText(text: String) {
    val header: CaptureHeader
    val records: List<CaptureRecord>
    val stopped: Boolean
    init {
        val lines = text.lineSequence().toList()
        stopped = lines.any { it.startsWith("# capture-stopped ") }
        val match = Regex("#taoecho-capture v1 driver=([a-z0-9:-]{1,64}) recordedAt=([0-9]+)(?: note=(.*))?")
            .matchEntire(lines.firstOrNull().orEmpty())
        require(match != null) { "录包第 1 行：非法文件头或版本" }
        header = CaptureHeader(match.groupValues[1], match.groupValues[2].toLong(), match.groupValues[3])
        var previous = 0L
        records = lines.drop(1).mapIndexedNotNull { index, raw ->
            val number = index + 2
            if (raw.isBlank() || raw.startsWith('#')) return@mapIndexedNotNull null
            try {
                val parts = raw.split(' ')
                val record = if (parts[0] == "M") {
                    require(parts.size in 3..4 && parts[2].startsWith("mtu="))
                    val mode = parts.getOrNull(3)?.let { require(it in setOf("mode=W", "mode=w")); it.last() }
                    CaptureRecord(number, parts[1].toLong(), 'M', limit = parts[2].removePrefix("mtu=").toInt(), mode = mode)
                        .also { require(it.limit > 0) }
                } else {
                    require(parts.size == 5 && parts[1].length == 1 && parts[1][0] in "RWwNDE")
                    CaptureRecord(number, parts[0].toLong(), parts[1][0], parts[2], parts[3], parts[4].unhex()).also {
                        if (it.kind == 'D') require(parts.drop(2).all { part -> part == "-" })
                        else if (it.kind != 'E') require(parts[2] != "-" && parts[3] != "-" && parts[2].isNotBlank() && parts[3].isNotBlank())
                        if (it.kind == 'E') {
                            val error = it.bytes.decodeToString(throwOnInvalidSequence = true).split('|', limit = 3)
                            require(error.size == 3 && error[0] in listOf("R", "W", "w", "N", "M"))
                        }
                    }
                }
                require(record.at >= previous) { "相对时间必须非负且不倒退" }
                previous = record.at
                record
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("录包第 $number 行：${e.message ?: "格式错误"}；$raw", e)
            }
        }
    }
}

/** 单位为 UTF-8 字节，预留终止说明；append 由平台串行调用并负责文件生命周期。 */
class LimitedCaptureSink(private val append: (ByteArray) -> Unit, private val maxBytes: Long = 20L * 1024 * 1024) {
    private val footer = "# capture-stopped size-limit\n".encodeToByteArray()
    private var size = 0L
    var stopped = false
        private set
    init { require(maxBytes >= footer.size) }
    fun line(value: String) {
        if (stopped) return
        val bytes = (value + "\n").encodeToByteArray()
        if (size + bytes.size + footer.size > maxBytes) {
            append(footer); size += footer.size; stopped = true
        } else { append(bytes); size += bytes.size }
    }
}
