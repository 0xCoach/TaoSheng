package app.resound.importer

import android.content.Context
import app.resound.data.CaptureDatabase
import app.resound.devices.*
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile

/** 卡 119 将返回的函数传给 DeviceSync。完成指的是解码切段成功入库，而非仅入队。 */
internal fun deviceImport(context: Context, db: CaptureDatabase, importer: AndroidAudioImporter): suspend (File, DeviceFile, DeviceSlice, String) -> Unit =
    { source, file, slice, key ->
        val id = java.util.UUID.nameUUIDFromBytes(key.toByteArray()).toString()
        val old = db.imports().byPending(id)
        if (old?.ingestComplete != true || old.error != null) {
            if (importer.entry(id) == null) {
                val converted = File(context.filesDir, "device-sync/convert-$key.wav")
                converted.parentFile!!.mkdirs()
                try {
                    // 列表时长可能只有整秒；全量导入必须保留实际音频尾部。
                    val actualDuration = if (file.format == FileFormat.Pcm16) source.length() / 32 else ImportMedia(context).inspect(source).duration
                    val effective = if (slice.from == 0L && slice.to == file.durationMillis) slice.copy(to = actualDuration) else slice
                    deviceWav(source, converted, file.format, effective)
                    if (old != null) db.imports().update(old.copy(error = null, ingestComplete = false))
                    importer.receiveDevice(converted, id, file.name, effective.start, effective.to - effective.from, "device:$key")
                } finally { converted.delete() }
            }
            while (true) {
                currentCoroutineContext().ensureActive()
                val record = db.imports().byPending(id)
                if (record?.ingestComplete == true) {
                    check(record.error == null) { "设备录音导入失败" }; break
                }
                delay(250)
            }
        }
        if (slice.from == 0L && slice.to == file.durationMillis)
            db.folderImports().remember(ImportFingerprint(audioFingerprint(source), System.currentTimeMillis()))
    }

/** 所有封装格式复用系统解码；裸 PCM16 约定为 16kHz 单声道小端。 */
internal suspend fun deviceWav(source: File, target: File, format: FileFormat, slice: DeviceSlice) {
    RandomAccessFile(target, "rw").use { out ->
        out.setLength(0); out.write(ByteArray(44))
        var index = 0L
        suspend fun consume(samples: ShortArray) {
            currentCoroutineContext().ensureActive()
            val first = maxOf(0L, slice.from * 16 - index).coerceAtMost(samples.size.toLong()).toInt()
            val last = maxOf(0L, slice.to * 16 - index).coerceAtMost(samples.size.toLong()).toInt()
            if (last > first) {
                val bytes = ByteArray((last - first) * 2)
                for (i in first until last) {
                    bytes[(i - first) * 2] = samples[i].toByte()
                    bytes[(i - first) * 2 + 1] = (samples[i].toInt() shr 8).toByte()
                }
                out.write(bytes)
            }
            index += samples.size
        }
        if (format == FileFormat.Pcm16) {
            require(source.length() % 2 == 0L)
            source.inputStream().buffered().use { input ->
                val bytes = ByteArray(8192)
                while (true) {
                    var n = 0
                    while (n < bytes.size) { val read = input.read(bytes, n, bytes.size - n); if (read < 0) break; n += read }
                    if (n == 0) break
                    consume(ShortArray(n / 2) { ((bytes[it * 2].toInt() and 255) or (bytes[it * 2 + 1].toInt() shl 8)).toShort() })
                }
            }
        } else ImportDecoder().decode(source, consume = ::consume)
        require(index >= slice.to * 16) { "音频短于声明时长" }
        val size = out.length() - 44
        require(size in 1..0xffffffffL - 36)
        out.seek(0)
        fun le(value: Long, count: Int) { repeat(count) { out.write((value shr (it * 8)).toInt() and 255) } }
        out.writeBytes("RIFF"); le(size + 36, 4); out.writeBytes("WAVEfmt "); le(16, 4)
        le(1, 2); le(1, 2); le(16000, 4); le(32000, 4); le(2, 2); le(16, 2)
        out.writeBytes("data"); le(size, 4); out.fd.sync()
    }
}
