package app.resound.devices

import android.app.Application
import android.content.Context
import app.resound.audio.*
import app.resound.backup.BackupSettings
import app.resound.devices.android.*
import app.resound.devices.core.CardParser
import app.resound.devices.driver.*
import app.resound.devices.hub.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE, application = Application::class)
class Device119Test {
    private fun card() = CardParser("0.2.21").parse(File("src/main/assets/devices/omi.json").readText()).getOrThrow()
    private class Cards(card: DeviceCard, enabled: Boolean = true) : DeviceCards {
        override val cards = MutableStateFlow(listOf(InstalledCard(card, CardSource.Bundled, enabled)))
        override fun parse(json: String) = CardParser("0.2.21").parse(json)
        override fun setEnabled(cardId: String, enabled: Boolean) { cards.value = cards.value.map { it.copy(enabled = enabled) } }
    }
    private class Store(var rows: List<RememberedDevice> = emptyList()) : PairedDeviceStore {
        override fun read() = rows
        override fun write(devices: List<RememberedDevice>) { rows = devices }
    }
    private class Link : HubLink, GattLink {
        override val gatt get() = this
        override fun maxWriteLength(withResponse: Boolean) = 244
        override val disconnected = MutableSharedFlow<DeviceException>(replay = 1)
        val incoming = Channel<ByteArray>(128)
        val subscribed = CompletableDeferred<Unit>()
        var closed = false
        override suspend fun read(service: String, characteristic: String) =
            if (characteristic == "00002a19-0000-1000-8000-00805f9b34fb") byteArrayOf(88) else byteArrayOf(0)
        override suspend fun write(service: String, characteristic: String, bytes: ByteArray, withResponse: Boolean) {
            assertTrue("必须先订阅再发命令", subscribed.isCompleted)
        }
        override fun notifications(service: String, characteristic: String): Flow<ByteArray> = flow {
            subscribed.complete(Unit)
            for (bytes in incoming) emit(bytes)
        }
        override suspend fun close() { closed = true; incoming.close() }
        suspend fun pcm(seq: Int, zero: Boolean = false) {
            incoming.send(byteArrayOf(seq.toByte(), (seq shr 8).toByte(), 0) + ByteArray(960) { if (zero || it % 2 == 1) 0 else 1 })
        }
    }
    private class Radio {
        val ads = MutableSharedFlow<Advertisement>(extraBufferCapacity = 10)
        val connections = Channel<Link>(Channel.UNLIMITED)
        var connectGate: CompletableDeferred<Unit>? = null
        suspend fun connect(): HubLink {
            connectGate?.await()
            return Link().also { connections.send(it) }
        }
    }
    private fun inputs(cards: DeviceCards, store: Store, radio: Radio, worker: CoroutineScope,
        now: () -> Long = { System.currentTimeMillis() },
        elapsed: () -> Long = { System.nanoTime() / 1_000_000 }, scan: (() -> Flow<Advertisement>)? = null,
        onLinked: suspend (RememberedDevice, DeviceCard, DeviceLink, Long?, Long?, Long?, (BackfillProgress?) -> Unit) -> Unit = { _, _, _, _, _, _, _ -> }): DeviceInputs {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("hub-119", Context.MODE_PRIVATE).apply { edit().clear().commit() }
        return DeviceInputs(cards, store, AndroidHubRepository(prefs), { _, _ -> radio.connect() },
            object : Toolbox {
                override val crypto: CryptoPort get() = error("unused")
                override fun opusDecoder(sampleRate: Int, channels: Int): OpusDecoder = error("unused")
            }, object : ClockPort {
                override fun nowEpochMillis() = now()
                override fun monotonicMillis() = elapsed()
                override fun zoneOffsetMinutes() = 0
            }, object : PlatformExtras {}, scan ?: { radio.ads }, { _, _ -> }, AndroidDevicePlatform::legacyIdentity,
            worker, onLinked = onLinked)
    }
    private suspend fun until(condition: () -> Boolean) = withTimeout(3000) { while (!condition()) delay(1) }

