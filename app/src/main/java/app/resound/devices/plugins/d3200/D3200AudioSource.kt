package app.resound.devices.plugins.d3200

import app.resound.audio.*
import app.resound.devices.AudioTimeline
import app.resound.devices.ManagedDevices
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import android.os.SystemClock
import java.io.IOException

internal class D3200AudioSource(
    private val connection: D3200Connection,
    override val displayName: String,
    private val ready: suspend (Long?) -> Unit,
) : AudioSource, AudioTimeline {
    override val id = ManagedDevices.deviceKey(connection.address)
    @Volatile private var file = 0L
    @Volatile private var first = -1L
    private var emitted = 0L
    private var acceptedStart: Long? = null
    private var acceptedEnd = 0L
    override val epochMillis get() = if (first >= 0) file * 1000 + first * 20 + emitted * 1000 / 16000 else null
    override val archiveSourceId get() = if (file != 0L) "$id:$file" else null
    @Synchronized override fun processed(at: Long?, samples: Int) {
        if (at == null) return
        if (acceptedStart == null) acceptedStart = at
        acceptedEnd = at + samples * 1000 / 16000
    }
    @Synchronized override fun finishCapture() {
        val start = acceptedStart ?: return
        val from = ((start - file * 1000) + 19) / 20
        val until = (acceptedEnd - file * 1000) / 20
        if (until > from) connection.settings.journal(connection.address).cover(file, D3200HistoryCore.Range(from, until))
        acceptedStart = null
    }
    override fun events(): Flow<AudioEvent> = flow {
        // ready() is awaited by ManagedDevices on this firmware; DeviceSync finishes before auto-start.
        for (step in D3200HistoryCore.startup(connection.recording, sync = true, connection.settings.shouldAutoStart(connection.address))) {
            when (step) {
                D3200HistoryCore.Startup.SyncSaved, D3200HistoryCore.Startup.WaitForSave -> ready(0L)
                D3200HistoryCore.Startup.StartRecording -> connection.startRecording()
            }
        }
        var listing = connection.listCurrent()
        file = D3200Protocol.currentFile(listing)
        while (file == 0L || connection.recording != 1) {
            connection.settings.report(connection.address) { it.copy(sync = "等待设备开始录音；补传要求先停止并保存文件") }
            emit(AudioEvent.Busy("等待录音豆开始新录音")); delay(1000)
            connection.refreshRecording()
            listing = connection.listCurrent(); file = D3200Protocol.currentFile(listing)
        }
        D3200HistoryCore.epoch(file, System.currentTimeMillis())
        val offset = liveOffset(listing, file)
        connection.requestLive(file, offset)
        val sequence = D3200Sequence(); var head = false
        var lastPcm = SystemClock.elapsedRealtime(); var anchor: Long? = null
        D3200Opus().use { decoder ->
            emit(AudioEvent.Available)
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!connection.controlling && connection.recording != 1) throw IOException("设备录音已停止或暂停，重新连接检查保存文件")
                if (SystemClock.elapsedRealtime() - lastPcm > 12000) throw IOException("录音豆未继续提供可解码音频")
                val frame = connection.receive() ?: continue
                val payload = D3200Protocol.payload(frame)
                when (D3200Protocol.command(frame)) {
                    0x1a07 -> { connection.crypto.fileHeader(payload, file); head = true }
                    0x1a08, 0x1a12 -> {
                        require(head) { "尚未取得当前录音的密钥" }
                        for (slice in D3200Protocol.slices(payload)) {
                            require(slice.seq >= offset / 166) { "拒绝将历史积压标为实时声音" }
                            if (!sequence.accept(slice.seq)) continue
                            if (first < 0) first = slice.seq
                            val now = SystemClock.elapsedRealtime()
                            if (anchor == null) anchor = now - (slice.seq - first) * 20
                            require(now - requireNotNull(anchor) - (slice.seq - first) * 20 < 4000) { "实时声音延迟过大，需要追到尾部" }
                            for (pcm in decoder.decode(connection.crypto.decrypt(slice))) {
                                connection.receiving()
                                emit(AudioEvent.Frame(pcm)); emitted += pcm.size; lastPcm = SystemClock.elapsedRealtime()
                            }
                        }
                    }
                    0x1a0a -> throw IOException("设备录音已保存，重新连接后补齐离线内容")
                    0x1a06 -> if (payload.size >= 4 && D3200Protocol.u32(payload, 0) != file) throw IOException("设备已切换录音文件")
                }
            }
        }
    }
    companion object {
        fun liveOffset(p: ByteArray, file: Long): Long {
            val count = D3200Protocol.u16(p, 0); val at = 2 + count * 8
            require(at + 8 <= p.size) { "设备未提供实时尾部时长" }
            var ms = D3200Protocol.u32(p, at + 4)
            for (i in 0 until count) if (D3200Protocol.u32(p, 2 + i * 8) == file) ms = maxOf(ms, D3200Protocol.u32(p, 6 + i * 8))
            require(ms <= 7 * 24 * 3600 * 1000L)
            return (ms / 20 - 50).coerceAtLeast(0) * 166
        }
    }
}
