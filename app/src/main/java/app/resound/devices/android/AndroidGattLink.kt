package app.resound.devices.android

import app.resound.audio.AudioSourceException
import app.resound.devices.LinkedDevice
import app.resound.devices.driver.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*

/** 底层 serial 队列和 CCCD 复用保留；本层增加新约定的长度与错误编码。 */
internal class AndroidGattLink(private val delegate: LinkedDevice) : GattLink {
    override fun maxWriteLength(withResponse: Boolean) = if (delegate.mtu >= 23) delegate.mtu - 3 else 20
    override suspend fun read(service: String, characteristic: String) = platformCall { delegate.read(service, characteristic) }
    override suspend fun write(service: String, characteristic: String, bytes: ByteArray, withResponse: Boolean) {
        if (bytes.size > maxWriteLength(withResponse)) throw DeviceException(ErrorCode.WriteTooLong)
        platformCall { delegate.write(service, characteristic, bytes, withResponse) }
    }
    override fun notifications(service: String, characteristic: String): Flow<ByteArray> = flow {
        platformCall { emitAll(delegate.notifications(service, characteristic)) }
    }
}
internal suspend fun <T> platformCall(block: suspend () -> T): T = try { block() }
    catch (e: CancellationException) { throw e }
    catch (e: DeviceException) { throw e }
    catch (e: SecurityException) { throw DeviceException(ErrorCode.PermissionDenied, cause = e) }
    catch (e: AudioSourceException) { throw DeviceException(ErrorCode.Disconnected, cause = e) }
    catch (e: IllegalArgumentException) { throw DeviceException(ErrorCode.BadArgs, cause = e) }
    catch (e: Exception) { throw DeviceException(ErrorCode.Disconnected, cause = e) }
