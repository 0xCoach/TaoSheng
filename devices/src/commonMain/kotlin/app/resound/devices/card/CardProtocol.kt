package app.resound.devices.card

import app.resound.devices.*
import app.resound.devices.core.*
import app.resound.devices.driver.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 品牌协议只在插件目录实现，核心不猜消息头。默认每个 notify 是一个完整包。 */
interface LiveProtocol {
    fun audio(frame: DecodedFrame): ByteArray? = frame.payload
    fun battery(frame: DecodedFrame): Int? = null
    fun batteryQuery(): CardCommand? = null
    val withResponse: Boolean get() = true
}
data class LinkDiagnostics(val received: Long, val lost: Long, val crcFailures: Long, val decodeFailures: Long)
/** 平台在 HubConnector 包装通道时提供身份；驱动不解析句柄或蓝牙地址。
 * ready 只通知宿主连接初始化完成，不表示音源已经可用（必须等第一块有效 PCM）。 */
class CardLink(
    delegate: GattLink,
    val identity: DeviceIdentity,
    val ready: suspend (Long?) -> Unit = {},
) : GattLink by delegate {
    private val error = MutableStateFlow<DeviceException?>(null)
    val failure = error.asStateFlow()
    internal fun failed(cause: DeviceException) { error.compareAndSet(null, cause) }
}

/** 仅把旧文件插口的通道调用翻译到中枢；MTU 是兼容旧接口的值，写入仍由 GattLink 校验。 */
class CardDeviceLink(private val link: GattLink) : DeviceLink {
    override val mtu get() = link.maxWriteLength(true) + 3
    override suspend fun read(service: String, characteristic: String) = link.read(service, characteristic)
    override suspend fun write(service: String, characteristic: String, bytes: ByteArray, withResponse: Boolean) =
        link.write(service, characteristic, bytes, withResponse)
    override fun notifications(service: String, characteristic: String) = link.notifications(service, characteristic)
}
