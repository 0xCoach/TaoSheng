package app.resound.devices.plugins.d3200

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** One GATT writer, separate per-transport parsers, one complete-frame receive queue. */
@SuppressLint("MissingPermission")
internal class D3200Transport(private val context: Context, private val device: BluetoothDevice) : AutoCloseable {
    private val ready = CountDownLatch(1)
    private val bleDecoder = BeanLight.Decoder()
    private val incoming = LinkedBlockingQueue<ByteArray>(1024)
    @Volatile private var error: Exception? = null
    @Volatile private var closed = false
    @Volatile private var writeDone: CountDownLatch? = null
    @Volatile private var writeOk = false
    @Volatile private var socket: BluetoothSocket? = null
    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                fail(IOException("录音豆蓝牙连接已断开 ($status)")); return
            }
            if (state == BluetoothProfile.STATE_CONNECTED && !g.discoverServices()) fail(IOException("无法发现录音豆服务"))
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            try {
                require(status == BluetoothGatt.GATT_SUCCESS)
                val service = requireNotNull(g.getService(UUID.fromString(D3200Protocol.SERVICE)))
                tx = requireNotNull(service.getCharacteristic(UUID.fromString(D3200Protocol.WRITE)))
                val notify = requireNotNull(service.getCharacteristic(UUID.fromString(D3200Protocol.NOTIFY)))
                require(g.setCharacteristicNotification(notify, true))
                val descriptor = requireNotNull(notify.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")))
                if (Build.VERSION.SDK_INT >= 33) require(g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS)
                else { @Suppress("DEPRECATION") descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; @Suppress("DEPRECATION") require(g.writeDescriptor(descriptor)) }
            } catch (e: Exception) { fail(IOException("无法订阅录音豆通知", e)) }
        }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { fail(IOException("录音豆通知订阅失败")); return }
            if (!g.requestMtu(517)) ready.countDown()
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { ready.countDown() }
        @Deprecated("Legacy Android callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) { @Suppress("DEPRECATION") receiveBle(c.value ?: byteArrayOf()) }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = receiveBle(value)
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeOk = status == BluetoothGatt.GATT_SUCCESS; writeDone?.countDown()
        }
    }
    private fun fail(e: Exception) { if (!closed) error = e; ready.countDown(); writeDone?.countDown() }
    private fun receiveBle(bytes: ByteArray) {
        try { bleDecoder.push(bytes).forEach(::offer) } catch (e: Exception) { fail(e) }
    }
    private fun offer(b: ByteArray) { if (!incoming.offer(b)) fail(IOException("录音豆音频队列溢出，需要重新连接")) }
    fun connect() {
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (!ready.await(12, TimeUnit.SECONDS)) throw IOException("连接录音豆超时")
        error?.let { throw it }
        requireNotNull(tx)
    }
    @Synchronized fun write(b: ByteArray) {
        error?.let { throw it }
        val g = requireNotNull(gatt); val c = requireNotNull(tx)
        val done = CountDownLatch(1); writeDone = done; writeOk = false
        if (Build.VERSION.SDK_INT >= 33) {
            if (g.writeCharacteristic(c, b, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) != BluetoothStatusCodes.SUCCESS) throw IOException("录音豆写入被拒绝")
        } else {
            @Suppress("DEPRECATION") c.value = b
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION") if (!g.writeCharacteristic(c)) throw IOException("录音豆写入被拒绝")
        }
        if (!done.await(4, TimeUnit.SECONDS) || !writeOk) throw IOException("录音豆写入超时")
        error?.let { throw it }
    }
    fun receive(waitMs: Long): ByteArray? { error?.let { throw it }; return incoming.poll(waitMs, TimeUnit.MILLISECONDS) }
    /** Only the owning session calls this, after its fixed 1A0F preparation ACK. */
    fun openSpp(address: String) {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val s = adapter.getRemoteDevice(address).createInsecureRfcommSocketToServiceRecord(UUID.fromString(BeanLight.SPP))
        socket = s
        val timer = Thread({ try { Thread.sleep(8000); if (!s.isConnected) s.close() } catch (_: Exception) {} }, "bean-spp-timeout").apply { isDaemon = true; start() }
        try { s.connect() } finally { timer.interrupt() }
        Thread({
            val decoder = BeanLight.Decoder(); val buffer = ByteArray(8192)
            try { while (!closed) {
                val n = s.inputStream.read(buffer)
                if (n < 0) throw IOException("录音豆 SPP 已断开")
                decoder.push(buffer.copyOf(n)).forEach(::offer)
            } } catch (e: Exception) { fail(e) }
        }, "bean-spp-reader").apply { isDaemon = true; start() }
    }
    fun writeSpp(bytes: ByteArray) { requireNotNull(socket).outputStream.apply { write(bytes); flush() } }
    override fun close() {
        closed = true; ready.countDown(); writeDone?.countDown()
        runCatching { socket?.close() }; socket = null
        runCatching { gatt?.disconnect() }; runCatching { gatt?.close() }; gatt = null
    }
}
