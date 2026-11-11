package app.resound.devices.replay

import app.resound.devices.driver.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

sealed interface ReplayAction {
    /** 明确期望的帧数，避免依赖真实时间；不足时由 timeoutMillis 报超时。 */
    data class Live(val frames: Int) : ReplayAction { init { require(frames > 0) } }
    data class Files(val fromOffset: Long = 0) : ReplayAction { init { require(fromOffset >= 0) } }
}
data class ReplayError(val stage: String, val code: ErrorCode?, val detail: String)
data class ReplayedFile(val file: RecordingFile, val fromOffset: Long, val chunks: List<ByteArray>) {
    val bytes: Long get() = chunks.sumOf { it.size.toLong() }
}
data class ReplayResult(
    val frames: List<AudioChunk>,
    val files: List<RecordingFile>,
    val downloads: List<ReplayedFile>,
    val statuses: List<DeviceStatusModel>,
    val errors: List<ReplayError>,
) {
    val frameCount get() = frames.size
    val sampleCount get() = frames.sumOf { it.pcm.size.toLong() }
    val lostSamples get() = frames.sumOf { it.lostSamplesBefore.toLong() }
    /** 约定只提供丢失采样数；不能反推未知编码的 BLE 丢包数。 */
    val gapCount get() = frames.count { it.lostSamplesBefore > 0 }
}

/** 时钟、解码与加密显式注入；可在 runTest 内直接运行，不依赖 JVM 或测试库。 */
class ReplayHarness(private val toolbox: Toolbox, private val clock: ClockPort) {
    suspend fun run(
        driver: DeviceDriver, capture: String, action: ReplayAction,
        masks: List<WriteMask> = emptyList(), timeoutMillis: Long = 5_000,
    ): ReplayResult = coroutineScope {
        val runScope = this
        require(timeoutMillis > 0)
        val link = ReplayGattLink(capture, masks)
        require(link.header.driver == driver.meta.id) { "录包驱动 ${link.header.driver} 与 ${driver.meta.id} 不一致" }
        val frames = mutableListOf<AudioChunk>()
        var files = emptyList<RecordingFile>()
        val downloads = mutableListOf<ReplayedFile>()
        val statuses = mutableListOf<DeviceStatusModel>()
        val errors = mutableListOf<ReplayError>()
        val jobs = mutableListOf<Job>()
        val decoders = mutableListOf<OpusDecoder>()
        var connection: DriverConnection? = null
        var started = false
        var stage = "open"
        fun report(where: String, error: Exception) {
            if (error is CancellationException) throw error
            errors += ReplayError(where, (error as? DeviceException)?.code, error.message.orEmpty())
        }
        val session = object : DriverSession {
            override val link = link
            override val clock = this@ReplayHarness.clock
            override val extras = object : PlatformExtras {}
            override val store = MemoryReplayStore()
            override val toolbox = object : Toolbox {
                override val crypto get() = this@ReplayHarness.toolbox.crypto
                override fun opusDecoder(sampleRate: Int, channels: Int): OpusDecoder {
                    val delegate = this@ReplayHarness.toolbox.opusDecoder(sampleRate, channels)
                    var closed = false
                    return object : OpusDecoder {
                        override fun decode(packet: ByteArray) = delegate.decode(packet)
                        override fun close() { if (!closed) { closed = true; delegate.close() } }
                    }.also { decoders += it }
                }
            }
            override fun schedule(name: String, everyMillis: Long, task: suspend () -> Unit): ScheduledTask {
                val job = launch {
                    while (isActive) {
                        delay(everyMillis.coerceAtLeast(30_000))
                        try { task() } catch (e: Exception) { report("schedule:$name", e) }
                    }
                }
                jobs += job
                return object : ScheduledTask { override fun cancel() = job.cancel() }
            }
            override fun log(level: LogLevel, message: String) = Unit
        }
        suspend fun cleanup(where: String, action: suspend () -> Unit) {
            try {
                if (withTimeoutOrNull(timeoutMillis) { action(); true } == null)
                    errors += ReplayError(where, ErrorCode.Timeout, "清理超时")
            } catch (e: Exception) { report(where, e) }
        }
        try {
            val completed = withTimeoutOrNull(timeoutMillis) {
                try {
                    val opened = driver.open(session).also { connection = it }
                    jobs += runScope.launch(start = CoroutineStart.UNDISPATCHED) { opened.status.collect { statuses += it } }
                    jobs += runScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        try { opened.closed.collect { errors += ReplayError("closed", it.code, it.detail) } }
                        catch (e: Exception) { report("closed", e) }
                    }
                    when (action) {
                        is ReplayAction.Live -> {
                            stage = "live"
                            val live = opened.live ?: throw DeviceException(ErrorCode.Unsupported, "驱动没有实时声音")
                            coroutineScope {
                                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                                    try { live.frames.take(action.frames).collect { frames += it.copy(pcm = it.pcm.copyOf()) } }
                                    catch (e: Exception) { report("frames", e) }
                                }
                                started = true
                                live.start()
                                collector.join()
                                if (frames.size != action.frames && errors.isEmpty())
                                    errors += ReplayError("frames", ErrorCode.ProtocolError, "期望 ${action.frames} 帧，实际 ${frames.size} 帧")
                            }
                            live.stop(); started = false
                        }
                        is ReplayAction.Files -> {
                            stage = "files"
                            val source = opened.files ?: throw DeviceException(ErrorCode.Unsupported, "驱动没有文件能力")
                            files = source.list()
                            if (action.fromOffset != 0L && !source.caps.resume)
                                throw DeviceException(ErrorCode.Unsupported, "驱动不支持续传")
                            files.forEach { file ->
                                val chunks = mutableListOf<ByteArray>()
                                // 即使中途失败，也保留已取得的数据供断言。
                                downloads += ReplayedFile(file, action.fromOffset, chunks)
                                source.read(file, action.fromOffset).collect { chunks += it.copyOf() }
                            }
                        }
                    }
                } catch (e: Exception) { report(stage, e) }
                true
            }
            if (completed == null) errors += ReplayError(stage, ErrorCode.Timeout, "回放超过 $timeoutMillis 毫秒")
        } finally {
            withContext(NonCancellable) {
                jobs.forEach { it.cancel() }; jobs.joinAll()
                try {
                    if (started) cleanup("stop") { connection?.live?.stop() }
                } finally {
                    try { cleanup("close") { connection?.close() } }
                    finally { link.close(); decoders.forEach { decoder -> cleanup("decoder.close") { decoder.close() } } }
                }
            }
        }
        if (errors.isEmpty()) link.assertExhausted()
        ReplayResult(frames.toList(), files, downloads.toList(), statuses.toList(), errors.toList())
    }
}

private class MemoryReplayStore : DriverStore {
    private val strings = mutableMapOf<String, String>()
    private val bytes = mutableMapOf<String, ByteArray>()
    override fun getString(key: String) = strings[key]
    override fun putString(key: String, value: String?) { if (value == null) strings.remove(key) else strings[key] = value }
    override fun getBytes(key: String) = bytes[key]?.copyOf()
    override fun putBytes(key: String, value: ByteArray?) { if (value == null) bytes.remove(key) else bytes[key] = value.copyOf() }
    override fun keys() = strings.keys + bytes.keys
}
