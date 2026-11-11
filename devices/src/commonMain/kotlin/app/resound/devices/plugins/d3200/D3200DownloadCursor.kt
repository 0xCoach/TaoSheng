package app.resound.devices.plugins.d3200

/** Cursor for the upstream queue's raw decrypted-byte offset. Re-read one packet at an EOF boundary. */
internal class D3200DownloadCursor(val fromOffset: Long, val totalPackets: Long) {
    init { require(fromOffset >= 0 && fromOffset % 160 == 0L && fromOffset / 160 <= totalPackets && totalPackets > 0) }
    val requested = D3200HistoryCore.resumeAt(fromOffset / 160)
    private var next = requested
    val complete get() = next == totalPackets
    fun accept(sequence: Long, plain: ByteArray): ByteArray? {
        if (sequence < next) return null
        require(sequence == next && sequence < totalPackets) { "补传缺包，将从保存位置重试" }
        D3200Ogg.packetSamples(plain)
        next++
        return plain.takeIf { sequence >= fromOffset / 160 }
    }
}