    @Test fun missingAndroidScanPermissionReturnsErrorWithoutThrowing() = runBlocking {
        val job = SupervisorJob()
        try {
            val context = RuntimeEnvironment.getApplication()
            val worker = CoroutineScope(coroutineContext + job)
            val center = AndroidBluetoothCenter(context, worker, AndroidClockPort())
            val managed = inputs(Cards(card()), Store(), Radio(), worker, scan = { center.scan() })
            managed.startScan()
            // 扫描改为异步中心；Robolectric 需推进主 Looper，错误断言保持不变。
            withTimeout(3000) {
                while (managed.lastError.value == null) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    delay(1)
                }
            }
            assertTrue(managed.lastError.value!!.contains("权限"))
            assertTrue(managed.found.value.isEmpty())
        } finally { job.cancelAndJoin() }
    }

    @Test fun pairForgetAndDisablingCardRemoveDiscovery() = runBlocking {
        val cards = Cards(card()); val store = Store(); val radio = Radio(); val job = SupervisorJob()
        val managed = inputs(cards, store, radio, CoroutineScope(coroutineContext + job))
        try {
            managed.startScan(); until { radio.ads.subscriptionCount.value > 0 }
            radio.ads.emit(Advertisement("AA:BB:CC:DD:EE:FF", "Omi", emptySet(), emptyMap(), -50))
            until { managed.found.value.isNotEmpty() }
            managed.pair(managed.found.value.single()); until { store.rows.isNotEmpty() }
            assertEquals("omi", store.rows.single().cardId)
            cards.setEnabled("omi", false); until { managed.found.value.isEmpty() }
            assertTrue(managed.found.value.isEmpty())
            managed.forget(store.rows.single().address); until { store.rows.isEmpty() }
            assertTrue(store.rows.isEmpty()); assertTrue(managed.devices.value.isEmpty())
        } finally { managed.stopScan(); job.cancelAndJoin() }
    }

    @Test fun fakeDeviceDisconnectFallsBackAndReconnectCallsSyncWithActualGap() = runBlocking {
        val card = card().copy(offline = OfflineMode.Files, filePlugin = "test", clock = CardClock("service", "clock", ClockFormat.Unix32Le))
        val row = RememberedDevice("AA:BB:CC:DD:EE:FF", card.id, "设备")
        val key = DeviceInputs.deviceKey(row.address)
        val choice = InputChoice.Device(key, row.name)
        val store = Store(listOf(row)); val radio = Radio(); val job = SupervisorJob()
        val worker = CoroutineScope(coroutineContext + job)
        val sync = Channel<Triple<Long?, Long?, Long?>>(Channel.UNLIMITED)
        var time = 1234L
        val managed = inputs(Cards(card), store, radio, worker, now = { time },
            onLinked = { _, _, _, start, end, offset, _ -> sync.send(Triple(start, end, offset)) })
        val snapshots = MutableStateFlow(InputSnapshot(choice, emptyList()))
        val changes = Channel<String>(Channel.UNLIMITED)
        worker.launch { managed.devices.collect { states ->
            snapshots.value = InputSnapshot(choice, states.map { InputRoute(InputDevice(key, it.name, InputKind.CardDevice,
                it.status == DeviceStatus.Live || it.status == DeviceStatus.Backfilling)) })
        } }
        worker.launch { InputSwitchLoop(snapshots, { System.nanoTime() / 1_000_000 }, cooldownMillis = 10).run { route, _ ->
            changes.send(route.device.key)
            if (route.device.kind == InputKind.Phone) awaitCancellation() else managed.audioSource(key).events().collect()
        } }
        suspend fun next() = withTimeout(2000) { changes.receive() }
        try {
            managed.select(choice); managed.recording(true)
            assertEquals("mic", next())
            val first = withTimeout(2000) { radio.connections.receive() }; first.subscribed.await()
            assertEquals(Triple(null, null, null), withTimeout(2000) { sync.receive() })
            first.pcm(0); first.pcm(1); assertEquals(key, next())
            time = 2000
            first.disconnected.emit(DeviceException(ErrorCode.Disconnected))
            assertEquals("mic", next())
            assertEquals(2000L, store.rows.single().gapStart)
            time = 5678
            val second = withTimeout(5000) { radio.connections.receive() }; second.subscribed.await()
            // 新对时偏差是 678，补传必须仍用断开前的 234。
            assertEquals(Triple(2000L, 5678L, 234L), withTimeout(2000) { sync.receive() })
            second.pcm(0); second.pcm(1); assertEquals(key, next())
            assertTrue(first.closed)
            until { store.rows.single().clockOffset == 678L }
            assertNull(store.rows.single().gapStart)
            managed.recording(false)
            withTimeout(2000) { while (!second.closed) yield() }
        } finally { job.cancelAndJoin() }
    }

    @Test fun missingPairedCardIsUnavailableAndNeverConnects() = runBlocking {
        val cards = Cards(card()).apply { cards.value = emptyList() }
        val row = RememberedDevice("AA:BB:CC:DD:EE:FF", "removed-card", "旧设备")
        val radio = Radio(); val job = SupervisorJob()
        val managed = inputs(cards, Store(listOf(row)), radio, CoroutineScope(coroutineContext + job))
        try {
            managed.select(InputChoice.Device(DeviceInputs.deviceKey(row.address), row.name)); managed.recording(true)
            yield()
            assertEquals(DeviceStatus.Disconnected, managed.devices.value.single().status)
            assertEquals("设备说明卡不可用", managed.devices.value.single().lastError)
            assertTrue(radio.connections.tryReceive().isFailure)
        } finally { job.cancelAndJoin() }
    }

    @Test fun unconnectedAndConnectedWithoutFirstPcmBothKeepPhoneAvailable() = runBlocking {
        val row = RememberedDevice("AA:BB:CC:DD:EE:FF", "omi", "设备")
        val key = DeviceInputs.deviceKey(row.address)
        val radio = Radio().apply { connectGate = CompletableDeferred() }
        val job = SupervisorJob()
        val managed = inputs(Cards(card()), Store(listOf(row)), radio, CoroutineScope(coroutineContext + job))
        fun selected() = InputSnapshot(InputChoice.Device(key, row.name), managed.devices.value.map {
            InputRoute(InputDevice(key, it.name, InputKind.CardDevice, it.status == DeviceStatus.Live || it.status == DeviceStatus.Backfilling))
        }).selected().device
        try {
            managed.select(InputChoice.Device(key, row.name)); managed.recording(true)
            until { managed.devices.value.single().status == DeviceStatus.Connecting }
            assertEquals(InputDevices.PHONE, selected())
            try { managed.audioSource(key).events().first(); fail("连接前不得发布声音") } catch (e: AudioSourceException) {
                assertEquals(AudioSourceException.Reason.Disconnected, e.reason)
            }
            radio.connectGate!!.complete(Unit)
            val link = withTimeout(2000) { radio.connections.receive() }; link.subscribed.await()
            assertEquals(InputDevices.PHONE, selected())
            link.pcm(0); link.pcm(1)
            until { managed.devices.value.single().status == DeviceStatus.Live }
            assertEquals(key, selected().key)
            // 原匹配测试里 AutoExternal 不自动抢占专用设备的断言继续保留。
            val route = InputRoute(InputDevice(key, row.name, InputKind.CardDevice, true))
            assertEquals(InputDevices.PHONE, InputSnapshot(InputChoice.AutoExternal, listOf(route)).selected().device)
            managed.forget(row.address); until { managed.devices.value.isEmpty() && link.closed }
        } finally { job.cancelAndJoin() }
    }
    @Test fun zeroAudioFailureSurvivesHubCancellationAndReleasesDecoderStream() = runBlocking {
        val row = RememberedDevice("AA:BB:CC:DD:EE:FF", "omi", "设备")
        val store = Store(listOf(row)); val radio = Radio(); val job = SupervisorJob()
        val worker = CoroutineScope(coroutineContext + job)
        var elapsed = 1000L
        val managed = inputs(Cards(card()), store, radio, worker, now = { 9000L }, elapsed = { elapsed })
        val key = DeviceInputs.deviceKey(row.address)
        try {
            managed.select(InputChoice.Device(key, row.name)); managed.recording(true)
            val link = withTimeout(2000) { radio.connections.receive() }; link.subscribed.await()
            link.pcm(0); link.pcm(1)
            until { managed.devices.value.single().status == DeviceStatus.Live }
            val zero = CompletableDeferred<Unit>()
            val error = worker.async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    managed.audioSource(key).events().collect { event ->
                        if (event is AudioEvent.Frame && event.samples.all { it == 0.toShort() }) zero.complete(Unit)
                    }; null
                } catch (e: AudioSourceException) { e }
            }
            yield()
            link.pcm(2, true); link.pcm(3, true)
            withTimeout(2000) { zero.await() }
            elapsed = 22_001
            link.pcm(4, true)
            assertEquals(CaptureHealth.ZERO_PCM, withTimeout(2000) { error.await() }?.message)
            until { link.closed && store.rows.single().gapStart == 9000L }
        } finally { job.cancelAndJoin() }
    }

    @Test fun pairedDevicesAndCardSwitchesSurviveSettingsBackup() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("devices", Context.MODE_PRIVATE)
        val cardPrefs = context.getSharedPreferences("device-cards", Context.MODE_PRIVATE)
        val store = AndroidPairedDeviceStore(prefs)
        val row = RememberedDevice("AA:BB:CC:DD:EE:FF", "omi", "设备", 123, 2)
        store.write(listOf(row)); cardPrefs.edit().putBoolean("enabled:omi", true).putLong("last-attempt", 999).commit()
        val dir = File(context.cacheDir, "devices-119").apply { mkdirs() }
        BackupSettings.capture(context, dir) { _, _ -> "" }
        assertFalse(File(dir, "settings.json").readText().contains("last-attempt"))
        store.write(emptyList()); cardPrefs.edit().clear().commit()
        BackupSettings.apply(context, dir) { _, _, _ -> }
        assertEquals(listOf(row), store.read()); assertTrue(cardPrefs.getBoolean("enabled:omi", false))
    }


}
