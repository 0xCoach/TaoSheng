package app.resound.update

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.DigestOutputStream
import java.security.MessageDigest

/** 标准 BSDIFF40：三个有界 bzip2 流，旧包随机读，新包按 64 KiB 写；内存不随包大小增长。 */
internal object Bspatch {
    fun apply(old: File, patch: File, output: File, expectedSize: Long): String {
        try {
            val header = ByteArray(32)
            RandomAccessFile(patch, "r").use { it.readFully(header) }
            require(String(header, 0, 8, Charsets.US_ASCII) == "BSDIFF40")
            val controlSize = number(header, 8)
            val diffSize = number(header, 16)
            val size = number(header, 24)
            require(controlSize > 0 && diffSize > 0 && size == expectedSize && size >= 0)
            val diffAt = Math.addExact(32L, controlSize)
            val extraAt = Math.addExact(diffAt, diffSize)
            require(extraAt < patch.length())
            val digest = MessageDigest.getInstance("SHA-256")
            compressed(patch, 32, controlSize).use { control ->
                compressed(patch, diffAt, diffSize).use { diff ->
                    compressed(patch, extraAt, patch.length() - extraAt).use { extra ->
                        RandomAccessFile(old, "r").use { source ->
                            FileOutputStream(output).use { file ->
                                val target = DigestOutputStream(file, digest)
                                val buffer = ByteArray(64 * 1024)
                                val oldBuffer = ByteArray(buffer.size)
                                val word = ByteArray(8)
                                var oldAt = 0L
                                var newAt = 0L
                                var records = 0L
                                while (newAt < size) {
                                    require(++records <= size + 1)
                                    fun next(): Long { control.fully(word, 8); return number(word, 0) }
                                    val add = next(); val copy = next(); val seek = next()
                                    require(add >= 0 && copy >= 0 && add <= size - newAt && copy <= size - newAt - add)
                                    var left = add
                                    while (left > 0) {
                                        val count = minOf(left, buffer.size.toLong()).toInt()
                                        diff.fully(buffer, count)
                                        val end = Math.addExact(oldAt, count.toLong())
                                        val startInOld = maxOf(0L, oldAt)
                                        val endInOld = minOf(source.length(), end)
                                        if (endInOld > startInOld) {
                                            val offset = (startInOld - oldAt).toInt()
                                            val length = (endInOld - startInOld).toInt()
                                            source.seek(startInOld); source.readFully(oldBuffer, 0, length)
                                            repeat(length) { buffer[offset + it] = (buffer[offset + it] + oldBuffer[it]).toByte() }
                                        }
                                        target.write(buffer, 0, count)
                                        oldAt = end; newAt += count; left -= count
                                    }
                                    left = copy
                                    while (left > 0) {
                                        val count = minOf(left, buffer.size.toLong()).toInt()
                                        extra.fully(buffer, count); target.write(buffer, 0, count)
                                        newAt += count; left -= count
                                    }
                                    oldAt = Math.addExact(oldAt, seek)
                                }
                                // 读到流尾也使 bzip2 CRC 校验执行，拒绝截断和多余指令。
                                require(control.read() == -1 && diff.read() == -1 && extra.read() == -1)
                                target.flush(); file.fd.sync()
                            }
                        }
                    }
                }
            }
            return digest.digest().hex()
        } catch (e: Exception) { output.delete(); throw e }
    }

    /** bsdiff 的整数是小端符号位 + 绝对值，并非二进制补码。 */
    private fun number(bytes: ByteArray, offset: Int): Long {
        var value = (bytes[offset + 7].toInt() and 127).toLong()
        for (i in 6 downTo 0) value = (value shl 8) or (bytes[offset + i].toLong() and 255)
        return if (bytes[offset + 7].toInt() and 128 != 0) -value else value
    }
    private fun InputStream.fully(buffer: ByteArray, count: Int) {
        var at = 0
        while (at < count) {
            val read = read(buffer, at, count - at)
            if (read <= 0) throw EOFException("patch_truncated")
            at += read
        }
    }
    private fun compressed(file: File, offset: Long, size: Long): InputStream {
        val range = object : InputStream() {
            val source = RandomAccessFile(file, "r").apply { seek(offset) }
            var remaining = size
            override fun read(): Int {
                if (remaining == 0L) return -1
                return source.read().also { if (it >= 0) remaining-- }
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                if (remaining == 0L) return -1
                return source.read(b, off, minOf(remaining, len.toLong()).toInt()).also { if (it > 0) remaining -= it }
            }
            override fun close() = source.close()
        }
        // 解压时会逐字节读底层流：加一层缓冲，免得每个字节都去读一次文件
        val buffered = java.io.BufferedInputStream(range, 64 * 1024)
        return try { BZip2CompressorInputStream(buffered) } catch (e: Exception) { buffered.close(); throw e }
    }
}
