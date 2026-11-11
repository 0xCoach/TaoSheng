package app.resound.devices.card

import app.resound.devices.*
import app.resound.devices.core.*
import app.resound.devices.driver.*
import app.resound.devices.driver.OpusDecoder
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CardDriver141Test {
    private fun card(name: String = "omi") = CardParser("0.2.21").parse(if (name == "synthetic") javaClass.getResource("/devices/synthetic.json")!!.readText()
        else File(System.getProperty("deviceCardsDir"), "$name.json").readText()).getOrThrow()
    private class Link : GattLink {
        override fun maxWriteLength(withResponse: Boolean) = 244
        val incoming = Channel<ByteArray>(128)
        val subscribed = CompletableDeferred<Unit>()
        val writes = mutableListOf<ByteArray>()
        var onWrite: suspend (ByteArray) -> Unit = {}
        override suspend fun read(service: String, characteristic: String) = if (characteristic == CardLiveAudio.BATTERY_LEVEL) byteArrayOf(88) else byteArrayOf(0)
        override suspend fun write(service: String, characteristic: String, bytes: ByteArray, withResponse: Boolean) {
            assertTrue("必须先订阅再发命令", subscribed.isCompleted)
            writes += bytes.copyOf(); onWrite(bytes)
        }
        override fun notifications(service: String, characteristic: String): Flow<ByteArray> = flow {
            subscribed.complete(Unit)
            for (bytes in incoming) emit(bytes)
        }
        suspend fun pcm(seq: Int, zero: Boolean = false) {
            incoming.send(byteArrayOf(seq.toByte(), (seq shr 8).toByte(), 0) + ByteArray(960) { if (zero || it % 2 == 1) 0 else 1 })
        }
    }
    private fun session(link: Link, elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
        decodePacket: (ByteArray) -> ShortArray = { error("unexpected Opus") }) = object : DriverSession {
        override val link = CardLink(link, DeviceIdentity(DeviceId("platform:test"), IdScope.PlatformLocal))
        override val clock = object : ClockPort {
            override fun nowEpochMillis() = 1234L
            override fun monotonicMillis() = elapsed()
            override fun zoneOffsetMinutes() = 0
        }
        override val toolbox = object : Toolbox {
            override val crypto: CryptoPort get() = error("unused")
            override fun opusDecoder(sampleRate: Int, channels: Int): OpusDecoder {
                assertEquals(16000, sampleRate)
                return object : OpusDecoder {
                    override fun decode(packet: ByteArray) = decodePacket(packet)
                    override fun close() { }
                }
            }
        }
        override val store: DriverStore get() = error("unused")
        override val extras = object : PlatformExtras { }
        override fun schedule(name: String, everyMillis: Long, task: suspend () -> Unit): ScheduledTask = error("unused")
        override fun log(level: LogLevel, message: String) { }
    }
    private suspend fun DriverConnection.audio(): Flow<AudioChunk> = live!!.also { it.start() }.frames

    @Test fun omiNotificationsDecodeCodecReadAndFragmentIntoStandardPcm() = runBlocking {
        val link = Link()
        val connection = CardDriver(card(), true).open(session(link))
        val frames = async { connection.audio().take(2).toList() }
        link.subscribed.await()
        link.pcm(0); link.pcm(1); link.pcm(2)
        val result = withTimeout(2000) { frames.await() }
        assertEquals(2, result.size)
        assertTrue(result.all { it.pcm.size == 480 && it.pcm.all { v -> v == 1.toShort() } })
        assertEquals(DeviceIdentity(DeviceId("platform:test"), IdScope.PlatformLocal), connection.identity)
    }

    @Test fun syntheticHalfPacketsControlFilteringCrcAndStop() = runBlocking {
        val card = card("synthetic")
        val link = Link()
        val frame = card.audio!!.frame
        val fixed = frame.copy(fields = frame.fields.map { it.copy(endian = Endian.Le) })
        val encoder = FrameCodec(fixed)
        var decoded = 0
        var report: LinkDiagnostics? = null
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
        val driver = CardDriver(card, true, protocol = object : LiveProtocol {
            override val withResponse = false
            override fun audio(frame: DecodedFrame) = frame.payload.takeIf {
                it.size > 2 && it[0] == 1.toByte() && it[1] == 1.toByte()
            }?.copyOfRange(2, frame.payload.size)
            override fun batteryQuery(): CardCommand = CardCommand.Framed(0, 3)
        }, report = { report = it })
        val connection = driver.open(session(link, decodePacket = { packet ->
            assertArrayEquals(byteArrayOf(7), packet); decoded++
            ShortArray(480) { 123 }
        }))
        val first = withTimeout(2000) { connection.audio().first() }
        assertEquals(123.toShort(), first.pcm[0]); assertEquals(1, decoded)
        assertEquals(1L, report!!.crcFailures); assertTrue(report!!.lost >= 1)
        assertTrue(link.writes.any { FrameCodec(fixed).accept(it)?.payload?.contentEquals(byteArrayOf(1, 2)) == true })
    }

    @Test fun noValidAudioTimesOutEvenWhileControlNotificationsArrive() = runBlocking {
        val link = Link()
        val connection = CardDriver(card(), true, readTimeout = 30).open(session(link))
        val error = async {
            try { connection.audio().collect(); null } catch (e: DeviceException) { e }
        }
        link.subscribed.await(); link.incoming.send(byteArrayOf(0))
        assertEquals(ErrorCode.Disconnected, withTimeout(1500) { error.await() }?.code)
    }

    @Test fun continuousZeroKeepsCapture112WatchdogThreshold() = runBlocking {
        val link = Link()
        var elapsed = 0L
        val connection = CardDriver(card(), true, readTimeout = 100_000).open(session(link, { elapsed }))
        val error = async {
            try { connection.audio().collect { elapsed += 21_000 }; null } catch (e: DeviceException) { e }
        }
        link.subscribed.await(); link.pcm(0, true); link.pcm(1, true); link.pcm(2, true)
        assertEquals("zero-pcm", withTimeout(2000) { error.await() }?.detail)
    }

    @Test fun scanOnlyMatchesEnabledCardsByAnyDeclaredRule() {
        val card = card()
        val off = CardDriver(card, false)
        val ad = Advertisement("AA", "Omi test", emptySet(), emptyMap(), -40)
        assertEquals(MatchConfidence.None, off.match(ad))
        val on = CardDriver(card, true)
        assertEquals(MatchConfidence.Certain, on.match(ad))
        assertEquals(MatchConfidence.None, on.match(ad.copy(name = "无关设备")))
        assertEquals(MatchConfidence.Certain, on.match(ad.copy(name = null, services = card.match.serviceUuid.toSet())))
        val manufacturer = CardDriver(card.copy(match = CardMatch(manufacturerId = 89)), true)
        assertEquals(MatchConfidence.Certain, manufacturer.match(ad.copy(name = null, manufacturerData = mapOf(89 to byteArrayOf()))))
        assertEquals("card:omi", on.meta.id); assertEquals(DriverTier.Card, on.meta.tier); assertEquals(Stability.Trial, on.meta.stability)
        assertEquals(Stability.Stable, CardDriver(card.copy(status = CardStatus.Verified), true).meta.stability)
    }

    @Test fun clockEncodingUsesDeclaredEndianAndUtcBcd() {
        assertArrayEquals(byteArrayOf(1, 0, 0, 0), clockBytes(ClockFormat.Unix32Le, 1000))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1), clockBytes(ClockFormat.Unix32Be, 1000))
        assertArrayEquals(byteArrayOf(0x26, 0x11, 0x09, 0x12, 0x34, 0x56),
            clockBytes(ClockFormat.Bcd, java.time.Instant.parse("2026-11-09T12:34:56Z").toEpochMilli()))
    }

    @Test fun syntheticStreamConvertsSequenceLossToSamplesAndRejectsBadCrc() = runBlocking {
        val original = card("synthetic")
        val spec = original.audio!!.frame.let { it.copy(fields = it.fields.map { f -> f.copy(endian = Endian.Le) }) }
        val card = original.copy(audio = original.audio.copy(frame = spec, start = null, stop = null))
        val link = Link()
        val encoder = FrameCodec(spec)
        var decoded = 0
        var stats: LinkDiagnostics? = null
        val connection = CardDriver(card, true, report = { stats = it }).open(session(link, decodePacket = { decoded++; ShortArray(320) { 17 } }))
        val result = async { connection.audio().take(2).toList() }
        link.subscribed.await()
        repeat(2) { link.incoming.send(encoder.encode(CardCommand.Framed(1, 1, byteArrayOf(7)))) }
        encoder.encode(CardCommand.Framed(1, 1)) // 序号 2 丢失。
        link.incoming.send(encoder.encode(CardCommand.Framed(1, 1)).also { it[2] = (it[2].toInt() xor 1).toByte() })
        repeat(2) { link.incoming.send(encoder.encode(CardCommand.Framed(1, 1, byteArrayOf(7)))) }
        val chunks = withTimeout(2000) { result.await() }
        assertTrue(chunks.all { it.pcm.size == 480 && it.pcm.all { v -> v == 17.toShort() } })
        assertEquals(listOf(0, 640), chunks.map { it.lostSamplesBefore })
        assertEquals(4, decoded); assertEquals(1L, stats!!.crcFailures); assertEquals(2L, stats!!.lost)
    }

    @Test fun batteryHasPartAndReadTimeAndEightKhzStillProducesStandardFrames() = runBlocking {
        val link = Link()
        val base = card("adeus-esp32c3")
        val connection = CardDriver(base.copy(battery = CardBattery()), true).open(session(link))
        val frames = async { connection.audio().take(2).toList() }
        link.subscribed.await()
        link.incoming.send(ByteArray(960) { if (it % 2 == 0) 3 else 0 })
        val pcm = withTimeout(2000) { frames.await() }
        assertTrue(pcm.all { it.pcm.size == 480 && it.pcm.all { v -> v == 3.toShort() } })
        connection.controls.refresh()
        assertEquals(BatteryPart(1, percent = 88, readAtMillis = 1234), connection.status.value.batteries.single())
    }

    @Test fun fakeFilePluginMapsResumeConfirmationAndReclaim() = runBlocking {
        val row = DeviceFile("file", "sample.pcm", 4, 1000, 2000, FileFormat.Pcm16)
        var opens = 0; var deletes = 0; var offset: Long? = null
        val plugin = object : FileSourcePlugin {
            override val id = "fake"
            override val caps = app.resound.devices.FileCaps(true, true, false)
            override suspend fun open(link: DeviceLink) { opens++ }
            override suspend fun list(link: DeviceLink) = listOf(row)
            override fun download(link: DeviceLink, file: DeviceFile, fromOffset: Long) = flow {
                assertEquals(row, file); offset = fromOffset; emit(byteArrayOf(3, 4))
            }
            override suspend fun delete(link: DeviceLink, file: DeviceFile) { assertEquals(row, file); deletes++ }
        }
        val files = CardFiles(plugin, CardDeviceLink(Link()))
        val file = files.list().single()
        assertEquals(AudioFileFormat.Pcm16k, file.format); assertEquals(1000L, file.start.epochMillis)
        assertEquals(Reclaim.Manual, files.caps.reclaim); assertFalse(files.caps.confirm)
        assertArrayEquals(byteArrayOf(3, 4), files.read(file, 2).single()); assertEquals(2L, offset)
        files.confirm(file, null); assertEquals(0, deletes)
        files.delete(file); assertEquals(1, deletes)
        files.list(); assertEquals(1, opens)
        val noDelete = object : FileSourcePlugin by plugin { override val caps = app.resound.devices.FileCaps(false, false, false) }
        assertEquals(Reclaim.Never, CardFiles(noDelete, CardDeviceLink(Link())).caps.reclaim)
    }
}
