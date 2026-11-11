package app.resound.devices.hub

import app.resound.devices.driver.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceHubTest {
    private class Store : DriverStore {
        val strings = mutableMapOf<String, String>()
        val bytes = mutableMapOf<String, ByteArray>()
        override fun getString(key: String) = strings[key]
        override fun putString(key: String, value: String?) { if (value == null) strings.remove(key) else strings[key] = value }
        override fun getBytes(key: String) = bytes[key]?.copyOf()
        override fun putBytes(key: String, value: ByteArray?) { if (value == null) bytes.remove(key) else bytes[key] = value.copyOf() }
        override fun keys() = strings.keys + bytes.keys
    }
    private class Repository : HubRepository {
        val selected = mutableMapOf<String, DriverSelection>()
        val stores = mutableMapOf<Pair<String, String>, Store>()
        val disabled = mutableMapOf<Pair<String, String>, DisableReason>()
        override fun selections() = selected.values.toList()
        override fun save(selection: DriverSelection) { selected[selection.platformHandle] = selection }
        override fun forget(platformHandle: String) { selected.remove(platformHandle) }
        override fun store(driverId: String, deviceKey: String) = stores.getOrPut(driverId to deviceKey) { Store() }
        override fun disabled(driverId: String, deviceKey: String) = disabled[driverId to deviceKey]
        override fun setDisabled(driverId: String, deviceKey: String, reason: DisableReason?) {
            if (reason == null) disabled.remove(driverId to deviceKey) else disabled[driverId to deviceKey] = reason
        }
    }
    private class Link : HubLink {
        var closed = false
        override val disconnected = MutableSharedFlow<DeviceException>(replay = 1)
        override val gatt = object : GattLink {
            override fun maxWriteLength(withResponse: Boolean) = 20
            override suspend fun read(service: String, characteristic: String) = byteArrayOf()
            override suspend fun write(service: String, characteristic: String, bytes: ByteArray, withResponse: Boolean) { }
            override fun notifications(service: String, characteristic: String) = emptyFlow<ByteArray>()
        }
        override suspend fun close() { closed = true }
    }
    private class Connection : DriverConnection {
        var closedCount = 0
        override val identity = DeviceIdentity(DeviceId("device-1"), IdScope.Global)
        override val capabilities = setOf(Capability.Files)
        override val live: LiveAudio? = null
        override var files: DeviceFiles? = null
        override val status = MutableStateFlow(DeviceStatusModel())
        override var controls: DeviceControls = object : DeviceControls { }
        override val settings: DeviceSettings? = null
        override val policy = DriverPolicy()
        override val binding: DeviceBinding? = null
        override val closed = MutableSharedFlow<DeviceException>()
        override suspend fun close() { closedCount++ }
    }
    private class Driver(id: String = "test", var confidence: MatchConfidence = MatchConfidence.Certain) : DeviceDriver {
        override val meta = DriverMeta(id, DriverTier.Plugin, Stability.Trial, LocalizedText(id))
        var matches = 0
        var matchError: Exception? = null
        var opener: suspend (DriverSession) -> DriverConnection = { Connection() }
        override fun match(ad: Advertisement): MatchConfidence { matches++; matchError?.let { throw it }; return confidence }
        override suspend fun open(session: DriverSession) = opener(session)
    }
    private class Fixture(val test: TestScope, val drivers: List<Driver> = listOf(Driver()), val repo: Repository = Repository()) {
        val links = mutableListOf<Link>()
        val times = mutableListOf<Long>()
        val connections = mutableListOf<Connection>()
        val sessions = mutableListOf<DriverSession>()
        var decodersClosed = 0
        val ad = Advertisement("handle-1", "display", emptySet(), emptyMap(), -42)
        val clock = object : ClockPort {
            override fun nowEpochMillis() = 1_000_000 + test.testScheduler.currentTime
            override fun monotonicMillis() = test.testScheduler.currentTime
            override fun zoneOffsetMinutes() = 480
        }
        val hub = DeviceHub(object : DriverCatalog { override val drivers = this@Fixture.drivers }, repo,
            HubConnector { times += test.testScheduler.currentTime; Link().also(links::add) },
            object : Toolbox {
                override val crypto: CryptoPort get() = error("unused")
                override fun opusDecoder(sampleRate: Int, channels: Int) = object : OpusDecoder {
                    override fun decode(packet: ByteArray) = shortArrayOf()
                    override fun close() { decodersClosed++ }
                }
            }, clock, object : PlatformExtras {}, test.backgroundScope)
        init { drivers.forEach { driver -> driver.opener = { sessions += it; Connection().also(connections::add) } } }
        suspend fun start() { hub.match(ad); hub.connect(ad.platformHandle); test.runCurrent() }
        fun state() = hub.devices.value.single()
    }
    private suspend fun expect(code: ErrorCode, action: suspend () -> Unit) {
        try { action(); fail("expected $code") } catch (e: DeviceException) { assertEquals(code, e.code) }
    }

    @Test fun certainSelectionAndIdentityAreRemembered() = runTest {
        val f = Fixture(this); f.start()
        assertEquals(ConnectionState.Connected, f.state().connection)
        assertEquals(DeviceId("device-1"), f.repo.selected.getValue("handle-1").deviceId)
        assertTrue(f.hub.match(f.ad) is MatchResult.Selected)
        assertEquals(1, f.drivers.single().matches)
        f.hub.close()
        assertTrue(f.links.single().closed); assertEquals(1, f.connections.single().closedCount)
    }
    @Test fun conflictingCertainDriversDoNotConnect() = runTest {
        val f = Fixture(this, listOf(Driver("one"), Driver("two")))
        assertEquals(listOf("one", "two"), (f.hub.match(f.ad) as MatchResult.Conflict).driverIds)
        assertTrue(f.repo.selected.isEmpty()); assertTrue(f.links.isEmpty())
        assertTrue(f.hub.diagnostics("handle-1").single().message.contains("conflict"))
    }
    @Test fun possibleCandidatesRequireSelection() = runTest {
        val f = Fixture(this, listOf(Driver("one", MatchConfidence.Possible), Driver("two", MatchConfidence.Possible)))
        assertEquals(2, (f.hub.match(f.ad) as MatchResult.Candidates).drivers.size)
        assertTrue(f.repo.selected.isEmpty())
        f.hub.select(f.ad, "two"); assertEquals("two", (f.hub.match(f.ad) as MatchResult.Selected).driver.id)
    }
    @Test fun brokenMatchDoesNotHideOtherDriver() = runTest {
        val bad = Driver("broken").apply { matchError = IllegalStateException("broken") }
        val f = Fixture(this, listOf(bad, Driver("healthy")))
        assertEquals("healthy", (f.hub.match(f.ad) as MatchResult.Selected).driver.id)
        assertEquals(1, f.hub.diagnostics("handle-1").size)
        val onlyBad = Fixture(this, listOf(Driver("broken").apply { matchError = IllegalStateException("bad") }))
        repeat(4) { assertEquals(MatchResult.None, onlyBad.hub.match(onlyBad.ad)) }
        assertTrue(onlyBad.hub.match(onlyBad.ad) is MatchResult.Disabled)
        assertEquals(DisableReason.FaultLimit, onlyBad.state().disabledReason)
        onlyBad.hub.enable("handle-1")
        assertEquals(ConnectionState.Disconnected, onlyBad.state().connection)
    }
    @Test fun leasesQueueReportBusyAndReleaseOnCancellation() = runTest {
        val f = Fixture(this); f.start()
        val first = launch { f.hub.withConnection("handle-1", LeaseOwner.LiveAudio) { awaitCancellation() } }
        runCurrent()
        expect(ErrorCode.BusyRetry) { f.hub.withConnection("handle-1", LeaseOwner.Management, wait = false) {} }
        var second = false
        val next = launch { f.hub.withConnection("handle-1", LeaseOwner.Backfill) {
            assertEquals(ConnectionState.Backfilling, f.state().connection); second = true
        } }
        runCurrent(); assertFalse(second)
        first.cancelAndJoin(); runCurrent(); next.join()
        assertTrue(second); assertEquals(ConnectionState.Connected, f.state().connection)
    }
    @Test fun timersClampLimitAndDisconnectCancelsThemAndDecoder() = runTest {
        val f = Fixture(this); f.start(); val session = f.sessions.single()
        var runs = 0
        // 丢包会重建解码器；主动关闭后不应在断连时重复释放，也不再滞留会话列表。
        repeat(2) { session.toolbox.opusDecoder(16000, 1).also { it.close(); it.close() } }
        assertEquals(2, f.decodersClosed)
        session.toolbox.opusDecoder(16000, 1)
        val task = session.schedule("a", 1) { runs++ }
        repeat(3) { session.schedule("other$it", 999_999) {} }
        expect(ErrorCode.BusyRetry) { session.schedule("fifth", 30_000) {} }
        advanceTimeBy(29_999); runCurrent(); assertEquals(0, runs)
        advanceTimeBy(1); runCurrent(); assertEquals(1, runs)
        task.cancel(); runCurrent(); session.schedule("replacement", 30_000) { runs++ }
        f.links.single().disconnected.emit(DeviceException(ErrorCode.Disconnected)); runCurrent()
        assertEquals(3, f.decodersClosed)
        f.hub.disable("handle-1"); advanceTimeBy(120_000); runCurrent(); assertEquals(1, runs)
    }
    @Test fun timersIsolateExceptionsAndDiagnosticsStayBounded() = runTest {
        val f = Fixture(this); f.start(); val session = f.sessions.single()
        var healthy = 0
        session.schedule("bad", 30_000) { error("bad") }
        session.schedule("good", 30_000) { healthy++ }
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, healthy); assertEquals(ConnectionState.Connected, f.state().connection)
        assertEquals(ErrorCode.ProtocolError, f.state().lastError)
        repeat(501) { session.log(LogLevel.Debug, "line-$it") }
        assertEquals(500, f.hub.diagnostics("handle-1").size)
        assertEquals("line-1", f.hub.diagnostics("handle-1").first().message)
    }
    @Test fun reconnectBackoffCapsAndForgetStops() = runTest {
        val f = Fixture(this); f.start()
        for (wait in listOf(3_000L, 6_000L, 12_000L, 24_000L, 48_000L, 60_000L, 60_000L)) {
            f.connections.last().closed.emit(DeviceException(ErrorCode.Disconnected)); runCurrent()
            assertEquals(wait, f.state().retryInMillis)
            val count = f.links.size
            advanceTimeBy(wait - 1); runCurrent(); assertEquals(count, f.links.size)
            advanceTimeBy(1); runCurrent(); assertEquals(count + 1, f.links.size)
        }
        f.hub.forget("handle-1"); val count = f.links.size
        advanceTimeBy(180_000); runCurrent()
        assertEquals(count, f.links.size); assertTrue(f.hub.devices.value.isEmpty()); assertTrue(f.repo.selected.isEmpty())
    }
    @Test fun fiveUnexpectedOpenFailuresDisableUntilExplicitEnable() = runTest {
        val f = Fixture(this)
        f.drivers.single().opener = { error("bad open") }
        f.start()
        for (wait in listOf(3_000L, 6_000L, 12_000L, 24_000L)) { advanceTimeBy(wait); runCurrent() }
        assertEquals(ConnectionState.Disabled, f.state().connection)
        assertEquals(DisableReason.FaultLimit, f.state().disabledReason)
        assertTrue(f.links.all { it.closed })
        advanceTimeBy(300_000); runCurrent(); assertEquals(5, f.links.size)
        assertTrue(f.hub.match(f.ad) is MatchResult.Disabled)
        f.drivers.single().opener = { Connection() }
        f.hub.enable("handle-1"); runCurrent(); assertEquals(ConnectionState.Connected, f.state().connection)
    }
    @Test fun expectedErrorsAndExpiredFaultsDoNotDisable() = runTest {
        val f = Fixture(this); f.start()
        var broken = true
        f.hub.close()
        val faults = mutableListOf<ErrorCode>()
        val b = DriverBoundary(f.clock, { faults += it }, {})
        repeat(6) { expect(ErrorCode.Unsupported) { b.call { throw DeviceException(ErrorCode.Unsupported) } } }
        assertTrue(faults.isEmpty())
        val g = Fixture(this)
        g.drivers.single().opener = { session ->
            session.schedule("fault", 160_000) { if (broken) error("bad") }
            Connection()
        }
        g.start(); advanceTimeBy(960_000); runCurrent()
        assertEquals(ConnectionState.Connected, g.state().connection)
        broken = false
    }
    @Test fun controlTimeoutCountsOnceAndDoesNotEscapeToOtherDevice() = runTest {
        val f = Fixture(this)
        f.drivers.single().opener = { Connection().apply {
            controls = object : DeviceControls { override suspend fun refresh() { awaitCancellation() } }
        } }
        f.start()
        val command = async { expect(ErrorCode.Timeout) { f.hub.withConnection("handle-1", LeaseOwner.Management) { it.controls.refresh() } } }
        runCurrent(); advanceTimeBy(15_000); runCurrent(); command.await()
        assertEquals(1, f.hub.diagnostics("handle-1").size)
        assertEquals(ConnectionState.Connected, f.state().connection)
    }
    @Test fun fileTimeoutIsPerBlockAndConsumerTimeIsExcluded() = runTest {
        val f = Fixture(this)
        f.drivers.single().opener = { Connection().apply {
            files = object : DeviceFiles {
                override val caps = FileCaps(true, true, false, null, Reclaim.Manual)
                override suspend fun list() = emptyList<RecordingFile>()
                override fun read(file: RecordingFile, fromOffset: Long) = flow {
                    delay(29_000); emit(byteArrayOf(1)); delay(29_000); emit(byteArrayOf(2)); awaitCancellation()
                }
                override suspend fun confirm(file: RecordingFile, checksum: ByteArray?) {}
                override suspend fun delete(file: RecordingFile) {}
            }
        } }
        f.start(); val received = mutableListOf<Int>()
        val command = async { expect(ErrorCode.Timeout) {
            f.hub.withConnection("handle-1", LeaseOwner.Backfill) { c ->
                c.files!!.read(RecordingFile("file", FileState.Complete, null, AudioFileFormat.Opus20, DeviceTime(), null), 0)
                    .collect { received += it[0].toInt(); delay(40_000) }
            }
        } }
        runCurrent(); advanceTimeBy(200_000); runCurrent(); command.await()
        assertEquals(listOf(1, 2), received); assertEquals(1, f.hub.diagnostics("handle-1").size)
    }
    @Test fun physicalDisconnectCancelsLeaseAndReleasesForNextConnection() = runTest {
        val f = Fixture(this); f.start(); var released = false
        val holder = async { expect(ErrorCode.Disconnected) {
            f.hub.withConnection("handle-1", LeaseOwner.LiveAudio) { try { awaitCancellation() } finally { released = true } }
        } }
        runCurrent(); f.links.single().disconnected.emit(DeviceException(ErrorCode.Disconnected)); runCurrent(); holder.await()
        assertTrue(released)
        advanceTimeBy(3_000); runCurrent()
        f.hub.withConnection("handle-1", LeaseOwner.Management, false) { assertNotNull(it.identity) }
    }
    @Test fun sessionStoreMovesToIdentityAndKeepsNamespacesSeparate() = runTest {
        val f = Fixture(this)
        f.repo.store("test", "id:device-1").putString("progress", "offset-10")
        f.drivers.single().opener = { session ->
            f.sessions += session; session.store.putString("progress", "offset-17"); session.store.putBytes("auth", byteArrayOf(1, 2)); Connection()
        }
        f.start()
        assertEquals("offset-17", f.repo.store("test", "id:device-1").getString("progress"))
        assertTrue(f.repo.store("test", "handle:handle-1").keys().isEmpty())
        f.sessions.single().store.putString("progress", "offset-20")
        assertEquals("offset-20", f.repo.store("test", "id:device-1").getString("progress"))
        assertNull(f.repo.store("other", "id:device-1").getString("progress"))
    }
    @Test fun statusUpdatesAndManualDisableStopReconnect() = runTest {
        val f = Fixture(this); f.start()
        val status = DeviceStatusModel(batteries = listOf(BatteryPart(1, percent = 42)))
        f.connections.single().status.value = status; runCurrent(); assertEquals(status, f.state().status)
        f.hub.disable("handle-1"); advanceTimeBy(120_000); runCurrent()
        assertEquals(DisableReason.User, f.state().disabledReason); assertEquals(1, f.links.size)
        assertEquals(ConnectionState.Disabled, f.state().connection)
    }
}
