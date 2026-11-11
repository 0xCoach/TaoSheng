package app.resound.devices.replay

import app.resound.devices.core.CardParser
import app.resound.devices.core.FrameCodec
import app.resound.devices.driver.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Replay142Test {
    private val header = "#taoecho-capture v1 driver=test recordedAt=1000"
    private fun capture(vararg lines: String) = (listOf(header) + lines).joinToString("\n")
    private fun clock(scheduler: TestCoroutineScheduler) = object : ClockPort {
        override fun nowEpochMillis() = 1000 + scheduler.currentTime
        override fun monotonicMillis() = scheduler.currentTime
        override fun zoneOffsetMinutes() = 0
    }
    private class Tools : Toolbox {
        var closes = 0
        override val crypto: CryptoPort get() = throw DeviceException(ErrorCode.Unsupported)
        override fun opusDecoder(sampleRate: Int, channels: Int) = object : OpusDecoder {
            override fun decode(packet: ByteArray) = ShortArray(320) { (packet.single().toInt() and 255).toShort() }
            override fun close() { closes++ }
        }
    }
    private open class Connection : DriverConnection {
        var closes = 0
        override val identity = DeviceIdentity(DeviceId("replay-device"), IdScope.Global)
        override val capabilities = setOf(Capability.LiveAudio, Capability.Files)
        override var live: LiveAudio? = null
        override var files: DeviceFiles? = null
        override val status = MutableStateFlow(DeviceStatusModel())
        override val controls = object : DeviceControls {}
        override val settings: DeviceSettings? = null
        override val policy = DriverPolicy()
        override val binding: DeviceBinding? = null
        override val closed: Flow<DeviceException> = emptyFlow()
        override suspend fun close() { closes++ }
    }
    private fun driver(id: String = "test", open: suspend (DriverSession) -> DriverConnection) = object : DeviceDriver {
        override val meta = DriverMeta(id, DriverTier.Card, Stability.Trial, LocalizedText("测试"))
        override fun match(ad: Advertisement) = MatchConfidence.Certain
        override suspend fun open(session: DriverSession) = open(session)
    }

    @Test fun syntheticCardThroughOpenLiveDecodeAndClose() = runTest {
        val card = CardParser("0.2.21").parse(javaClass.getResource("/devices/synthetic.json")!!.readText()).getOrThrow()
        val bytes = javaClass.getResource("/devices/replay/synthetic-live.txt")!!.readText()
        val tools = Tools()
        val connection = Connection()
        val driver = driver("card:${card.id}") { session ->
            val audio = card.audio!!
            val codec = FrameCodec(audio.frame)
            assertNotNull(codec.probe(session.link.read(audio.service, audio.notify)))
            val battery = session.link.read(audio.service, "battery").single().toInt()
            connection.status.value = DeviceStatusModel(batteries = listOf(BatteryPart(1, percent = battery)))
            val decoder = session.toolbox.opusDecoder(16000, 1)
            connection.live = object : LiveAudio {
                override suspend fun start() = session.link.write(audio.service, audio.write!!, codec.encode(audio.start!!))
                override suspend fun stop() = session.link.write(audio.service, audio.write!!, codec.encode(audio.stop!!))
                override val frames = session.link.notifications(audio.service, audio.notify).mapNotNull { packet ->
                    val before = codec.diagnostics.lost
                    codec.accept(packet)?.let { frame -> AudioChunk(decoder.decode(frame.payload.copyOfRange(2, frame.payload.size)),
                        lostSamplesBefore = ((codec.diagnostics.lost - before) * 320).toInt()) }
                }
            }
            connection
        }
        val result = ReplayHarness(tools, clock(testScheduler)).run(driver, bytes, ReplayAction.Live(6))
        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertEquals(6, result.frameCount); assertEquals(1920L, result.sampleCount)
        assertEquals(320L, result.lostSamples); assertEquals(1, result.gapCount)
        assertEquals(77, result.statuses.first().batteries.single().percent)
        assertEquals(160.toShort(), result.frames.first().pcm.first())
        assertEquals(1, connection.closes); assertEquals(1, tools.closes)
    }

    @Test fun mismatchedWritesExplainLineExpectedAndActual() = runTest {
        val link = ReplayGattLink(capture("0 W service control aabb"))
        val error = try { link.write("service", "control", byteArrayOf(0xaa.toByte(), 0xcc.toByte())); error("应失败") }
            catch (e: AssertionError) { e }
        assertTrue(error.message!!, error.message!!.contains("第 2 行"))
        assertTrue(error.message!!.contains("期望 W service control aabb"))
        assertTrue(error.message!!.contains("实际 W service control aacc"))
        try { link.write("service", "control", byteArrayOf(0xaa.toByte(), 0xbb.toByte()), false); fail() }
        catch (e: AssertionError) { assertTrue(e.message!!.contains("实际 w")) }
        link.write("service", "control", byteArrayOf(0xaa.toByte(), 0xbb.toByte()))
        link.assertExhausted()
    }

    @Test fun maskedWritesMulticastReadMtuAndDisconnectRemainOrdered() = runTest {
        val link = ReplayGattLink(capture("M 0 mtu=244", "0 R s r 01", "1 w s w 102030",
            "2 N s n aa", "M 3 mtu=180 mode=w", "4 N s n bb", "5 D - - -"),
            listOf(WriteMask("s", "w", setOf(1))))
        assertEquals(244, link.maxWriteLength(true))
        assertArrayEquals(byteArrayOf(1), link.read("s", "r"))
        val first = async(start = CoroutineStart.UNDISPATCHED) { link.notifications("s", "n").toList() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { link.notifications("s", "n").toList() }
        link.write("s", "w", byteArrayOf(0x10, 0x7f, 0x30), false)
        assertEquals(listOf("aa", "bb"), first.await().map { it.hex() })
        assertEquals(listOf("aa", "bb"), second.await().map { it.hex() })
        assertEquals(180, link.maxWriteLength(false)); assertEquals(244, link.maxWriteLength(true))
        assertEquals(ErrorCode.Disconnected, link.disconnections.first().code)
        link.assertExhausted()
    }

    @Test fun recordingForwardsValuesErrorsAndDoesNotWrapCollectorFailures() = runTest {
        val lines = mutableListOf<String>()
        val failed = DeviceException(ErrorCode.BusyRetry, "稍后\n再试")
        val response = byteArrayOf(1, 2)
        val inner = object : GattLink {
            override fun maxWriteLength(withResponse: Boolean) = if (withResponse) 20 else 40
            override suspend fun read(service: String, characteristic: String) = response
            override suspend fun write(service: String, characteristic: String, bytes: ByteArray, withResponse: Boolean) { throw failed }
            override fun notifications(service: String, characteristic: String) = flow { emit(response); throw failed }
        }
        val recorder = RecordingGattLink(inner, clock(testScheduler), lines::add)
        assertEquals(20, recorder.maxWriteLength(true)); recorder.maxWriteLength(true)
        assertEquals(1, lines.size)
        assertSame(response, recorder.read("s", "r"))
        advanceTimeBy(10)
        try { recorder.write("s", "w", response, false); fail() } catch (e: DeviceException) { assertSame(failed, e) }
        try { recorder.notifications("s", "n").collect(); fail() } catch (e: DeviceException) { assertSame(failed, e) }
        val count = lines.count { " E " in it }
        try { recorder.notifications("s", "n").collect { throw IllegalStateException("消费者失败") } }
        catch (_: IllegalStateException) { }
        assertEquals(count, lines.count { " E " in it })
        recorder.recordDisconnected(); recorder.recordDisconnected()
        assertEquals(1, lines.count { " D " in it })
        assertTrue(lines.any { it.startsWith("10 w s w 0102") })
        val failingSink = RecordingGattLink(inner, clock(testScheduler)) { error("磁盘失败") }
        assertSame(response, failingSink.read("s", "r"))
        try { failingSink.write("s", "w", response); fail() } catch (e: DeviceException) { assertSame(failed, e) }
        val replay = ReplayGattLink(capture("0 E s r ${"R|NotFound|missing".encodeToByteArray().hex()}"))
        try { replay.read("s", "r"); fail() } catch (e: DeviceException) { assertEquals(ErrorCode.NotFound, e.code) }
        val cancelled = CancellationException("上游取消")
        val cancelling = RecordingGattLink(object : GattLink by inner {
            override fun notifications(service: String, characteristic: String) = flow<ByteArray> { throw cancelled }
        }, clock(testScheduler), lines::add)
        try { cancelling.notifications("s", "n").collect(); fail() } catch (e: CancellationException) { assertSame(cancelled, e) }
        assertTrue(lines.last().substringAfterLast(' ').unhex().decodeToString().contains("N|Cancelled|"))
    }

    @Test fun formatRejectsBadHexTimeAndVersionAndLimitCountsUtf8() {
        for (bad in listOf(capture("0 N s n a"), capture("2 W s w aa", "1 N s n bb"), header.replace("v1", "v2"))) {
            try { ReplayGattLink(bad); fail("应拒绝 $bad") } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("行")) }
        }
        val chunks = mutableListOf<ByteArray>()
        val sink = LimitedCaptureSink(chunks::add, 100)
        sink.line(header)
        repeat(30) { sink.line("中文") }
        assertTrue(sink.stopped)
        assertTrue(chunks.sumOf { it.size } <= 100)
        assertEquals("# capture-stopped size-limit\n", chunks.last().decodeToString())
        try { ReplayGattLink(capture("# capture-stopped size-limit")).assertExhausted(); fail() }
        catch (e: AssertionError) { assertTrue(e.message!!.contains("不能作为完整回放通过")) }
    }

    @Test fun fileHarnessCollectsListResumeBytesAndErrors() = runTest {
        val file = RecordingFile("1", FileState.Complete, 4, AudioFileFormat.Pcm16k, DeviceTime(epochMillis = 1000), 20)
        val connection = Connection()
        val driver = driver { session ->
            connection.files = object : DeviceFiles {
                override val caps = FileCaps(true, false, false, null, Reclaim.Never)
                override suspend fun list(): List<RecordingFile> { session.link.write("s", "w", byteArrayOf(1)); return listOf(file) }
                override fun read(file: RecordingFile, fromOffset: Long) = flow {
                    assertEquals(2L, fromOffset)
                    session.link.write("s", "w", byteArrayOf(2, fromOffset.toByte()))
                    emit(session.link.read("s", "r"))
                    throw DeviceException(ErrorCode.ChecksumMismatch, "校验失败")
                }
                override suspend fun confirm(file: RecordingFile, checksum: ByteArray?) = error("不能确认测试下载")
                override suspend fun delete(file: RecordingFile) = error("不能删除设备文件")
            }
            connection
        }
        val result = ReplayHarness(Tools(), clock(testScheduler)).run(driver,
            capture("0 W s w 01", "1 W s w 0202", "2 R s r aabb"), ReplayAction.Files(2))
        assertEquals(listOf(file), result.files)
        assertEquals(2L, result.downloads.single().bytes)
        assertEquals("aabb", result.downloads.single().chunks.single().hex())
        assertEquals(ErrorCode.ChecksumMismatch, result.errors.single().code)
        assertEquals(1, connection.closes)
    }

    @Test fun timeoutReturnsErrorAndReleasesStartedAudioAndDecoders() = runTest {
        val tools = Tools(); val connection = Connection(); var stops = 0
        val driver = driver { session ->
            session.toolbox.opusDecoder(16000, 1)
            connection.live = object : LiveAudio {
                override suspend fun start() = Unit
                override suspend fun stop() { stops++ }
                override val frames = flow<AudioChunk> { awaitCancellation() }
            }
            connection
        }
        val result = ReplayHarness(tools, clock(testScheduler)).run(driver, capture(), ReplayAction.Live(1), timeoutMillis = 100)
        assertEquals(ErrorCode.Timeout, result.errors.single().code)
        assertEquals(100L, testScheduler.currentTime)
        assertEquals(1, stops); assertEquals(1, connection.closes); assertEquals(1, tools.closes)
    }

    @Test fun callerCancellationPropagatesAndOpenResourcesAreReleased() = runTest {
        val tools = Tools()
        val cancelled = CompletableDeferred<Unit>()
        val driver = driver { session ->
            session.toolbox.opusDecoder(16000, 1)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        val job = launch { ReplayHarness(tools, clock(testScheduler)).run(driver, capture(), ReplayAction.Live(1)) }
        runCurrent(); job.cancelAndJoin()
        assertTrue(cancelled.isCompleted); assertTrue(job.isCancelled); assertEquals(1, tools.closes)
    }
}
