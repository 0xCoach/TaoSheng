package app.resound.devices

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import app.resound.audio.AudioSourceException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** 每条连接一个队列。GATT 操作及回调均在主线程，等回调完成才开始下一项；超时整条连接作废。 */
@SuppressLint("MissingPermission")
internal class AndroidDeviceLink private constructor() : LinkedDevice {
    private val handler = Handler(Looper.getMainLooper())
    private val queue = Mutex()
    private var gatt: BluetoothGatt? = null
    private val connected = CompletableDeferred<Unit>()
    private var pending: Pending? = null
    @Volatile override var disconnectedAt: Long? = null
        private set
    @Volatile private var ended: AudioSourceException? = null
    @Volatile override var mtu: Int = 23
        private set
    private data class Pending(val kind: String, val uuid: UUID?, val result: CompletableDeferred<ByteArray>)
    private data class Subscription(val service: String, val characteristic: String, val channels: MutableSet<Channel<ByteArray>> = mutableSetOf())
    private val subscriptions = mutableMapOf<String, Subscription>()
    private val enabled = mutableSetOf<String>()
    private fun finish(kind: String, uuid: UUID?, status: Int, bytes: ByteArray = byteArrayOf()) {
        val p = pending ?: return
        if (p.kind != kind || p.uuid != uuid) return
        if (status == BluetoothGatt.GATT_SUCCESS) p.result.complete(bytes)
        else p.result.completeExceptionally(AudioSourceException(AudioSourceException.Reason.Disconnected, "蓝牙操作失败（$status）"))
    }
    private fun disconnect(message: String = "蓝牙已断开") {
        val error = AudioSourceException(AudioSourceException.Reason.Disconnected, message)
        if (ended != null) return
        disconnectedAt = System.currentTimeMillis()
        ended = error
        connected.completeExceptionally(error); pending?.result?.completeExceptionally(error)
        synchronized(subscriptions) { subscriptions.values.forEach { s -> s.channels.forEach { it.close(error) } } }
        gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }; gatt = null
    }
    private fun notification(c: BluetoothGattCharacteristic, bytes: ByteArray) {
        val key = "${c.service.uuid}/${c.uuid}"
        var overflow = false
        synchronized(subscriptions) {
            subscriptions[key]?.channels?.forEach { if (!it.trySend(bytes.copyOf()).isSuccess) overflow = true }
        }
        if (overflow) disconnect("蓝牙通知缓存超限")
    }
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (ended != null) return
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) connected.complete(Unit)
            else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) disconnect()
        }
        override fun onMtuChanged(g: BluetoothGatt, value: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = value
            finish("mtu", null, status)
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = finish("services", null, status)
        @Deprecated("旧 Android 回调")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) finish("read", c.uuid, status, c.value?.copyOf() ?: byteArrayOf())
        }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) = finish("read", c.uuid, status, value.copyOf())
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = finish("write", c.uuid, status)
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = finish("descriptor", d.uuid, status)
        @Deprecated("旧 Android 回调")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) notification(c, c.value?.copyOf() ?: byteArrayOf())
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = notification(c, value)
    }
    private fun characteristic(service: String, characteristic: String): BluetoothGattCharacteristic {
        ended?.let { throw it }
        return requireNotNull(gatt?.getService(UUID.fromString(service))?.getCharacteristic(UUID.fromString(characteristic))) { "设备缺少说明卡要求的通道" }
    }
    private suspend fun operation(kind: String, uuid: UUID? = null, start: (BluetoothGatt) -> Boolean): ByteArray {
        ended?.let { throw it }
        val result = CompletableDeferred<ByteArray>()
        check(pending == null)
        pending = Pending(kind, uuid, result)
        try {
            check(start(requireNotNull(gatt))) { "蓝牙操作未被接受" }
            return withTimeout(10_000) { result.await() }
        } catch (e: Exception) {
            // 取消/超时后不能把迟来的回调误配给下一次操作。
            disconnect("蓝牙操作中断")
            if (e is TimeoutCancellationException) throw AudioSourceException(AudioSourceException.Reason.Disconnected, "蓝牙操作超时")
            throw e
        } finally { pending = null }
    }
    private suspend fun <T> serial(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) {
        queue.withLock { ended?.let { throw it }; block() }
    }
    private suspend fun enableSubscriptions() {
        val desired = synchronized(subscriptions) { subscriptions.filterValues { it.channels.isNotEmpty() }.toMap() }
        for ((key, subscription) in desired) if (key !in enabled) {
            val c = characteristic(subscription.service, subscription.characteristic)
            val value = when {
                c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                else -> error("设备通道不支持通知")
            }
            check(requireNotNull(gatt).setCharacteristicNotification(c, true))
            val descriptor = requireNotNull(c.getDescriptor(CCCD)) { "设备缺少通知配置" }
            operation("descriptor", descriptor.uuid) { g ->
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
                else { @Suppress("DEPRECATION") descriptor.value = value; @Suppress("DEPRECATION") g.writeDescriptor(descriptor) }
            }
            enabled += key
        }
    }
    override suspend fun read(service: String, characteristic: String): ByteArray = serial {
        enableSubscriptions()
        val c = characteristic(service, characteristic)
        operation("read", c.uuid) { it.readCharacteristic(c) }
    }
    override suspend fun write(service: String, characteristic: String, bytes: ByteArray, withResponse: Boolean) {
        serial {
            // 即便收集器正在等待切主线程，这里也先完成已登记通知的 CCCD，保证订阅先于命令。
            enableSubscriptions()
            require(bytes.size <= minOf(mtu - 3, 512)) { "命令超过单次蓝牙写入长度，不拆分发送" }
            val c = characteristic(service, characteristic)
            val type = when {
                withResponse && c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                else -> error("设备通道不可写")
            }
            operation("write", c.uuid) { g ->
                if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, bytes.copyOf(), type) == BluetoothStatusCodes.SUCCESS
                else { @Suppress("DEPRECATION") c.value = bytes.copyOf(); c.writeType = type; @Suppress("DEPRECATION") g.writeCharacteristic(c) }
            }
        }
    }
    override fun notifications(service: String, characteristic: String): Flow<ByteArray> = flow {
        val key = "${UUID.fromString(service)}/${UUID.fromString(characteristic)}"
        val channel = Channel<ByteArray>(128)
        synchronized(subscriptions) {
            ended?.let { channel.close(it) } ?: subscriptions.getOrPut(key) { Subscription(service, characteristic) }.channels.add(channel)
        }
        try {
            serial { enableSubscriptions() }
            for (bytes in channel) emit(bytes)
        } finally {
            synchronized(subscriptions) { subscriptions[key]?.channels?.remove(channel) }
            channel.cancel()
            // 同一连接保留 CCCD；多个插件共用，不能取消一个收集器就关闭其他人的通知。
        }
    }
    override suspend fun close() { withContext(Dispatchers.Main.immediate) { disconnect() } }
    companion object {
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        suspend fun connect(context: Context, device: BluetoothDevice, card: DeviceCard): AndroidDeviceLink = withContext(Dispatchers.Main.immediate) {
            val link = AndroidDeviceLink()
            try {
                link.gatt = device.connectGatt(context.applicationContext, false, link.callback, BluetoothDevice.TRANSPORT_LE,
                    BluetoothDevice.PHY_LE_1M_MASK, link.handler) ?: error("无法创建蓝牙连接")
                withTimeout(15_000) { link.connected.await() }
                link.serial {
                    card.link.mtu?.let { size -> link.operation("mtu") { it.requestMtu(size) } }
                    link.operation("services") { it.discoverServices() }
                }
                link
            } catch (e: Exception) {
                link.disconnect()
                if (e is TimeoutCancellationException) throw AudioSourceException(AudioSourceException.Reason.Disconnected, "蓝牙连接超时")
                throw e
            }
        }
    }
}
