package app.resound.digest

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import app.resound.capture.CaptureController
import app.resound.capture.CaptureState
import app.resound.transcribe.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** 每次会话独占麦克风；取消可以打断准备、录音和识别，不留下录音文件。 */
class AndroidTodoVoiceInput internal constructor(
    private val controller: CaptureController,
    private val scope: CoroutineScope,
    private val cacheDir: File,
    private val provider: AsrProvider,
    private val credential: () -> String,
    private val microphone: () -> TodoMicrophone = { AndroidTodoMicrophone() },
    private val pauseDelayMillis: Long = 500,
) : TodoVoiceInput {
    private class Session {
        val ready = CompletableDeferred<Boolean>()
        val stop = CompletableDeferred<Unit>()
        lateinit var recording: Deferred<ShortArray>
        var finishing: Job? = null
        var cancelled = false
    }
    private val lock = Any()
    private var session: Session? = null
    private val volume = MutableStateFlow(0f)
    override val level = volume.asStateFlow()

    override suspend fun start(): Boolean {
        val active = synchronized(lock) {
            if (session != null) return false
            Session().also { s ->
                s.recording = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) { record(s) }
                session = s
            }
        }
        active.recording.start()
        return try {
            // 父作用域取消也会唤醒 start，不会永久停在 ready。
            active.recording.invokeOnCompletion { active.ready.complete(false) }
            active.ready.await().also { if (!it) clear(active) }
        } catch (e: CancellationException) {
            cancelSession(active)
            throw e
        }
    }

    private suspend fun record(active: Session): ShortArray {
        val resume = controller.status.value.state in setOf(CaptureState.Listening, CaptureState.Recording, CaptureState.MicBusy)
        var mic: TodoMicrophone? = null
        try {
            if (resume) { controller.pause(2); delay(pauseDelayMillis) }
            currentCoroutineContext().ensureActive()
            mic = microphone()
            mic.open()
            active.ready.complete(true)
            val samples = ShortArray(60 * 16_000)
            var size = 0
            val started = System.nanoTime()
            var updated = started
            while (!active.stop.isCompleted && size < samples.size && System.nanoTime() - started < 60_000_000_000L) {
                currentCoroutineContext().ensureActive()
                val count = mic.read(samples, size, minOf(1280, samples.size - size))
                check(count >= 0)
                val tick = System.nanoTime()
                if (tick - updated >= 80_000_000L) {
                    volume.value = if (count == 0) 0f else sqrt((size until size + count).sumOf {
                        val v = samples[it] / 32768.0; v * v
                    } / count).toFloat().coerceIn(0f, 1f)
                    updated = tick
                }
                size += count
                delay(10)
            }
            return samples.copyOf(size)
        } finally {
            withContext(NonCancellable) {
                try { mic?.close() } finally {
                    volume.value = 0f
                    if (resume && controller.status.value.state !in setOf(CaptureState.Stopped, CaptureState.NoPermission)) controller.resume()
                }
            }
        }
    }

    override suspend fun finish(): VoiceNoteResult {
        val caller = currentCoroutineContext().job
        val active = synchronized(lock) {
            val s = session ?: return VoiceNoteResult.Empty
            if (s.cancelled || s.finishing != null) return VoiceNoteResult.Empty
            s.finishing = caller
            s.stop.complete(Unit)
            s
        }
        var file: File? = null
        return try {
            val samples = active.recording.await() // 麦克风在 record 的 finally 释放并恢复日常录音。
            if (samples.size < 16_000) return VoiceNoteResult.Empty
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                val wav = File.createTempFile("todo-voice-", ".wav", cacheDir).also { file = it }
                writeTodoWav(wav, samples)
                val result = provider.recognize(AsrAudio(wav, "wav", samples.size * 1000L / 16_000, separateSpeakers = false), credential()) {}
                val text = result.sentences.joinToString("\n") { it.text.trim() }.trim()
                if (text.isEmpty()) VoiceNoteResult.Empty else VoiceNoteResult.Text(text)
            }
        } catch (_: TimeoutCancellationException) { VoiceNoteResult.Failed("识别超时，没能完成") }
        catch (e: CancellationException) { throw e }
        catch (e: AsrException) { VoiceNoteResult.Failed(voiceFailure(e.type)) }
        catch (_: Exception) { VoiceNoteResult.Failed("没能完成语音识别") }
        finally {
            withContext(NonCancellable + Dispatchers.IO) {
                active.recording.cancelAndJoin()
                file?.delete()
                clear(active)
            }
        }
    }

    override fun cancel() { synchronized(lock) { session }?.let(::cancelSession) }

    private fun cancelSession(active: Session) {
        synchronized(lock) {
            active.cancelled = true
            active.recording.cancel()
            active.finishing?.cancel()
        }
        scope.launch(NonCancellable) {
            active.recording.join()
            active.finishing?.join()
            clear(active)
        }
    }

    private fun clear(active: Session) = synchronized(lock) { if (session === active) session = null }
}

internal interface TodoMicrophone : AutoCloseable {
    fun open()
    fun read(target: ShortArray, offset: Int, count: Int): Int
}

private class AndroidTodoMicrophone : TodoMicrophone {
    private var recorder: AudioRecord? = null
    @android.annotation.SuppressLint("MissingPermission")
    override fun open() {
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0)
        val active = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 32000)).also { recorder = it }
        check(active.state == AudioRecord.STATE_INITIALIZED)
        app.resound.audio.OwnRecordingSessions.add(active.audioSessionId)
        active.startRecording()
        check(active.recordingState == AudioRecord.RECORDSTATE_RECORDING)
        check(active.activeRecordingConfiguration?.isClientSilenced != true)
    }
    override fun read(target: ShortArray, offset: Int, count: Int) =
        recorder!!.read(target, offset, count, AudioRecord.READ_NON_BLOCKING)
    override fun close() {
        val active = recorder ?: return
        recorder = null
        try { runCatching { active.stop() } } finally { active.release() }
    }
}

internal fun writeTodoWav(file: File, samples: ShortArray) {
    val bytes = samples.size * 2
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray()).putInt(16)
        .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(bytes).array()
    file.outputStream().buffered().use { output ->
        output.write(header)
        samples.forEach { output.write(it.toInt() and 255); output.write(it.toInt() shr 8 and 255) }
    }
}

private fun voiceFailure(error: AsrError): String = when (error) {
    AsrError.Network -> "网络不通，没能识别"
    AsrError.ModelMissing -> "还没下载本机转写模型"
    AsrError.Auth -> "密钥无效或未开通，没能识别"
    AsrError.Timeout -> "识别超时，没能完成"
    AsrError.Server -> "识别服务暂时不可用"
    AsrError.Audio -> "没能读取这次录音"
    AsrError.Storage -> "没能保存临时录音"
    AsrError.Protocol -> "没能读懂识别结果"
    AsrError.LocalEngine, AsrError.Speakers -> "本机识别失败"
    AsrError.Interrupted -> "语音识别已中断"
}
