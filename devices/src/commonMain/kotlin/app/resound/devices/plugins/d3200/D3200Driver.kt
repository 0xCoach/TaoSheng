package app.resound.devices.plugins.d3200

import app.resound.audio.PcmInputConverter
import app.resound.devices.driver.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.selects.select

/** 私有协议插件。平台提供连接、设备作用域存储与密码实现。 */
class D3200Driver : DeviceDriver {
    override val meta = DriverMeta("d3200", DriverTier.Plugin, Stability.Trial,
        LocalizedText("安克 / 飞书录音豆 D3200"), listOf("安克录音豆", "飞书录音豆"), maintainer = "xhp1211")
    private val observed = MutableStateFlow<Map<String, String>>(emptyMap())
    override fun match(ad: Advertisement): MatchConfidence {
        val matched = ad.services.any { it.equals(D3200Protocol.SERVICE, true) }
        if (matched) ad.manufacturerData.entries.firstNotNullOfOrNull { BeanLight.classicAddress(it.key, it.value) }?.let { address ->
            observed.update { it + (ad.platformHandle to address) }
        }
        return if (matched) MatchConfidence.Certain else MatchConfidence.None
    }
    override suspend fun open(session: DriverSession): DriverConnection {
        val handle = session.store.getString("platformHandle") ?: throw DeviceException(ErrorCode.BadArgs, "platform identity missing")
        observed.value[handle]?.let { session.store.putString("classicAddress", it) }
        val identity = session.store.getString("platformIdentity") ?: throw DeviceException(ErrorCode.BadArgs, "platform identity missing")
        val connection = D3200Connection(session, identity)
        try { connection.initialize(); return connection }
        catch (e: Exception) { withContext(NonCancellable) { connection.close() }; throw e }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class D3200Connection(private val session: DriverSession, platformIdentity: String) : DriverConnection {
    private val crypto = D3200Crypto(session.toolbox.crypto)
    private val commandLock = Mutex()
    private val operations = Mutex()
    private val infoLock = Mutex()
    private val stopped = MutableStateFlow(false)
    private val controlling = MutableStateFlow(false)
    private val mutableStatus = MutableStateFlow(DeviceStatusModel())
    private val failures = MutableSharedFlow<DeviceException>(replay = 1)
    private val infoState = MutableStateFlow(D3200DeviceInfo())
    private val info get() = infoState.value
    private var lightChannel: SerialChannel? = null
    // The connection owns these readers; neither receipt waits nor audio consumers
    // may compete for SerialChannel.incoming (a single-consumer stream on Android).
    private lateinit var readers: CoroutineScope
    private var serialReader: Job? = null
    private val frames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    private val lightReplies = MutableSharedFlow<ByteArray>(extraBufferCapacity = 4)
    private var heartbeat: ScheduledTask? = null
    private var lightId = 0
    override var identity = DeviceIdentity(DeviceId(platformIdentity), IdScope.PlatformLocal, "Anker", "D3200")
        private set
    override val capabilities = setOf(Capability.LiveAudio, Capability.Files, Capability.Battery, Capability.Storage,
        Capability.Record, Capability.Settings, Capability.Light, Capability.AndroidOnlyLight)
    override val status = mutableStatus.asStateFlow()
    override val closed = failures.asSharedFlow()
    override val binding: DeviceBinding? = null
    override val policy = DriverPolicy(backfillBeforeLive = true, newestFirst = true, exclusiveBackfill = true)

    private suspend fun <T> timed(millis: Long, block: suspend () -> T): T = try {
        withTimeout(millis) { block() }
    } catch (e: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw DeviceException(ErrorCode.Timeout, cause = e)
    }

    private fun rawFrames(): Flow<ByteArray> = frames
    private fun readFrames(source: Flow<ByteArray>): Job = readers.launch(start = CoroutineStart.UNDISPATCHED) {
        val parser = BeanLight.Decoder()
        try {
            source.collect { bytes ->
                parser.push(bytes).forEach { frame ->
                    if (BeanLight.qc(frame)) lightReplies.emit(frame)
                    else {
                        val cmd = D3200Protocol.command(frame)
                        if (cmd in setOf(0x0103, 0x0104, 0x1a06) || (cmd == 0x1882 && !controlling.value))
                            update(cmd, D3200Protocol.payload(frame))
                        frames.emit(frame)
                    }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { failures.emit(DeviceException(ErrorCode.Disconnected, "device receive failed", e)) }
    }
    private suspend fun closeSerial() {
        serialReader?.cancel()
        try { lightChannel?.close() }
        finally { serialReader?.join(); serialReader = null; lightChannel = null }
    }
    private suspend fun update(command: Int, payload: ByteArray) = infoLock.withLock {
        infoState.value = if (command == 0x0101) D3200DeviceInfo.parse(payload) else D3200DeviceInfo.pushed(command, payload, info)
        val now = session.clock.nowEpochMillis()
        mutableStatus.value = DeviceStatusModel(listOf(
            BatteryPart(1, LocalizedText("录音豆"), info.battery, info.charging, now),
            BatteryPart(2, LocalizedText("充电盒"), info.boxBattery, info.boxCharging, now)),
            if (info.totalKb != null || info.freeKb != null) StorageInfo(info.totalKb?.times(1024), info.freeKb?.times(1024)) else null,
            when (info.recording) { 0 -> RecordingState.Stopped; 1 -> RecordingState.Recording; 2 -> RecordingState.Paused; else -> null })
        val serialId = info.serial?.let { DeviceId("d3200:$it") }
        if (identity.scope == IdScope.Global && serialId != null && identity.id != serialId)
            throw DeviceException(ErrorCode.ProtocolError,"device identity changed")
        identity = identity.copy(id = serialId ?: identity.id, scope = if (serialId != null) IdScope.Global else identity.scope,
            firmware = listOfNotNull(info.firmware.takeIf { it.isNotBlank() }?.let { PartVersion(1,it) },
            info.boxFirmware.takeIf { it.isNotBlank() }?.let { PartVersion(2,it) }))
    }
    private suspend fun ask(request: ByteArray, expected: Int, file: Long? = null): ByteArray = commandLock.withLock {
        coroutineScope {
            val reply = async(start = CoroutineStart.UNDISPATCHED) {
                rawFrames().first { frame -> D3200Protocol.command(frame) == expected &&
                    (file == null || D3200Protocol.headerMatches(D3200Protocol.payload(frame), file)) }
            }
            try {
                session.link.write(D3200Protocol.SERVICE, D3200Protocol.WRITE, request)
                val frame = timed(8_000) { reply.await() }
                if (frame[4].toInt() and 15 != 1) throw DeviceException(ErrorCode.VendorError)
                D3200Protocol.payload(frame).also { if (expected == 0x0101) update(expected, it) }
            } finally { reply.cancel() }
        }
    }
    suspend fun initialize() {
        // open() runs in a short-lived boundary call. Readers instead live exactly
        // until connection.close(), which also handles failed initialization.
        readers = CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob())
        readFrames(session.link.notifications(D3200Protocol.SERVICE, D3200Protocol.NOTIFY))
        val response = ask(D3200Protocol.frame(1,1), 0x0101)
        if (response.size < 51 || info.recording == null) throw DeviceException(ErrorCode.ProtocolError, "device info incomplete")
        crypto.handshake(ask(D3200Protocol.frame(0x2e,1,crypto.publicKey()),0x2e01))
        val mode = session.store.getString("light")?.toIntOrNull() ?: 0
        if (mode != 0 && info.firmware.startsWith("04.92") && session.extras.classicSerial != null) controls.setLight(mode)
        session.schedule("status",30_000) {
            if (operations.tryLock()) try { refreshInternal() } finally { operations.unlock() }
        }
    }
    private suspend fun refreshInternal() { val p = ask(D3200Protocol.frame(1,1),0x0101); if (p.size < 51) throw DeviceException(ErrorCode.ProtocolError) }
    private suspend fun <T> interactive(block: suspend () -> T): T {
        if (!operations.tryLock()) throw DeviceException(ErrorCode.BusyRetry)
        try { return block() } finally { operations.unlock() }
    }
    override val controls = object : DeviceControls {
        override suspend fun refresh() = interactive { refreshInternal() }
        override suspend fun setRecording(target: RecordingState): RecordingState = interactive {
            val start = when(target) { RecordingState.Recording -> true; RecordingState.Paused -> false; else -> throw DeviceException(ErrorCode.Unsupported) }
            controlling.value = true
            try {
                if (!start) session.store.putString("hold", "true")
                refreshInternal()
                if (!D3200ControlPolicy.confirmed(info.recording,start)) {
                    ask(D3200Protocol.frame(0x18,0x82,byteArrayOf(if (start) 1 else 2)),0x1882)
                    var confirmed = false
                    repeat(6) { if (!confirmed) { delay(250); refreshInternal(); confirmed=D3200ControlPolicy.confirmed(info.recording,start) } }
                    if (!confirmed) throw DeviceException(ErrorCode.Timeout, "recording readback")
                }
                if (start) session.store.putString("hold", "false")
                requireNotNull(mutableStatus.value.recording)
            } finally { controlling.value = false }
        }
        override suspend fun lightModes(): List<LightMode> = buildList {
            add(LightMode(0, LightKind.Default, LocalizedText("默认")))
            if (info.firmware.startsWith("04.92") && session.extras.classicSerial != null) {
                add(LightMode(1, LightKind.Off, LocalizedText("关闭")))
                add(LightMode(2, LightKind.Preset, LocalizedText("暖橙常亮")))
            }
        }
        override suspend fun setLight(modeId: Int): LightMode = interactive {
            val mode = lightModes().firstOrNull { it.id == modeId } ?: throw DeviceException(ErrorCode.Unsupported)
            heartbeat?.cancel(); heartbeat = null
            if (modeId == 0) { closeSerial(); lightId = 0; session.store.putString("light", "0"); return@interactive mode }
            if (!info.firmware.startsWith("04.92")) throw DeviceException(ErrorCode.Unsupported)
            if (lightChannel == null) {
                ask(BeanLight.enableSpp(),0x1a0f)
                lightChannel = (session.extras.classicSerial ?: throw DeviceException(ErrorCode.Unsupported)).open(BeanLight.SPP)
                serialReader = readFrames(requireNotNull(lightChannel).incoming)
            }
            val channel = requireNotNull(lightChannel)
            suspend fun send() = coroutineScope {
                val receipt = async(start = CoroutineStart.UNDISPATCHED) {
                    lightReplies.mapNotNull(BeanLight::receipt).first()
                }
                try {
                    channel.send(BeanLight.command(modeId == 2))
                    if (!timed(3_000) { receipt.await() }) throw DeviceException(ErrorCode.VendorError)
                } finally { receipt.cancel() }
            }
            try { send() } catch (e: Exception) { closeSerial(); throw e }
            lightId = modeId
            session.store.putString("light",modeId.toString())
            heartbeat = session.keepAlive(2_000) {
                try { send() } catch (e: CancellationException) { throw e }
                catch (e: Exception) { failures.emit(DeviceException(ErrorCode.Disconnected, "light receipt failed", e)) }
            }
            mode
        }
    }
    override val settings = object : DeviceSettings {
        override suspend fun schema() = buildList {
            add(SettingItem("autoStart",SettingKind.Switch,LocalizedText("自动开始设备录音"),SettingValue.Bool(session.store.getString("autoStart")=="true")))
            add(SettingItem("autoSync",SettingKind.Switch,LocalizedText("自动补传"),SettingValue.Bool(session.store.getString("autoSync")!="false")))
            if (info.firmware.isNotBlank()) add(SettingItem("firmware",SettingKind.ReadOnly,LocalizedText("录音豆固件"),SettingValue.Text(info.firmware)))
            if (info.boxFirmware.isNotBlank()) add(SettingItem("boxFirmware",SettingKind.ReadOnly,LocalizedText("充电盒固件"),SettingValue.Text(info.boxFirmware)))
            if (info.firmware.startsWith("04.92") && session.extras.classicSerial != null)
                add(SettingItem("lightScope",SettingKind.ReadOnly,LocalizedText("灯控范围"),SettingValue.Text("连接期间生效；断开或重连时可能亮灯，并非永久设置")))
        }
        override suspend fun set(key: String,value: SettingValue): SettingValue {
            if (key !in setOf("autoStart","autoSync") || value !is SettingValue.Bool) throw DeviceException(ErrorCode.BadArgs)
            session.store.putString(key,value.value.toString()); return value
        }
    }
    private fun coverage(file: String): List<MillisRange> = session.store.getString("coverage:$file")?.split(';')?.mapNotNull { item ->
        val parts=item.split(':'); if (parts.size!=2) null else parts[0].toLongOrNull()?.let { a -> parts[1].toLongOrNull()?.let { b -> if (a<=b && a>=0) MillisRange(a,b) else null } }
    }.orEmpty()
    private fun saveCoverage(file: String, ranges: List<MillisRange>) {
        val merged = D3200HistoryCore.merge(ranges.map { D3200HistoryCore.Range(it.fromMillis,it.toMillis) })
        session.store.putString("coverage:$file",merged.joinToString(";") { "${it.from}:${it.until}" })
    }
    private val pendingCoverage = MutableStateFlow<Map<String,List<MillisRange>>>(emptyMap())
    override val live = object : LiveAudio {
        override suspend fun start() {
            stopped.value = false
            if (info.recording != 1 && D3200ControlPolicy.autoStart(session.store.getString("autoStart") == "true", session.store.getString("hold") == "true"))
                controls.setRecording(RecordingState.Recording)
        }
        override suspend fun stop() { stopped.value = true }
        override fun processed(origin: ChunkOrigin, samples: Int) {
            if (samples <= 0) return
            val end = origin.offsetMillis + samples.toLong()*1000/16000
            pendingCoverage.update { all ->
                val ranges = all[origin.fileId].orEmpty() + MillisRange(origin.offsetMillis,end)
                val merged = D3200HistoryCore.merge(ranges.map { D3200HistoryCore.Range(it.fromMillis,it.toMillis) })
                    .map { MillisRange(it.from,it.until) }
                all + (origin.fileId to merged)
            }
        }
        override fun captureFinished(discarded: Boolean) {
            val values = pendingCoverage.getAndUpdate { emptyMap() }
            if (!discarded) values.forEach { (file,ranges) -> saveCoverage(file,coverage(file)+ranges) }
        }
        override val frames: Flow<AudioChunk> = flow {
            if (info.recording != 1) {
                val started = coroutineScope {
                    val observer = launch(start = CoroutineStart.UNDISPATCHED) { rawFrames().collect() }
                    val ready = async { status.first { it.recording == RecordingState.Recording } }
                    val stop = async { stopped.first { it } }
                    try { select<Boolean> { ready.onAwait { true }; stop.onAwait { false } } }
                    finally { ready.cancel(); stop.cancel(); observer.cancelAndJoin() }
                }
                if (!started) return@flow
            }
            val listing = ask(D3200Protocol.frame(0x1a,0x0e,byteArrayOf(0,0)),0x1a0e)
            val file = D3200Protocol.currentFile(listing)
            if (file == 0L || info.recording != 1) throw DeviceException(ErrorCode.StateConflict, "device not recording")
            val epoch = D3200HistoryCore.epoch(file,session.clock.nowEpochMillis())
            val offset = liveOffset(listing,file)
            coroutineScope {
                val incoming = Channel<ByteArray>(128)
                val collector = launch(start = CoroutineStart.UNDISPATCHED) { try { rawFrames().collect { incoming.send(it) } } finally { incoming.close() } }
                val stop = async { stopped.first { it } }
                var decoder: OpusDecoder? = null
                try {
                    val header = ask(D3200Protocol.request(file,offset),0x1a07,file)
                    awaitHeader(incoming, header)
                    crypto.fileHeader(header,file)
                    val sequence = D3200Sequence(); var channels=0; var converter: PcmInputConverter?=null; var emitted=0L; var first: Long?=null
                    var anchor: Long? = null
                    var lastPcm = session.clock.monotonicMillis()
                    while (!stopped.value) {
                        val remaining = 12_000 - (session.clock.monotonicMillis() - lastPcm)
                        if (remaining <= 0) throw DeviceException(ErrorCode.Timeout, "live audio stalled")
                        val frame = timed(remaining) {
                            select<ByteArray?> {
                                incoming.onReceiveCatching { it.getOrThrow() }
                                stop.onAwait { null }
                            }
                        } ?: break
                        when (D3200Protocol.command(frame)) {
                            0x1a08,0x1a12 -> for (slice in D3200Protocol.slices(D3200Protocol.payload(frame))) {
                                require(slice.seq >= offset/166)
                                if (!sequence.accept(slice.seq)) continue
                                val packet=crypto.decrypt(slice)
                                val count=if (packet[0].toInt() and 4 != 0) 2 else 1
                                if (decoder == null) { channels=count; decoder=session.toolbox.opusDecoder(16_000,count); converter=PcmInputConverter(16_000,count) }
                                require(count==channels)
                                if (first==null) first=slice.seq
                                val now = session.clock.monotonicMillis()
                                if (anchor == null) anchor = now - (slice.seq - requireNotNull(first)) * 20
                                require(now - requireNotNull(anchor) - (slice.seq - requireNotNull(first)) * 20 < 4_000) { "live audio is behind device" }
                                val pcm=decoder!!.decode(packet)
                                for (out in converter!!.accept(pcm,pcm.size)) {
                                    val position=requireNotNull(first)*20 + emitted*1000/16000
                                    emit(AudioChunk(out,DeviceTime(epochMillis=epoch+position),origin=ChunkOrigin(file.toString(),position)))
                                    emitted += out.size
                                    lastPcm = session.clock.monotonicMillis()
                                }
                            }
                            0x1a0a -> throw DeviceException(ErrorCode.Disconnected, "recording saved")
                        }
                    }
                } finally { stop.cancel(); collector.cancelAndJoin(); incoming.cancel(); decoder?.close() }
            }
        }
    }
    override val files = object : DeviceFiles {
        override val caps = FileCaps(resume=true,confirm=false,delete=false,checksum=null,reclaim=Reclaim.Never)
        override suspend fun list(): List<RecordingFile> = operations.withLock {
            refreshInternal()
            if (info.recording != 0) throw DeviceException(ErrorCode.StateConflict, "stop and save recording first")
            val all=linkedMapOf<Long,D3200HistoryCore.FileEntry>(); val active=mutableSetOf<Long>(); var page=0
            while (true) {
                val result=D3200HistoryCore.page(ask(D3200Protocol.listPage(page),0x1b0e))
                if (result.current!=0L) active += result.current
                result.files.forEach { require(all.put(it.id,it)==null) }
                if (result.count<10) break
                require(++page<=65535)
            }
            all.values.filter { it.id !in active && it.durationMillis>0 }.sortedBy { it.id }.map {
                RecordingFile(it.id.toString(),FileState.Complete,it.durationMillis/20*160,AudioFileFormat.Opus20,
                    DeviceTime(epochMillis=D3200HistoryCore.epoch(it.id,session.clock.nowEpochMillis())),it.durationMillis,liveCovered=coverage(it.id.toString()))
            }
        }
        override fun read(file: RecordingFile,fromOffset: Long): Flow<ByteArray> = flow {
            operations.withLock {
                refreshInternal()
                if (info.recording!=0) throw DeviceException(ErrorCode.StateConflict, "stop and save recording first")
                val id=file.id.toLongOrNull() ?: throw DeviceException(ErrorCode.BadArgs)
                require(fromOffset>=0 && fromOffset%160==0L)
                coroutineScope {
                    val incoming=Channel<ByteArray>(128)
                    val collector=launch(start=CoroutineStart.UNDISPATCHED) { try { rawFrames().collect { incoming.send(it) } } finally { incoming.close() } }
                    try {
                        val request=D3200HistoryCore.resumeAt(fromOffset/160)
                        var header=ask(D3200Protocol.offlineRequest(id,request),0x1a07,id)
                        var busy=0
                        while (header.size>=87 && (header[86].toInt() and 255)==4 && busy++<3) { delay(1000); header=ask(D3200Protocol.offlineRequest(id,request),0x1a07,id) }
                        require(header.size>=87 && (header[86].toInt() and 255)==0)
                        awaitHeader(incoming, header)
                        crypto.fileHeader(header,id)
                        val total=D3200HistoryCore.packets(D3200Protocol.u32(header,4)); val cursor=D3200DownloadCursor(fromOffset,total)
                        val batch = ByteArray(8_000); var used = 0
                        var progressed = session.clock.monotonicMillis()
                        while (!cursor.complete) {
                            val remaining = 45_000 - (session.clock.monotonicMillis() - progressed)
                            if (remaining <= 0) throw DeviceException(ErrorCode.Timeout, "file download stalled")
                            val frame=timed(remaining) { incoming.receive() }
                            when (D3200Protocol.command(frame)) {
                                0x1a08,0x1a12 -> for (slice in D3200Protocol.slices(D3200Protocol.payload(frame))) {
                                    cursor.accept(slice.seq,crypto.decrypt(slice))?.let { packet ->
                                        packet.copyInto(batch,used); used += packet.size
                                        if (used == batch.size) { emit(batch.copyOf()); used = 0 }
                                    }
                                    progressed = session.clock.monotonicMillis()
                                }
                                0x1a0a -> throw DeviceException(ErrorCode.Disconnected,"incomplete transfer")
                            }
                        }
                        if (used > 0) emit(batch.copyOf(used))
                    } finally { collector.cancelAndJoin(); incoming.cancel() }
                }
            }
        }
        override suspend fun confirm(file: RecordingFile, checksum: ByteArray?) {}
        override suspend fun delete(file: RecordingFile) { throw DeviceException(ErrorCode.Unsupported) }
    }
    override suspend fun close() {
        heartbeat?.cancel(); stopped.value = true
        try { closeSerial() }
        finally { if (::readers.isInitialized) readers.coroutineContext.job.cancelAndJoin() }
    }
    private suspend fun awaitHeader(incoming: Channel<ByteArray>, header: ByteArray) {
        timed(8_000) {
            while (true) {
                val frame = incoming.receive()
                if (D3200Protocol.command(frame) == 0x1a07 && D3200Protocol.payload(frame).contentEquals(header)) break
            }
        }
    }
    companion object {
        fun liveOffset(p: ByteArray,file: Long): Long {
            val count=D3200Protocol.u16(p,0); val at=2+count*8
            require(at+8<=p.size)
            var ms=D3200Protocol.u32(p,at+4)
            for (i in 0 until count) if (D3200Protocol.u32(p,2+i*8)==file) ms=maxOf(ms,D3200Protocol.u32(p,6+i*8))
            require(ms<=7*24*3600*1000L)
            return (ms/20-50).coerceAtLeast(0)*166
        }
    }
}
