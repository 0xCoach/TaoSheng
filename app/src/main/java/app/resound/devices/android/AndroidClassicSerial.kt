package app.resound.devices.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import app.resound.devices.driver.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 地址只在平台层使用；连接、收发取消时关闭 socket，避免留下阻塞的 RFCOMM 操作。 */
@SuppressLint("MissingPermission")
internal class AndroidClassicSerial(private val context: Context, private val address: () -> String?) : ClassicSerialPort {
    override suspend fun open(serviceUuid: String): SerialChannel = coroutineScope {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: throw DeviceException(ErrorCode.Disconnected)
        val socket = try {
            val device = adapter.getRemoteDevice(address() ?: throw DeviceException(ErrorCode.NotFound, "classic address missing"))
            device.createInsecureRfcommSocketToServiceRecord(UUID.fromString(serviceUuid))
        } catch (e: SecurityException) { throw DeviceException(ErrorCode.PermissionDenied, cause = e) }
        try {
            val timer = launch { delay(8_000); runCatching { socket.close() } }
            try {
                suspendCancellableCoroutine<Unit> { continuation ->
                    continuation.invokeOnCancellation { runCatching { socket.close() } }
                    launch(Dispatchers.IO) {
                        try { socket.connect(); if (continuation.isActive) continuation.resume(Unit) }
                        catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(DeviceException(
                            if (e is SecurityException) ErrorCode.PermissionDenied else ErrorCode.Disconnected, "RFCOMM connect failed", e)) }
                    }
                }
            } finally { timer.cancel() }
            AndroidSerialChannel(socket)
        } catch (e: Exception) { runCatching { socket.close() }; throw e }
    }
}

private class AndroidSerialChannel(private val socket: BluetoothSocket) : SerialChannel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val packets = Channel<ByteArray>(128)
    private val reader = scope.launch {
        val buffer = ByteArray(8192)
        try {
            while (isActive) {
                val count = socket.inputStream.read(buffer)
                if (count < 0) throw DeviceException(ErrorCode.Disconnected)
                if (!packets.trySend(buffer.copyOf(count)).isSuccess) throw DeviceException(ErrorCode.Disconnected, "RFCOMM receive overflow")
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { packets.close(e) }
    }
    override suspend fun send(bytes: ByteArray) = withContext(Dispatchers.IO) {
        try { socket.outputStream.write(bytes); socket.outputStream.flush() }
        catch (e: Exception) { throw DeviceException(ErrorCode.Disconnected, "RFCOMM write failed", e) }
    }
    override val incoming: Flow<ByteArray> = packets.receiveAsFlow()
    override suspend fun close() {
        withContext(Dispatchers.IO) { runCatching { socket.close() } }
        scope.cancel(); reader.join(); packets.cancel()
    }
}
