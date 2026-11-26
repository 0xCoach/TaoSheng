package app.resound.devices.plugins.d3200

/** Cursor for the upstream queue's raw decrypted-byte offset. Re-read one packet at an EOF boundary. */
internal class D3200DownloadCursor(val fromOffset: Long, val totalPackets: Long) {
    private val firstRequired = fromOffset / D3200Protocol.AUDIO_PACKET_BYTES
    init {
        require(fromOffset >= 0 && fromOffset % D3200Protocol.AUDIO_PACKET_BYTES == 0L &&
            firstRequired <= totalPackets && totalPackets > 0)
    }
    val requested = D3200HistoryCore.resumeAt(firstRequired)
    private var next = requested
    val complete get() = next == totalPackets
    fun accept(sequence: Long, plain: ByteArray): ByteArray? {
        if (sequence < next) return null
        require(sequence == next && sequence < totalPackets) { "补传缺包，将从保存位置重试" }
        D3200Ogg.packetSamples(plain)
        next++
        return plain.takeIf { sequence >= firstRequired }
    }
}

/** BLE 与 Wi-Fi 上的离线录音都以完整 Opus 包为单位续传。 */
internal class D3200FileDownload(
    header: ByteArray,
    file: Long,
    offset: Long,
    private val crypto: D3200Crypto,
    private val emitBytes: suspend (ByteArray) -> Unit,
) {
    private val cursor: D3200DownloadCursor
    private val chunk = ByteArray(50 * D3200Protocol.AUDIO_PACKET_BYTES)
    private var used = 0
    val complete: Boolean get() = cursor.complete

    init {
        crypto.fileHeader(header, file)
        cursor = D3200DownloadCursor(offset, D3200HistoryCore.packets(D3200Protocol.u32(header, 4)))
    }

    suspend fun audio(frame: ByteArray): Boolean {
        val slices = D3200Protocol.slices(D3200Protocol.payload(frame))
        for (slice in slices) {
            val packet = cursor.accept(slice.seq, crypto.decrypt(slice)) ?: continue
            packet.copyInto(chunk, used)
            used += packet.size
            if (used == chunk.size) finishChunk()
        }
        return slices.isNotEmpty()
    }

    suspend fun finishChunk() {
        if (used == 0) return
        emitBytes(chunk.copyOf(used))
        used = 0
    }
}
