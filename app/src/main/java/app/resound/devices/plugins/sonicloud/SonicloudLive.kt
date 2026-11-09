package app.resound.devices.plugins.sonicloud

import app.resound.devices.*
import app.resound.devices.core.DecodedFrame

/** 公开协议 DATA 的 TYPE/CMD 在 LEN 里面；控制/文件应答绝不能送给 Opus。 */
internal class SonicloudLive : LiveProtocol {
    override val withResponse = false
    override fun audio(frame: DecodedFrame): ByteArray? = frame.payload.takeIf {
        it.size > 2 && it[0] == 1.toByte() && it[1] == 1.toByte()
    }?.copyOfRange(2, frame.payload.size)
    override fun battery(frame: DecodedFrame): Int? = frame.payload.takeIf {
        it.size >= 3 && it[0] == 0.toByte() && it[1] == 4.toByte()
    }?.get(2)?.toInt()?.and(255)
    override fun batteryQuery(): CardCommand = CardCommand.Framed(0, 3)
}
