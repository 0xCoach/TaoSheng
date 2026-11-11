package app.resound.devices.card

import app.resound.devices.*
import app.resound.devices.core.*
import app.resound.devices.driver.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/** 每个连接独占拆包/解码状态。通知、探测、对时、开始命令仍按旧录音链路的顺序执行。 */
internal class CardLiveAudio(
    private val card: DeviceCard,
    private val session: DriverSession,
    private val protocol: LiveProtocol,
    private val battery: (Int) -> Unit,
    private val report: (LinkDiagnostics) -> Unit,
    private val failed: (DeviceException) -> Unit,
    private val readTimeout: Long,
    private val zeroLimitMillis: Long,
) : LiveAudio {
    private val link get() = session.link
    private val enabled = MutableStateFlow(false)
    private val collector = MutableStateFlow<Job?>(null)
    override suspend fun start() { enabled.value = true }
    override suspend fun stop() {
        enabled.value = false
        collector.getAndUpdate { null }?.cancelAndJoin()
    }
    // Flow 按订阅启动，通知先于开始命令；宿主在 start 后立即收集且仅允许一个收集器。
    override val frames: Flow<AudioChunk> = flow {
        check(enabled.value) { "LiveAudio.start required" }
        coroutineScope {
            val owner = currentCoroutineContext().job
            if (!collector.compareAndSet(null, owner)) throw DeviceException(ErrorCode.BusyRetry)
            try { emitAll(stream()) } finally { collector.compareAndSet(owner, null) }
        }
    }
    private fun stream(): Flow<AudioChunk> = flow {
        val audio = requireNotNull(card.audio) { "说明卡没有实时声音通道" }
        val frames = FrameCodec(audio.frame)
        val framing = NotificationFrames(audio.frame)
        var decoder: AudioDecoder? = null
        var failures = 0L
        var started = false
        suspend fun command(cmd: CardCommand) = link.write(audio.service, requireNotNull(audio.write), frames.encode(cmd), protocol.withResponse)
        fun diagnostics() {
            val d = frames.diagnostics
            val stats = LinkDiagnostics(d.received, d.lost, d.crcFailures, failures)
            session.log(LogLevel.Info, "received=${d.received} lost=${d.lost} crc=${d.crcFailures} decode=$failures")
            report(stats)
        }
        try {
            coroutineScope {
                val packets = Channel<ByteArray>(128)
                val receiver = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { link.notifications(audio.service, audio.notify).collect {
                        // 不无限缓存声音，也不静默丢包。慢消费者按故障重连。
                        if (!packets.trySend(it).isSuccess) throw DeviceException(ErrorCode.Disconnected, "audio-overflow")
                    } } finally { packets.close() }
                }
                var batteryRead: Job? = null
                try {
                    if (audio.frame.fields.any { it.endian == Endian.Probe }) {
                        for (probe in frames.probeCommands()) {
                            link.write(audio.service, requireNotNull(audio.write), probe, protocol.withResponse)
                            val locked = withTimeoutOrNull(1500) {
                                while (frames.lockedEndian == null) {
                                    framing.accept(packets.receive(), frames).forEach { frames.probe(it) }
                                }
                                true
                            } == true
                            if (locked) break
                        }
                        check(frames.lockedEndian != null) { "设备协议端序探测失败" }
                    }
                    val codec = audio.codec ?: run {
                        val bytes = link.read(audio.service, requireNotNull(audio.codecRead))
                        require(bytes.size == 1) { "设备声音格式编号无效" }
                        requireNotNull(audio.codecMap[bytes[0].toInt() and 255]) { "设备声音格式不支持" }
                    }
                    val decoding = AudioDecoder(codec, audio.sampleRate, audio.channels, audio.frame.opus,
                        if (codec == Codec.Opus10 || codec == Codec.Opus20) object : app.resound.devices.core.OpusDecoder {
                            private var delegate = session.toolbox.opusDecoder(16000, audio.channels)
                            override fun decode(packet: ByteArray) = listOf(DecodedPcm(delegate.decode(packet), 16000, audio.channels))
                            override fun reset() { delegate.close(); delegate = session.toolbox.opusDecoder(16000, audio.channels) }
                            override fun close() = delegate.close()
                        } else null)
                    decoder = decoding
                    // 约定只有写时钟，没有读旧时钟。只记录本次成功写入后的量化偏差，不能冒充断开期间的旧偏差。
                    val offset = card.clock?.let {
                        val now = session.clock.nowEpochMillis()
                        link.write(it.service, it.write, clockBytes(it.format, now))
                        now % 1000
                    }
                    audio.start?.let { started = true; command(it) }
                    (link as? CardLink)?.ready?.invoke(offset)
                    var zeroSince: Long? = null
                    var pendingLost = 0L
                    var samplesPerPacket = if (codec == Codec.Opus10) 160L else if (codec == Codec.Opus20) 320L else 0L
                    var lastFrame = session.clock.monotonicMillis()
                    var heartbeatAt = session.clock.monotonicMillis()
                    var batteryAt = session.clock.monotonicMillis() - 300_000
                    var reportAt = session.clock.monotonicMillis()
                    while (true) {
                        val bytes = withTimeoutOrNull(250) { packets.receive() }
                        if (bytes != null) for (packet in framing.accept(bytes, frames)) {
                            val before = frames.diagnostics
                            val frame = frames.accept(packet)
                            val after = frames.diagnostics
                            pendingLost = (pendingLost + (after.lost - before.lost) * samplesPerPacket).coerceAtMost(Int.MAX_VALUE.toLong())
                            if (after.lost != before.lost || after.discarded != before.discarded) decoding.reset()
                            if (frame == null) continue
                            protocol.battery(frame)?.takeIf { it in 0..100 }?.let(battery)
                            val payload = protocol.audio(frame) ?: continue
                            samplesPerPacket = when (codec) {
                                Codec.Opus10, Codec.Opus20 -> {
                                    val count = if (audio.frame.opus == OpusPacking.One) 1 else opusPacketCount(payload)
                                    count * if (codec == Codec.Opus10) 160L else 320L
                                }
                                else -> payload.size.toLong() * 16000 / audio.sampleRate / audio.channels / if (codec == Codec.Pcm16) 2 else 1
                            }
                            val pcm = try { decoding.accept(payload) } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                failures++; decoding.reset(); emptyList()
                            }
                            for (samples in pcm) {
                                lastFrame = session.clock.monotonicMillis()
                                if (samples.any { it.toInt() != 0 }) zeroSince = null
                                else {
                                    val since = zeroSince ?: lastFrame.also { zeroSince = it }
                                    if (lastFrame - since >= zeroLimitMillis) throw DeviceException(ErrorCode.Disconnected, "zero-pcm")
                                }
                                emit(AudioChunk(samples, lostSamplesBefore = pendingLost.toInt()))
                                pendingLost = 0
                            }
                        }
                        val now = session.clock.monotonicMillis()
                        if (now - lastFrame >= readTimeout) throw DeviceException(ErrorCode.Disconnected, "audio-stalled")
                        if (card.link.keepAliveSec > 0 && now - heartbeatAt >= card.link.keepAliveSec * 1000L) {
                            command(requireNotNull(card.link.keepAlive)); heartbeatAt = now
                        }
                        if (now - batteryAt >= 300_000) {
                            batteryAt = now
                            protocol.batteryQuery()?.let { command(it) }
                            card.battery?.let { spec ->
                                batteryRead?.cancelAndJoin()
                                batteryRead = launch {
                                    try {
                                        val value = link.read(if (spec.standard) BATTERY_SERVICE else requireNotNull(spec.service),
                                            if (spec.standard) BATTERY_LEVEL else requireNotNull(spec.read))
                                        value.singleOrNull()?.toInt()?.and(255)?.takeIf { it in 0..100 }?.let(battery)
                                    } catch (e: CancellationException) { throw e }
                                    catch (_: Exception) { /* 可选电量服务不存在不阻止收音；链路故障仍由通知传播。 */ }
                                }
                            }
                        }
                        if (now - reportAt >= 300_000) { diagnostics(); reportAt = now }
                    }
                } finally { batteryRead?.cancelAndJoin(); receiver.cancelAndJoin(); packets.cancel() }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val failure = e as? DeviceException ?: DeviceException(ErrorCode.ProtocolError, cause = e)
            (link as? CardLink)?.failed(failure)
            failed(failure)
            throw failure
        }
        finally {
            withContext(NonCancellable) { if (started) withTimeoutOrNull(1500) { runCatching { audio.stop?.let { command(it) } } } }
            frames.discardFragments(); decoder?.close(); diagnostics()
        }

    }
    private fun opusPacketCount(bytes: ByteArray): Int {
        var offset = 0
        var count = 0
        while (offset < bytes.size) {
            val size = bytes[offset++].toInt() and 255
            if (size == 0 || size > bytes.size - offset) return 0
            offset += size
            count++
        }
        return count
    }
    companion object {
        const val BATTERY_SERVICE = "0000180f-0000-1000-8000-00805f9b34fb"
        const val BATTERY_LEVEL = "00002a19-0000-1000-8000-00805f9b34fb"
    }
}
