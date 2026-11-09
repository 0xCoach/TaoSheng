/*
 * [INPUT]: 真实 SonicloudRecorder / PNote、延后或失败的绑定回调及扫描结果。
 * [OUTPUT]: 一次点击后的目标卡自动连接、取消隔离和初始化失败重试回归。
 * [POS]: 替代无线链路，不替代真实控制器与 SDK 命令发送路径。
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package app.resound.sonicloud

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.*
import android.os.Looper
import app.resound.importer.AndroidAudioImporter
import app.resound.importer.AudioMetadata
import com.wind.pnote.bluetooth.BluetoothEvent
import com.wind.pnote.bluetooth.BluetoothType
import com.wind.pnote.service.WindBluetoothService
import kotlinx.coroutines.*
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.NoSubscriberEvent
import org.greenrobot.eventbus.Subscribe
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

class DeferredRecorderApplication : Application() {
    var connection: ServiceConnection? = null
    var allowBinding = true
    override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean {
        connection = conn
        return allowBinding
    }
    override fun unbindService(conn: ServiceConnection) { if (connection === conn) connection = null }
    override fun startForegroundService(intent: Intent): ComponentName? = intent.component
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = DeferredRecorderApplication::class)
class RecorderConnectionTest {
    class Dropped {
        val events = CopyOnWriteArrayList<BluetoothEvent>()
        @Subscribe fun receive(event: NoSubscriberEvent) {
            (event.originalEvent as? BluetoothEvent)?.let { events += it }
        }
    }
    class Receiver {
        val events = CopyOnWriteArrayList<BluetoothEvent>()
        @Subscribe fun receive(event: BluetoothEvent) { events += event }
    }

    private fun pumpUntil(condition: () -> Boolean) {
        val end = System.nanoTime() + 3_000_000_000L
        while (!condition() && System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("控制器未在时限内完成预期步骤", condition())
    }

    private inner class Fixture : AutoCloseable {
        val app = RuntimeEnvironment.getApplication() as DeferredRecorderApplication
        val address = "00:11:22:33:44:55"
        private val imports = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dropped = Dropped()
        val receiver = Receiver()
        private val bus = EventBus.getDefault()
        val recorder: SonicloudRecorder
        init {
            shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setState(BluetoothAdapter.STATE_ON)
            app.getSharedPreferences("sonicloud", Context.MODE_PRIVATE).edit()
                .putString("address", address).putString("name", "CB08").commit()
            recorder = SonicloudRecorder(app, AndroidAudioImporter(app, imports,
                inspect = { AudioMetadata(1000, null, 1) }, enqueue = {}))
            bus.register(dropped)
        }
        fun connect() = recorder.connect(checkNotNull(recorder.status.value.device))
        fun bind() {
            pumpUntil { app.connection != null }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("SDK 尚未绑定，连接命令已经丢失：${dropped.events.size} 条", dropped.events.isEmpty())
            bus.register(receiver)
            val service = WindBluetoothService()
            app.connection!!.onServiceConnected(ComponentName(app, WindBluetoothService::class.java), service.onBind(Intent()))
            pumpUntil { receiver.events.isNotEmpty() }
        }
        fun found(address: String = this.address) = recorder.onDeviceDataEvent(
            """{"cmd":1,"state":0,"data":{"name":"CB08","address":"$address"}}""")
        fun connections() = receiver.events.filter { it.bluetoothType == BluetoothType.CONNECT }
        override fun close() {
            if (bus.isRegistered(receiver)) bus.unregister(receiver)
            bus.unregister(dropped)
            val field = SonicloudRecorder::class.java.getDeclaredField("scope").apply { isAccessible = true }
            (field.get(recorder) as CoroutineScope).cancel()
            imports.cancel()
        }
    }

    @Test fun savedCardConnectWaitsForBindingAndAutomaticallyConnectsOnlyMatchingAddress() {
        Fixture().use { f ->
            f.connect(); f.bind()
            assertEquals("冷启动必须先让 SDK 发现已保存的地址", BluetoothType.BACKSCAN, f.receiver.events.first().bluetoothType)
            assertTrue(f.connections().isEmpty())
            f.found("00:11:22:33:44:66")
            pumpUntil { f.recorder.status.value.devices.isNotEmpty() }
            assertTrue(f.connections().isEmpty())
            f.found()
            pumpUntil { f.connections().isNotEmpty() }
            assertEquals(1, f.connections().size)
            assertEquals(f.address, String(f.connections().single().buffer))
            assertFalse(f.recorder.status.value.scanning)
            assertTrue(f.dropped.events.isEmpty())
        }
    }

    @Test fun cancelledSearchIgnoresLateMatchAndExplicitRetryStillConnects() {
        Fixture().use { f ->
            f.connect(); f.bind()
            f.recorder.stopScan()
            pumpUntil { !f.recorder.status.value.scanning }
            f.found()
            pumpUntil { f.recorder.status.value.devices.any { it.address == f.address } }
            assertTrue(f.connections().isEmpty())
            f.connect()
            pumpUntil { f.connections().isNotEmpty() }
            assertEquals(1, f.connections().size)
            assertEquals(f.address, String(f.connections().single().buffer))
        }
    }

    @Test fun failedServiceBindingReleasesAttemptAndNextClickCanRetry() {
        Fixture().use { f ->
            f.app.allowBinding = false
            f.connect()
            pumpUntil { f.recorder.status.value.error != null }
            assertNull(f.app.connection)
            assertFalse(f.recorder.status.value.scanning)
            assertTrue(f.dropped.events.isEmpty())
            f.app.allowBinding = true
            f.connect(); f.bind(); f.found()
            pumpUntil { f.connections().isNotEmpty() }
            assertEquals(1, f.connections().size)
        }
    }
}
