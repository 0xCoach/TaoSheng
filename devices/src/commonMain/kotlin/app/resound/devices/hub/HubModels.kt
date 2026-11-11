package app.resound.devices.hub

import app.resound.devices.driver.*
import kotlinx.coroutines.flow.Flow

/** 句柄只作平台内索引；身份可能在 open/HELLO 后才出现。 */
data class DriverSelection(val platformHandle: String, val driverId: String, val deviceId: DeviceId? = null)

enum class DisableReason { FaultLimit, User }
enum class ConnectionState { Disconnected, Connecting, Connected, Backfilling, WaitingReconnect, Disabled }
enum class LeaseOwner { LiveAudio, Backfill, Management }

data class HubDevice(
    val platformHandle: String,
    val displayName: String,
    val driver: DriverMeta,
    val identity: DeviceIdentity? = null,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val disabledReason: DisableReason? = null,
    val status: DeviceStatusModel = DeviceStatusModel(),
    val lastError: ErrorCode? = null,
    val retryInMillis: Long? = null,
)

sealed interface MatchResult {
    data object None : MatchResult
    data class Selected(val driver: DriverMeta) : MatchResult
    data class Candidates(val drivers: List<DriverMeta>) : MatchResult
    data class Conflict(val driverIds: List<String>) : MatchResult
    data class Disabled(val driverId: String, val reason: DisableReason) : MatchResult
}

data class Diagnostic(val atMillis: Long, val level: LogLevel, val message: String)

/** 平台负责持久化。命名空间组件必须无碰撞编码，禁止直接拼接未转义的设备编号。 */
interface HubRepository {
    fun selections(): List<DriverSelection>
    fun save(selection: DriverSelection)
    fun forget(platformHandle: String)
    fun store(driverId: String, deviceKey: String): DriverStore
    fun disabled(driverId: String, deviceKey: String): DisableReason?
    fun setDisabled(driverId: String, deviceKey: String, reason: DisableReason?)
}

/** 连接工厂由装配层提供，MTU 偏好等属于平台装配参数，不进入共用驱动。 */
fun interface HubConnector { suspend fun connect(platformHandle: String): HubLink }
interface HubLink {
    val gatt: GattLink
    /** 必须重放已发生的底层断开，以免 open 期间漏事件。 */
    val disconnected: Flow<DeviceException>
    suspend fun close()
}
