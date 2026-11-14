package app.resound.devices

import android.app.Application
import android.content.Context
import app.resound.audio.*
import app.resound.devices.android.*
import app.resound.devices.driver.*
import app.resound.devices.hub.*
import app.resound.devices.core.coveredFromSavedAudio
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE, application = Application::class)
class DeviceInputs143Test {
    private class Live : LiveAudio {
        val incoming = MutableSharedFlow<AudioChunk>(extraBufferCapacity = 8)
        val started = CompletableDeferred<Unit>()
        val receipts = mutableListOf<Pair<ChunkOrigin,Int>>()
        var finished: Boolean? = null
        override suspend fun start() { started.complete(Unit) }
        override suspend fun stop() {}
        override val frames = incoming.asSharedFlow()
        override fun processed(origin: ChunkOrigin, samples: Int) { receipts += origin to samples }
        override fun captureFinished(discarded: Boolean) { finished = discarded }
    }
    private class Driver(val audio: Live) : DeviceDriver {
        var refreshes = 0
        override val meta = DriverMeta("test-driver",DriverTier.Plugin,Stability.Trial,LocalizedText("测试"))
        override fun match(ad: Advertisement) = MatchConfidence.Certain
        override suspend fun open(session: DriverSession) = object : DriverConnection {
            override val identity = DeviceIdentity(DeviceId("test-identity"),IdScope.PlatformLocal)
            override val capabilities = setOf(Capability.LiveAudio)
            override val live = audio
            override val files: DeviceFiles = object : DeviceFiles {
                override val caps = app.resound.devices.driver.FileCaps(false,false,false,null,Reclaim.Never)
                override suspend fun list() = emptyList<RecordingFile>()
                override fun read(file:RecordingFile,fromOffset:Long) = emptyFlow<ByteArray>()
                override suspend fun confirm(file:RecordingFile,checksum:ByteArray?) {}
                override suspend fun delete(file:RecordingFile) {}
            }
            override val status = MutableStateFlow(DeviceStatusModel(listOf(
                BatteryPart(1,percent=70,charging=false,readAtMillis=12345),
                BatteryPart(2,percent=40,charging=true,readAtMillis=12345)),
                StorageInfo(totalBytes=1024,freeBytes=512),RecordingState.Stopped))
            override val controls = object : DeviceControls { override suspend fun refresh() { refreshes++ } }
            override val settings: DeviceSettings? = null
            override val policy = DriverPolicy(backfillBeforeLive = true)
            override val binding: DeviceBinding? = null
            override val closed = MutableSharedFlow<DeviceException>()
            override suspend fun close() {}
        }
    }
    @Test fun backfillPrecedesLiveAndEachQueuedFrameKeepsItsOwnMetadata() = runBlocking {
        val address = "AA:BB:CC:DD:EE:FF"; val key = DeviceInputs.deviceKey(address)
        val row = RememberedDevice(address,"test-driver","test",clockOffset=1000)
        val store = object : PairedDeviceStore {
            override fun read() = listOf(row)
            override fun write(devices:List<RememberedDevice>) {}
        }
        val cards = object : DeviceCards {
            override val cards = MutableStateFlow<List<InstalledCard>>(emptyList())
            override fun parse(json:String): Result<DeviceCard> = Result.failure(UnsupportedOperationException())
            override fun setEnabled(cardId:String,enabled:Boolean) {}
        }
        val transport = DeviceCard("test-driver","test",1,"0",CardStatus.Trial,Transport.Ble,CardMatch())
        val audio = Live(); val driver = Driver(audio); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var manualSyncs = 0
        var holdManual = false
        var manualCancelled = 0
        var manualFailure: DeviceException? = null
        val manualEntered = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val job = SupervisorJob(); val worker = CoroutineScope(coroutineContext + job)
        val link = object : HubLink, GattLink {
            override val gatt get() = this
            override val disconnected = MutableSharedFlow<DeviceException>()
            override fun maxWriteLength(withResponse:Boolean) = 200
            override suspend fun read(service:String,characteristic:String)=byteArrayOf()
            override suspend fun write(service:String,characteristic:String,bytes:ByteArray,withResponse:Boolean) {}
            override fun notifications(service:String,characteristic:String)=emptyFlow<ByteArray>()
            override suspend fun close() {}
        }
        val context=RuntimeEnvironment.getApplication()
        val prefs=context.getSharedPreferences("driver-143-test",Context.MODE_PRIVATE).apply { edit().clear().commit() }
        val repository = AndroidHubRepository(prefs)
        val inputs=DeviceInputs(cards,store,repository,{ _,_->link },object:Toolbox {
            override val crypto:CryptoPort get()=error("unused")
            override fun opusDecoder(sampleRate:Int,channels:Int):OpusDecoder=error("unused")
        },object:ClockPort {
            override fun nowEpochMillis()=System.currentTimeMillis()
            override fun monotonicMillis()=System.nanoTime()/1_000_000
            override fun zoneOffsetMinutes()=0
        },object:PlatformExtras {},{ emptyFlow() },{ _,_-> },{ DeviceIdentity(DeviceId("test-identity"),IdScope.PlatformLocal) },worker,
            registered=listOf(RegisteredDriver(driver,transport)), onDriverLinked={ _,_,_,manual,_->
                if (manual) {
                    manualSyncs++
                    manualFailure?.let { throw it }
                    if (holdManual) {
                        manualEntered.send(Unit)
                        try { awaitCancellation() } finally { manualCancelled++ }
                    }
                } else { entered.complete(Unit); release.await() }
            })
        try {
            inputs.withDevice(address) { it.controls.refresh() }
            assertEquals(1,driver.refreshes); assertFalse(audio.started.isCompleted)
            val selection=repository.selections().single()
            val cached=repository.store(selection.driverId,"id:${selection.deviceId!!.value}").getString("lastStatus")!!
            assertEquals(12345L,DeviceStatusCache.decode(cached)!!.batteries.first().readAtMillis)
            assertEquals(40,inputs.lastDeviceStatus.value[address]!!.batteries.last().percent)
            inputs.syncDevice(address)
            assertEquals(1,manualSyncs); assertFalse(audio.started.isCompleted)
            holdManual = true
            val page = launch { inputs.startSyncDevice(address); awaitCancellation() }
            withTimeout(3000) { manualEntered.receive() }
            page.cancelAndJoin()
            assertTrue(inputs.manualSyncStates.value[address]!!.running)
            inputs.startSyncDevice(address) // 重复点按不能创建另一个传输。
            delay(20); assertEquals(2, manualSyncs)
            inputs.pauseSyncDevice(address)
            withTimeout(3000) { inputs.manualSyncStates.first { it[address]?.running == false } }
            assertEquals(1, manualCancelled)
            assertNull(inputs.devices.value.single().backfill)
            inputs.startSyncDevice(address)
            withTimeout(3000) { manualEntered.receive() }
            inputs.pauseSyncDevice(address)
            withTimeout(3000) { inputs.manualSyncStates.first { it[address]?.running == false } }
            assertEquals(2, manualCancelled)
            holdManual = false
            manualFailure = DeviceException(ErrorCode.StateConflict)
            inputs.startSyncDevice(address)
            withTimeout(3000) { inputs.manualSyncStates.first { it[address]?.errorCode == ErrorCode.StateConflict } }
            assertFalse(inputs.manualSyncStates.value[address]!!.running)
            assertFalse(audio.started.isCompleted)
            manualFailure = null
            inputs.select(InputChoice.Device(key,"test")); inputs.recording(true)
            withTimeout(3000) { entered.await() }; assertFalse(audio.started.isCompleted)
            val managing = async { inputs.withDevice(address) { it.controls.refresh() } }
            delay(20); assertFalse(managing.isCompleted)
            release.complete(Unit); withTimeout(3000) { managing.await(); audio.started.await() }
            withTimeout(3000) { audio.incoming.subscriptionCount.first { it>0 } }
            audio.incoming.emit(AudioChunk(ShortArray(480)))
            withTimeout(3000) { inputs.devices.first { it.single().status == DeviceStatus.Live } }
            inputs.withDevice(address) { it.controls.refresh() }
            assertEquals(3,driver.refreshes)
            val source=inputs.audioSource(key); val timeline=source as AudioTimeline
            val received=mutableListOf<Pair<Long?,String?>>()
            val collector=launch(start=CoroutineStart.UNDISPATCHED) {
                source.events().take(3).collect { event ->
                    received += timeline.frameEpochMillis to timeline.frameArchiveId
                    delay(10)
                    timeline.processed((event as AudioEvent.Frame).samples.size)
                }
            }
            delay(20)
            audio.incoming.emit(AudioChunk(ShortArray(480),DeviceTime(epochMillis=1000),origin=ChunkOrigin("file-a",0)))
            audio.incoming.emit(AudioChunk(ShortArray(480),DeviceTime(epochMillis=1030),origin=ChunkOrigin("file-a",30)))
            audio.incoming.emit(AudioChunk(ShortArray(480)))
            withTimeout(3000) { collector.join() }; timeline.finished(false)
            assertEquals(listOf(2000L,2030L,null),received.map { it.first })
            assertTrue(received.take(2).all { it.second!!.startsWith("$key:file-a:") })
            assertNull(received.last().second)
            assertEquals(listOf(ChunkOrigin("file-a",0),ChunkOrigin("file-a",30)),audio.receipts.map { it.first })
            assertEquals(false,audio.finished)
            assertTrue(prefs.all.keys.any { it.contains("audioAnchor:") })
        } finally { job.cancelAndJoin() }
    }
    @Test fun recoveryOnlyClaimsSamplesSafeForEveryPossibleAnchorTime() {
        assertEquals(MillisRange(2020,2980),coveredFromSavedAudio(5000,1000,10000,10040,11010,12020,20))
        assertNull(coveredFromSavedAudio(5000,0,10000,10100,10010,10080,20))
    }
}
