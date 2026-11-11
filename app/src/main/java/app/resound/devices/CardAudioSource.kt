package app.resound.devices

import app.resound.audio.*
import app.resound.devices.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneOffset

/** 品牌协议只在插件目录实现，核心不猜消息头。默认每个 notify 是一个完整包。 */
internal interface LiveProtocol {
    fun audio(frame: DecodedFrame): ByteArray? = frame.payload
    fun battery(frame: DecodedFrame): Int? = null
    fun batteryQuery(): CardCommand? = null
    val withResponse: Boolean get() = true
}
internal data class LinkDiagnostics(val received: Long, val lost: Long, val crcFailures: Long, val decodeFailures: Long)
internal interface LinkedDevice : DeviceLink {
    val disconnectedAt: Long? get() = null
    /** Some firmwares cannot read stored files while supplying realtime sound. */
    val backfillBeforeLive: Boolean get() = false
    suspend fun close()
}

/** 连接由宿主持有；取消先 stop，宿主随后 close。所有拆包/解码/命令组装在本协程串行进行。 */
internal class CardAudioSource(
    override val id: String,
    override val displayName: String,
    private val card: DeviceCard,
    private val link: DeviceLink,
    private val protocol: LiveProtocol = object : LiveProtocol {},
    private val opus: (Codec, Int) -> OpusDecoder = { codec, channels -> AndroidOpusDecoder(codec, channels) },
    private val wallTime: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
    private val ready: suspend (Long?) -> Unit = {},
    private val battery: (Int) -> Unit = {},
    private val report: (LinkDiagnostics) -> Unit = {},
    private val readTimeout: Long = CaptureHealth.STALL_MS,
) : AudioSource {
    override fun events(): Flow<AudioEvent> = flow {
        val audio = requireNotNull(card.audio) { "说明卡没有实时声音通道" }
        val frames = FrameCodec(audio.frame)
        val framing = NotificationFrames(audio.frame)
        var decoder: AudioDecoder? = null
        var failures = 0L
        var started = false
        suspend fun command(cmd: CardCommand) = link.write(audio.service, requireNotNull(audio.write), frames.encode(cmd), protocol.withResponse)
        fun diagnostics() { val d = frames.diagnostics; report(LinkDiagnostics(d.received, d.lost, d.crcFailures, failures)) }
        try {
            coroutineScope {
                val packets = Channel<ByteArray>(128)
                val receiver = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { link.notifications(audio.service, audio.notify).collect {
                        // 不无限缓存声音，也不静默丢包。慢消费者按故障重连。
                        if (!packets.trySend(it).isSuccess) throw AudioSourceException(AudioSourceException.Reason.Disconnected, "设备声音处理过慢")
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
                        if (codec == Codec.Opus10 || codec == Codec.Opus20) opus(codec, audio.channels) else null)
                    decoder = decoding
                    // 约定只有写时钟，没有读旧时钟。只记录本次成功写入后的量化偏差，不能冒充断开期间的旧偏差。
                    val offset = card.clock?.let {
                        val now = wallTime()
                        link.write(it.service, it.write, clockBytes(it.format, now))
                        now % 1000
                    }
                    audio.start?.let { started = true; command(it) }
                    ready(offset)
                    val zeros = CaptureHealth.ZeroWatch()
                    var lastFrame = elapsed()
                    var heartbeatAt = elapsed()
                    var batteryAt = elapsed() - 300_000
                    var reportAt = elapsed()
                    while (true) {
                        val bytes = withTimeoutOrNull(250) { packets.receive() }
                        if (bytes != null) for (packet in framing.accept(bytes, frames)) {
                            val before = frames.diagnostics
                            val frame = frames.accept(packet)
                            val after = frames.diagnostics
                            if (after.lost != before.lost || after.discarded != before.discarded) decoding.reset()
                            if (frame == null) continue
                            protocol.battery(frame)?.takeIf { it in 0..100 }?.let(battery)
                            val payload = protocol.audio(frame) ?: continue
                            val pcm = try { decoding.accept(payload) } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                failures++; decoding.reset(); emptyList()
                            }
                            for (samples in pcm) {
                                lastFrame = elapsed()
                                if (zeros.accept(samples, samples.size, lastFrame))
                                    throw AudioSourceException(AudioSourceException.Reason.Disconnected, CaptureHealth.ZERO_PCM)
                                emit(AudioEvent.Frame(samples))
                            }
                        }
                        val now = elapsed()
                        if (now - lastFrame >= readTimeout) throw AudioSourceException(AudioSourceException.Reason.Disconnected, "设备没有送来有效声音")
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
        catch (e: AudioSourceException) { throw e }
        catch (_: SecurityException) { throw AudioSourceException(AudioSourceException.Reason.NoPermission, "缺少蓝牙连接权限") }
        catch (_: Exception) { throw AudioSourceException(AudioSourceException.Reason.Disconnected, "设备连接或声音协议失败") }
        finally {
            withContext(NonCancellable) { if (started) withTimeoutOrNull(1500) { runCatching { audio.stop?.let { command(it) } } } }
            frames.discardFragments(); decoder?.close(); diagnostics()
        }
    }
    companion object {
        const val BATTERY_SERVICE = "0000180f-0000-1000-8000-00805f9b34fb"
        const val BATTERY_LEVEL = "00002a19-0000-1000-8000-00805f9b34fb"
    }
}

internal fun clockBytes(format: ClockFormat, now: Long): ByteArray = when (format) {
    ClockFormat.Unix32Le, ClockFormat.Unix32Be -> ByteBuffer.allocate(4)
        .order(if (format == ClockFormat.Unix32Le) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN).putInt((now / 1000).toInt()).array()
    ClockFormat.Bcd -> Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).let { t ->
        listOf(t.year % 100, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second)
            .map { ((it / 10 shl 4) or (it % 10)).toByte() }.toByteArray()
    }
}
