package app.resound.devices.android

import app.resound.devices.*
import app.resound.devices.plugins.d3200.D3200Driver
import app.resound.devices.plugins.d3200.D3200Ogg
import java.io.File
import java.io.RandomAccessFile

/** 唯一品牌装配目录；实际 MTU 由 GATT 协商并反馈给驱动。 */
internal object DriverCatalog {
    fun registered(): List<RegisteredDriver> {
        val driver = D3200Driver()
        val transport = DeviceCard(driver.meta.id, driver.meta.name.zh, 1, "0.2.22", CardStatus.Trial, Transport.Ble,
            CardMatch(), link = LinkParams(mtu = 517), offline = OfflineMode.Files, filePlugin = driver.meta.id)
        return listOf(RegisteredDriver(driver, transport))
    }
    /** 裸 160 字节 Opus 包按原协议封装到 Ogg，队列仍持久化原始数据以支持精确续传。 */
    fun encode(driverId: String, input: File, output: File): Boolean {
        if (driverId != "d3200") return false
        RandomAccessFile(input, "r").use { source ->
            require(source.length() > 0 && source.length() % 160 == 0L)
            val first = ByteArray(160); source.readFully(first); source.seek(0)
            val channels = if (first[0].toInt() and 4 != 0) 2 else 1
            val head = "OpusHead".encodeToByteArray() + byteArrayOf(1,channels.toByte(),0,0,0x80.toByte(),0xbb.toByte(),0,0,0,0,0)
            val vendor = "TaoSheng D3200".encodeToByteArray()
            fun le(n: Int) = ByteArray(4) { (n ushr (8*it)).toByte() }
            val tags = "OpusTags".encodeToByteArray() + le(vendor.size) + vendor + le(0)
            val serial = 0x44333230; var sequence = 0; var granule = 0L
            output.outputStream().buffered().use { sink ->
                sink.write(D3200Ogg.page(listOf(head),serial,sequence++,0,2))
                sink.write(D3200Ogg.page(listOf(tags),serial,sequence++,0))
                while (source.filePointer < source.length()) {
                    val count = minOf(50L,(source.length()-source.filePointer)/160).toInt()
                    val packets = (0 until count).map {
                        ByteArray(160).also { packet ->
                            source.readFully(packet); granule += D3200Ogg.packetSamples(packet)
                            require((if (packet[0].toInt() and 4 != 0) 2 else 1) == channels)
                        }
                    }
                    sink.write(D3200Ogg.page(packets,serial,sequence++,granule,if (source.filePointer==source.length()) 4 else 0))
                }
            }
        }
        return true
    }
}
