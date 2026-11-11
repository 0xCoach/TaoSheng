package app.resound.devices.core

/** 每个编解码会话串行使用；增长缓冲避免逐片复制全部历史数据。 */
internal class ByteAccumulator {
    private var bytes = ByteArray(32)
    private var count = 0

    fun size(): Int = count

    fun write(value: ByteArray) {
        require(value.size <= Int.MAX_VALUE - count) { "字节缓存超限" }
        val required = count + value.size
        if (required > bytes.size) {
            val doubled = (bytes.size.toLong() * 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            bytes = bytes.copyOf(maxOf(required, doubled))
        }
        value.copyInto(bytes, count)
        count = required
    }

    fun toByteArray(): ByteArray = bytes.copyOf(count)
}
