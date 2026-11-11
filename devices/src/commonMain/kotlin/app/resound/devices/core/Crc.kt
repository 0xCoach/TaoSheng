package app.resound.devices.core

import app.resound.devices.CrcAlgo

/** 无平台依赖；返回无符号值（用 Long 承载 CRC32）。 */
object Crc {
    fun compute(algo: CrcAlgo, bytes: ByteArray): Long {
        var crc = when (algo) { CrcAlgo.Crc16Xmodem -> 0L; CrcAlgo.Crc16Modbus -> 0xffffL; CrcAlgo.Crc32 -> 0xffffffffL }
        for (byte in bytes) {
            val unsigned = byte.toLong() and 255
            if (algo == CrcAlgo.Crc16Xmodem) {
                crc = crc xor (unsigned shl 8)
                repeat(8) { crc = if (crc and 0x8000 != 0L) (crc shl 1) xor 0x1021 else crc shl 1 }
                crc = crc and 0xffff
            } else {
                crc = crc xor unsigned
                val polynomial = if (algo == CrcAlgo.Crc32) 0xedb88320L else 0xa001L
                repeat(8) { crc = if (crc and 1 != 0L) (crc ushr 1) xor polynomial else crc ushr 1 }
            }
        }
        return if (algo == CrcAlgo.Crc32) crc xor 0xffffffffL else crc
    }
}
