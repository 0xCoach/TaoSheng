package app.resound.devices.plugins.d3200

import app.resound.devices.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** Protocol only. The upstream DeviceSync owns phone files, persistence, retries and ingestion. */
internal class D3200Files : FileSourcePlugin, DeviceFileDetails {
    override val id = "d3200"
    override val caps = FileCaps(resume = true, delete = false, checksum = false)
    private val sizes = mutableMapOf<String, Long>()
    override suspend fun list(link: DeviceLink): List<DeviceFile> {
        val connection = link as D3200Connection
        require(connection.refreshRecording() == 0) { "请先按设备键停止并保存录音，再补传" }
        val all = linkedMapOf<Long, D3200HistoryCore.FileEntry>()
        val open = mutableSetOf<Long>(); var page = 0
        while (true) {
            val listing = D3200HistoryCore.page(connection.ask(D3200Protocol.listPage(page), 0x1b0e))
            if (listing.current != 0L) open += listing.current
            for (entry in listing.files) require(all.put(entry.id, entry) == null) { "设备重复返回同一页" }
            if (listing.count < 10) break
            require(++page <= 65535)
        }
        return all.values.filter { it.id !in open && it.durationMillis > 0 }.sortedByDescending { it.id }.map {
            DeviceFile(it.id.toString(), "录音豆 ${java.time.Instant.ofEpochSecond(it.id).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()}",
                it.durationMillis / 20 * 160, D3200HistoryCore.epoch(it.id, System.currentTimeMillis()), it.durationMillis, FileFormat.Opus)
        }
    }
    override fun download(link: DeviceLink, file: DeviceFile, fromOffset: Long): Flow<ByteArray> = flow {
        val connection = link as D3200Connection
        require(fromOffset >= 0 && fromOffset % 160 == 0L) { "补传进度未对齐完整音频包" }
        require(connection.refreshRecording() == 0) { "补传期间请保持设备停止录音" }
        val fileId = file.id.toLong(); val from = fromOffset / 160
        val request = D3200HistoryCore.resumeAt(from)
        var head = connection.ask(D3200Protocol.offlineRequest(fileId, request), 0x1a07, fileId)
        var busy = 0
        while (head.size >= 87 && head[86].toInt() and 255 == 4 && busy++ < 3) {
            delay(1000); head = connection.ask(D3200Protocol.offlineRequest(fileId, request), 0x1a07, fileId)
        }
        require(head.size >= 87 && head[86].toInt() and 255 == 0) { "设备未允许读取保存文件" }
        connection.crypto.fileHeader(head, fileId)
        val total = D3200HistoryCore.packets(D3200Protocol.u32(head, 4))
        require(from <= total) { "本地进度超过设备文件长度" }
        sizes[file.id] = total * 160
        val cursor = D3200DownloadCursor(fromOffset, total)
        var progressAt = SystemClock.elapsedRealtime()
        val batch = ByteArrayOutputStream(8000)
        while (!cursor.complete) {
            currentCoroutineContext().ensureActive()
            if (SystemClock.elapsedRealtime() - progressAt > 45_000) throw IOException("离线补传无进展，已保留续传位置")
            val frame = connection.receive() ?: continue
            when (D3200Protocol.command(frame)) {
                0x1a08, 0x1a12 -> for (slice in D3200Protocol.slices(D3200Protocol.payload(frame))) {
                    val packet = connection.crypto.decrypt(slice)
                    cursor.accept(slice.seq, packet)?.let(batch::write)
                    progressAt = SystemClock.elapsedRealtime()
                    if (batch.size() >= 8000) { emit(batch.toByteArray()); batch.reset() }
                }
                0x1a0a -> throw IOException("设备提前结束补传，进度已保留")
                0x1a07 -> throw IOException("补传期间文件头发生变化")
            }
        }
        if (batch.size() > 0) emit(batch.toByteArray())
        delay(500)
    }
    override fun validSize(file: DeviceFile, local: File) = sizes[file.id]?.let { it == local.length() } ?: false
    override fun modifiedAt(file: DeviceFile): Long? = file.startedAt?.plus(file.durationMillis ?: 0)
}
