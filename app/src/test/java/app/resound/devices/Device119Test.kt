package app.resound.devices

import android.app.Application
import android.content.Context
import app.resound.audio.*
import app.resound.backup.BackupSettings
import app.resound.devices.core.*
import app.resound.devices.plugins.sonicloud.SonicloudLive
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
    private fun card(name: String = "omi") = CardParser("0.2.21").parse(File("src/main/assets/devices/$name.json").readText()).getOrThrow()
    private class Cards(card: DeviceCard, enabled: Boolean = true) : DeviceCards {
        override val cards = MutableStateFlow(listOf(InstalledCard(card, CardSource.Bundled, enabled)))
        override fun parse(json: String) = CardParser("0.2.21").parse(json)
        override fun setEnabled(cardId: String, enabled: Boolean) { cards.value = cards.value.map { it.copy(enabled = enabled) } }
    }
    private class Store(var rows: List<RememberedDevice> = emptyList()) : PairedDeviceStore {
        override fun read() = rows
        override fun write(devices: List<RememberedDevice>) { rows = devices }
    }
    private class Link : LinkedDevice {
        override val mtu = 247
        val incoming = Channel<ByteArray>(128)
        val subscribed = CompletableDeferred<Unit>()
        val writes = mutableListOf<ByteArray>()
        var closed = false
        var onWrite: suspend (ByteArray) -> Unit = {}
        override suspend fun read(service: String, characteristic: String) = if (characteristic == CardAudioSource.BATTERY_LEVEL) byteArrayOf(88) else byteArrayOf(0)
        override suspend fun write(service: String, characteristic: String, bytes: ByteArray, withResponse: Boolean) {
            assertTrue("必须先订阅再发命令", subscribed.isCompleted)
            writes += bytes.copyOf(); onWrite(bytes)
        }
        override fun notifications(service: String, characteristic: String): Flow<ByteArray> = flow {
            subscribed.complete(Unit)
            for (bytes in incoming) emit(bytes)
        }
        override suspend fun close() { closed = true; incoming.close(AudioSourceException(AudioSourceException.Reason.Disconnected)) }
        suspend fun pcm(seq: Int, zero: Boolean = false) {
            incoming.send(byteArrayOf(seq.toByte(), (seq shr 8).toByte(), 0) + ByteArray(960) { if (zero || it % 2 == 1) 0 else 1 })
        }
    }
    private class Radio : DeviceRadio {
        var discovery: ((DeviceAdvertisement) -> Unit)? = null
        val connections = Channel<Link>(Channel.UNLIMITED)
        override fun startScan(result: (DeviceAdvertisement) -> Unit, error: (String) -> Unit) { discovery = result }
        override fun stopScan() { discovery = null }
        override suspend fun pair(address: String, bond: Boolean) {}
        override suspend fun connect(address: String, card: DeviceCard): LinkedDevice = Link().also { connections.send(it) }
    }
    private fun source(row: RememberedDevice, card: DeviceCard, link: DeviceLink, ready: suspend (Long?) -> Unit, battery: (Int) -> Unit) =
        CardAudioSource(ManagedDevices.deviceKey(row.address), row.name, card, link, ready = ready, battery = battery)

    @Test fun omiNotificationsDecodeCodecReadAndFragmentIntoStandardPcm() = runBlocking {
        val link = Link()
        val frames = async { CardAudioSource("device", "设备", card(), link).events().filterIsInstance<AudioEvent.Frame>().take(2).toList() }
        link.subscribed.await()
        link.pcm(0); link.pcm(1); link.pcm(2)
        val result = withTimeout(2000) { frames.await() }
        assertEquals(2, result.size)
        assertTrue(result.all { it.samples.size == 480 && it.samples.all { v -> v == 1.toShort() } })
    }

    @Test fun sonicHalfPacketsControlFilteringCrcAndStop() = runBlocking {
        val card = card("sonicloud")
        val link = Link()
        val fixed = card.audio!!.frame.copy(fields = card.audio.frame.fields.map { it.copy(endian = Endian.Le) })
        val encoder = FrameCodec(fixed)
        var decoded = 0
        var report: LinkDiagnostics? = null
        val battery = mutableListOf<Int>()
        link.onWrite = { bytes ->
            val payload = FrameCodec(fixed).accept(bytes)!!.payload
            when {
                payload.contentEquals(byteArrayOf(0, 3)) -> {
                    val reply = encoder.encode(CardCommand.Framed(0, 4, byteArrayOf(75)))
                    link.incoming.send(reply.copyOf(3)); link.incoming.send(reply.copyOfRange(3, reply.size))
                }
                payload.contentEquals(byteArrayOf(1, 0)) -> {
                    val control = encoder.encode(CardCommand.Framed(2, 1, byteArrayOf(8)))
                    val bad = encoder.encode(CardCommand.Framed(1, 1, byteArrayOf(9))).also { it[2] = (it[2].toInt() xor 1).toByte() }
                    val good = encoder.encode(CardCommand.Framed(1, 1, byteArrayOf(7)))
                    link.incoming.send(control + bad + good)
                }
            }
        }
        val audio = CardAudioSource("device", "设备", card, link, SonicloudLive(), opus = { _, _ -> object : OpusDecoder {
            override fun decode(packet: ByteArray): List<DecodedPcm> {
                assertArrayEquals(byteArrayOf(7), packet); decoded++
                return listOf(DecodedPcm(ShortArray(480) { 123 }, 16000, 1))
            }
        } }, battery = battery::add, report = { report = it })
        val first = withTimeout(2000) { audio.events().filterIsInstance<AudioEvent.Frame>().first() }
        assertEquals(123.toShort(), first.samples[0]); assertEquals(1, decoded)
        assertEquals(1L, report!!.crcFailures); assertTrue(report!!.lost >= 1)
        assertTrue(link.writes.any { FrameCodec(fixed).accept(it)?.payload?.contentEquals(byteArrayOf(1, 2)) == true })
    }

    @Test fun noValidAudioTimesOutEvenWhileControlNotificationsArrive() = runBlocking {
        val link = Link()
        val error = async {
            try { CardAudioSource("id", "设备", card(), link, readTimeout = 30).events().collect(); null }
            catch (e: AudioSourceException) { e }
        }
        link.subscribed.await()
        link.incoming.send(byteArrayOf(0))
        assertEquals(AudioSourceException.Reason.Disconnected, withTimeout(1500) { error.await() }?.reason)
    }

    @Test fun continuousZeroUsesCapture112Watchdog() = runBlocking {
        val link = Link()
        var elapsed = 0L
        val error = async {
            try {
                CardAudioSource("id", "设备", card(), link, elapsed = { elapsed }, readTimeout = 100_000).events().collect {
                    elapsed += 21_000
                }; null
            } catch (e: AudioSourceException) { e }
        }
        link.subscribed.await(); link.pcm(0, true); link.pcm(1, true); link.pcm(2, true)
        assertEquals(CaptureHealth.ZERO_PCM, withTimeout(2000) { error.await() }?.message)
    }

    @Test fun scanOnlyMatchesEnabledCardsByAnyDeclaredRule() {
        val card = card()
        val trial = InstalledCard(card, CardSource.Bundled, false)
        val ad = DeviceAdvertisement("AA", "Omi test", emptySet(), emptySet(), -40)
        assertNull(matchingCard(ad, listOf(trial)))
        val on = trial.copy(enabled = true)
        assertEquals(on, matchingCard(ad, listOf(on)))
        assertNull(matchingCard(ad.copy(name = "无关设备"), listOf(on)))
        assertEquals(on, matchingCard(ad.copy(name = null, services = card.match.serviceUuid.toSet()), listOf(on)))
        val manufacturer = on.copy(card = card.copy(match = CardMatch(manufacturerId = 89)))
        assertEquals(manufacturer, matchingCard(ad.copy(name = null, manufacturers = setOf(89)), listOf(manufacturer)))
        val route = InputRoute(InputDevice("CardDevice:AA", "设备", InputKind.CardDevice, true))
        assertEquals(InputDevices.PHONE, InputSnapshot(InputChoice.AutoExternal, listOf(route)).selected().device)
    }

    @Test fun missingAndroidScanPermissionReturnsErrorWithoutThrowing() = runBlocking {
        val job = SupervisorJob()
        try {
            val managed = ManagedDevices(Cards(card()), Store(), AndroidDeviceRadio(RuntimeEnvironment.getApplication()),
                CoroutineScope(coroutineContext + job), ::source)
            managed.startScan()
            assertTrue(managed.lastError.value!!.contains("权限"))
            assertTrue(managed.found.value.isEmpty())
        } finally { job.cancelAndJoin() }
    }

    @Test fun pairForgetAndDisablingCardRemoveDiscovery() = runBlocking {
        val cards = Cards(card()); val store = Store(); val radio = Radio(); val job = SupervisorJob()
        val managed = ManagedDevices(cards, store, radio, CoroutineScope(coroutineContext + job), ::source)
        try {
            managed.startScan()
            radio.discovery!!(DeviceAdvertisement("AA:BB:CC:DD:EE:FF", "Omi", emptySet(), emptySet(), -50))
            managed.pair(managed.found.value.single()); yield()
            assertEquals("omi", store.rows.single().cardId)
            cards.setEnabled("omi", false); yield()
            assertTrue(managed.found.value.isEmpty())
            managed.forget(store.rows.single().address)
            assertTrue(store.rows.isEmpty()); assertTrue(managed.devices.value.isEmpty())
        } finally { managed.stopScan(); job.cancelAndJoin() }
    }

    @Test fun fakeDeviceDisconnectFallsBackAndReconnectCallsSyncWithActualGap() = runBlocking {
        val card = card().copy(offline = OfflineMode.Files, filePlugin = "test", clock = CardClock("service", "clock", ClockFormat.Unix32Le))
        val row = RememberedDevice("AA:BB:CC:DD:EE:FF", card.id, "设备")
        val key = ManagedDevices.deviceKey(row.address)
        val choice = InputChoice.Device(key, row.name)
        val store = Store(listOf(row)); val radio = Radio(); val job = SupervisorJob()
        val worker = CoroutineScope(coroutineContext + job)
        val sync = Channel<Triple<Long?, Long?, Long?>>(Channel.UNLIMITED)
        var time = 1000L
        val managed = ManagedDevices(Cards(card), store, radio, worker,
            source = { r, c, l, ready, battery -> CardAudioSource(key, r.name, c, l, wallTime = { time }, ready = ready, battery = battery) },
            onLinked = { _, _, _, start, end, offset, _ -> sync.send(Triple(start, end, offset)) }, now = { time }, retryMillis = 30)
        val snapshots = MutableStateFlow(InputSnapshot(choice, emptyList()))
        val changes = Channel<String>(Channel.UNLIMITED)
        worker.launch { managed.devices.collect { states ->
            snapshots.value = InputSnapshot(choice, states.map { InputRoute(InputDevice(key, it.name, InputKind.CardDevice, it.status == DeviceStatus.Live)) })
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
            first.incoming.close(AudioSourceException(AudioSourceException.Reason.Disconnected))
            assertEquals("mic", next())
            assertEquals(2000L, store.rows.single().gapStart)
            time = 5000
            val second = withTimeout(2000) { radio.connections.receive() }; second.subscribed.await()
            assertEquals(Triple(2000L, 5000L, 0L), withTimeout(2000) { sync.receive() })
            second.pcm(0); second.pcm(1); assertEquals(key, next())
            assertTrue(first.closed)
            managed.recording(false)
            withTimeout(2000) { while (!second.closed) yield() }
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

    @Test fun clockEncodingUsesDeclaredEndianAndUtcBcd() {
        assertArrayEquals(byteArrayOf(1, 0, 0, 0), clockBytes(ClockFormat.Unix32Le, 1000))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1), clockBytes(ClockFormat.Unix32Be, 1000))
        assertArrayEquals(byteArrayOf(0x26, 0x11, 0x09, 0x12, 0x34, 0x56),
            clockBytes(ClockFormat.Bcd, java.time.Instant.parse("2026-11-09T12:34:56Z").toEpochMilli()))
    }
}
