package app.resound.ui.transcript

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.resound.transcribe.TranscriptLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 点原话听原声：从点的那句开始，接着往下播（跨录音段自动接上）；正在播的那句给界面高亮。
 * 全 App 只有一个播放器。播放时暂停日常收音，暂停/停止就恢复，免得把原声又录进去。
 * 拔耳机、来电或别的 App 抢走声音时自动暂停；倍速只改快慢不改音调。
 */
object LinePlayer {
    /** 正在播（或暂停在）的那句；没在播为 null */
    var currentLineId by mutableStateOf<Long?>(null); private set
    var playing by mutableStateOf(false); private set
    /** 当前播到的绝对时间（毫秒时间戳），给播放条显示时刻 */
    var positionAt by mutableLongStateOf(0L); private set
    var speed by mutableFloatStateOf(1f); private set
    /** 每次新开始播放 +1，播放条据此重新弹出到最上层 */
    var session by mutableIntStateOf(0); private set
    /** 本次播放涉及的录音段（按顺序）和当前是第几段；多段时播放条显示“第 2/6 段”和“下一段” */
    var segmentOrder by mutableStateOf<List<Long>>(emptyList()); private set
    val segmentIndex get() = segmentOrder.indexOf(segmentId).coerceAtLeast(0)
    /** 本次播放的全部原话（不随播放缩短），给字幕和总进度用 */
    var allLines by mutableStateOf<List<TranscriptLine>>(emptyList()); private set
    internal var timeline by mutableStateOf<PlayTimeline?>(null); private set
    /** 总进度（毫秒，不含段间空隙） */
    val elapsed get() = timeline?.elapsed(currentSegment, positionAt) ?: 0L
    var source by mutableStateOf<PlaySource?>(null); private set
    private var currentSegment by mutableLongStateOf(0L)
    /** 给界面的一次性提示（如录音已过保留期）；界面显示后清空 */
    var notice by mutableStateOf<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private var queue: List<TranscriptLine> = emptyList()
    private var segmentStart = 0L
    private var segmentId = 0L
    private var context: Context? = null
    private var focus: AudioFocusRequest? = null
    private var captureHeld = false
    private var noisyRegistered = false

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (i.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause() }
    }

    val active get() = currentLineId != null

    /** 从 [lines] 里第 [index] 句开始往下播 */
    fun play(context: Context, lines: List<TranscriptLine>, index: Int, from: PlaySource? = null) {
        if (lines.isEmpty() || index !in lines.indices) return
        this.context = context.applicationContext
        queue = lines.drop(index).filter { it.segmentId > 0 }
        if (queue.isEmpty()) return
        allLines = queue; timeline = PlayTimeline(queue); source = from
        segmentOrder = queue.map { it.segmentId }.distinct()
        session++
        scope.launch { startAt(queue.first()) }
    }

    /** 点正在播的那句：暂停 / 继续 */
    fun toggle() { if (playing) pause() else resume() }

    // 暂停后等 3 秒再把麦克风还给日常收音：快速暂停/继续时不反复开关麦克风
    private var releaseLater: Job? = null

    fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
        playing = false; ticker?.cancel()
        releaseLater?.cancel(); releaseLater = scope.launch { delay(3_000); if (!playing) releaseCapture() }
    }

    fun resume() {
        val p = player ?: return
        if (!requestFocus()) return
        releaseLater?.cancel(); releaseLater = null
        holdCapture(); p.start(); if (speed != 1f) applySpeed(); playing = true; tick()
    }

    fun changeSpeed(value: Float) { speed = value; applySpeed() }

    /** 关闭播放条、离开页面、切到后台时调用 */
    fun stop() {
        ticker?.cancel(); ticker = null; releaseLater?.cancel(); releaseLater = null
        player?.runCatching { stop(); release() }; player = null
        playing = false; currentLineId = null; queue = emptyList(); segmentOrder = emptyList()
        allLines = emptyList(); timeline = null; source = null
        abandonFocus(); releaseCapture()
        if (noisyRegistered) { runCatching { context?.unregisterReceiver(noisy) }; noisyRegistered = false }
    }

    /** 拖进度条：fraction 0..1 → 对应段的对应时刻（可往前也可往后） */
    fun seekFraction(fraction: Float) {
        val t = timeline ?: return
        val (seg, at) = t.locate((fraction.coerceIn(0f, 1f) * t.total).toLong())
        if (seg == segmentId && player != null) {
            player?.seekTo((at - segmentStart).coerceAtLeast(0).toLong(), MediaPlayer.SEEK_CLOSEST); positionAt = at
            allLines.lastOrNull { it.segmentId == seg && it.startAt <= at + 120 }?.let { currentLineId = it.id }
            return
        }
        // 换到别的段：从那一段起重新排队
        val startIdx = allLines.indexOfFirst { it.segmentId == seg }.coerceAtLeast(0)
        queue = allLines.drop(startIdx)
        val line = queue.lastOrNull { it.segmentId == seg && it.startAt <= at } ?: queue.first()
        scope.launch { startAt(line, at) }
    }

    /** 往前 / 往后若干毫秒（−15 / +15） */
    fun skipBy(ms: Long) {
        val t = timeline ?: return
        seekFraction(((elapsed + ms).toFloat() / t.total))
    }

    private suspend fun startAt(line: TranscriptLine, atAbs: Long? = null) {
        val audio = if (app.resound.AppGraph.isOriginalAudioReady) runCatching { app.resound.AppGraph.originalAudio.locate(line.segmentId) }.getOrNull() else null
        if (audio == null || !audio.file.exists()) {
            // 这一段没有原声（过期或语音输入）：跳到下一段；一段能播的都没有才提示
            val next = queue.dropWhile { it.segmentId == line.segmentId }.firstOrNull()
            if (next == null) { notice = "这段录音已过保留期，只剩文字"; stop(); return }
            queue = queue.dropWhile { it.segmentId == line.segmentId }; startAt(next); return
        }
        player?.runCatching { release() }
        val ctx = context ?: return
        if (!requestFocus()) { notice = "现在无法播放声音"; return }
        if (!noisyRegistered) runCatching {
            androidx.core.content.ContextCompat.registerReceiver(ctx, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            noisyRegistered = true
        }
        holdCapture()
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            p.setDataSource(audio.file.absolutePath)
            p.prepare()
            p.seekTo(((atAbs ?: line.startAt) - audio.startAt).coerceAtLeast(0).toLong(), MediaPlayer.SEEK_CLOSEST)
            p.setOnCompletionListener { scope.launch { nextSegment() } }
            p.start()
        } catch (_: Exception) {
            p.release(); notice = "这段原声打不开"; stop(); return
        }
        player = p; segmentStart = audio.startAt; segmentId = line.segmentId; currentSegment = line.segmentId
        currentLineId = line.id; positionAt = atAbs ?: line.startAt; playing = true
        if (speed != 1f) applySpeed(); tick()
    }

    /** 播放条上的“下一段” */
    fun skipSegment() { scope.launch { nextSegment() } }

    private suspend fun nextSegment() {
        val next = queue.dropWhile { it.segmentId == segmentId }.firstOrNull()
        if (next == null) { stop(); return }
        queue = queue.dropWhile { it.segmentId == segmentId }
        startAt(next)
    }

    /** 每 150 毫秒对一次进度：正在播的那句 = 本段里开始时间不晚于当前位置的最后一句 */
    private fun tick() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val p = player ?: break
                val at = segmentStart + runCatching { p.currentPosition.toLong() }.getOrDefault(0L)
                positionAt = at
                queue.filter { it.segmentId == segmentId }.lastOrNull { it.startAt <= at + 120 }?.let { currentLineId = it.id }
                delay(150)
            }
        }
    }

    private fun applySpeed() {
        val p = player ?: return
        if (!p.isPlaying) return
        runCatching { p.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f) }
    }

    private fun requestFocus(): Boolean {
        val am = context?.getSystemService(AudioManager::class.java) ?: return false
        val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change -> if (change < 0) scope.launch { pause() } }.build().also { focus = it }
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        val am = context?.getSystemService(AudioManager::class.java) ?: return
        focus?.let { am.abandonAudioFocusRequest(it) }
    }

    private fun holdCapture() {
        if (captureHeld || !app.resound.AppGraph.isOriginalAudioReady) return
        app.resound.AppGraph.originalAudio.beginPlayback(); captureHeld = true
    }

    private fun releaseCapture() {
        if (!captureHeld) return
        if (app.resound.AppGraph.isOriginalAudioReady) app.resound.AppGraph.originalAudio.endPlayback()
        captureHeld = false
    }
}
