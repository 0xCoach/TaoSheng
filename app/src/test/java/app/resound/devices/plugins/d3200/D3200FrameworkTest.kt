package app.resound.devices.plugins.d3200

import app.resound.audio.*
import app.resound.devices.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class D3200FrameworkTest {
    private fun packet() = ByteArray(160).apply { this[0] = 0xf8.toByte() }
    @Test fun rawResumeReplaysOneBoundaryPacketAndEmitsOnlyMissingBytes() {
        val cursor = D3200DownloadCursor(320, 4)
        assertEquals(1L, cursor.requested)
        assertNull(cursor.accept(1, packet()))
        assertArrayEquals(packet(), cursor.accept(2, packet()))
        assertNull(cursor.accept(2, packet()))
        assertArrayEquals(packet(), cursor.accept(3, packet()))
        assertTrue(cursor.complete)
        val eof = D3200DownloadCursor(640, 4)
        assertFalse(eof.complete); assertNull(eof.accept(3, packet())); assertTrue(eof.complete)
    }
    @Test fun missingPacketsAndPartialByteOffsetsAreRejected() {
        try { D3200DownloadCursor(161, 4); fail("unaligned queue offset") } catch (_: IllegalArgumentException) {}
        val cursor = D3200DownloadCursor(0, 4)
        cursor.accept(0, packet())
        try { cursor.accept(2, packet()); fail("sequence hole") } catch (_: IllegalArgumentException) {}
        assertFalse(cursor.complete)
    }
    @Test fun queuedFramesKeepTheirOriginalTimestampUntilConsumed() = runBlocking {
        val bus = DeviceAudioBus()
        var clock = 1000L
        bus.timeline = object : AudioTimeline {
            override val epochMillis get() = clock
            override val archiveSourceId = "device:file"
            override fun processed(at: Long?, samples: Int) {}
            override fun finishCapture() {}
        }
        val received = mutableListOf<DeviceAudioBus.Delivery>()
        val reader = launch(start = CoroutineStart.UNDISPATCHED) { bus.events().take(2).toList(received) }
        bus.send(AudioEvent.Frame(ShortArray(480))); clock = 1030
        bus.send(AudioEvent.Frame(ShortArray(480))); clock = 9999
        reader.join()
        assertEquals(listOf(1000L, 1030L), received.map { it.epoch })
        assertTrue(received.all { it.archive == "device:file" })
    }
    @Test fun serialDeviceBackfillMustFinishBeforeTheLiveSourceStarts() = runBlocking {
        val card = DeviceCard("fake", "fake", 1, "0.2.22", CardStatus.Trial, Transport.Ble, CardMatch(),
            audio = CardAudio("s", "n", codec = Codec.Pcm16, frame = FrameSpec()), offline = OfflineMode.Files, filePlugin = "fake")
        val cards = object : DeviceCards {
            override val cards = MutableStateFlow(listOf(InstalledCard(card, CardSource.Bundled, true)))
            override fun parse(json: String): Result<DeviceCard> = Result.success(card)
            override fun setEnabled(cardId: String, enabled: Boolean) {}
        }
        val store = object : PairedDeviceStore {
            override fun read() = listOf(RememberedDevice("AA", "fake", "fake", 1000, 0))
            override fun write(devices: List<RememberedDevice>) {}
        }
        val link = object : LinkedDevice {
            override val mtu = 247
            override val backfillBeforeLive = true
            override suspend fun close() {}
            override suspend fun read(service: String, characteristic: String) = byteArrayOf()
            override suspend fun write(service: String, characteristic: String, bytes: ByteArray, withResponse: Boolean) {}
            override fun notifications(service: String, characteristic: String) = emptyFlow<ByteArray>()
        }
        val radio = object : DeviceRadio {
            override fun startScan(result: (DeviceAdvertisement) -> Unit, error: (String) -> Unit) {}
            override fun stopScan() {}
            override suspend fun pair(address: String, bond: Boolean) {}
            override suspend fun connect(address: String, card: DeviceCard) = link
        }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val live = CompletableDeferred<Unit>()
        val job = SupervisorJob()
        try {
            val managed = ManagedDevices(cards, store, radio, CoroutineScope(coroutineContext + job),
                source = { _, _, _, ready, _ -> object : AudioSource {
                    override val id = "fake"; override val displayName = "fake"
                    override fun events() = flow<AudioEvent> { ready(0); live.complete(Unit); emit(AudioEvent.Frame(ShortArray(480))); awaitCancellation() }
                } }, onLinked = { _, _, _, _, _, _, _ -> entered.complete(Unit); release.await() })
            managed.select(InputChoice.Device(ManagedDevices.deviceKey("AA"), "fake")); managed.recording(true)
            withTimeout(2000) { entered.await() }
            assertFalse(live.isCompleted)
            release.complete(Unit)
            withTimeout(2000) { live.await() }
        } finally { job.cancelAndJoin() }
    }
    @Test fun officialTransferIdentityPreservesRenameDedupAndSeparatesIntervals() {
        val file = DeviceFile("123", "old", 1600, 10000, 200, FileFormat.Opus)
        val key = deviceTransferKey("device", file, 0, 100)
        assertEquals(key, deviceTransferKey("device", file.copy(name = "renamed"), 0, 100))
        assertNotEquals(key, deviceTransferKey("device", file, 100, 200))
        assertNotEquals(key, deviceTransferKey("another-device", file, 0, 100))
    }
}
