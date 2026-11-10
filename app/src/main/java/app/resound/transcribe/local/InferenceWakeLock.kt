package app.resound.transcribe.local

import android.os.PowerManager
import android.os.SystemClock
import app.resound.capture.CaptureTuning

/** 每次推理独占一把锁；排队和模型空闲驻留期间不持锁。 */
internal class InferenceWakeLock(
    private val lock: PowerManager.WakeLock,
    private val recordHeld: (startedAt: Long, millis: Long) -> Unit = { _, _ -> },
) {
    private var closed = false
    private var acquiredAt: Long? = null
    private var startedAt = 0L
    private var limitMillis = 0L

    private @Synchronized fun acquire(timeoutMillis: Long): Boolean {
        if (closed) return false // 取消可能先于消费者开始执行。
        lock.setReferenceCounted(false)
        // JNI 不一定响应协程取消，必须同时设置系统自动释放上限。
        limitMillis = timeoutMillis.coerceIn(1L, MAX_MILLIS)
        lock.acquire(limitMillis)
        startedAt = System.currentTimeMillis()
        acquiredAt = SystemClock.elapsedRealtime()
        return true
    }

    @Synchronized fun release() {
        closed = true
        if (lock.isHeld) lock.release()
        acquiredAt?.let { at ->
            acquiredAt = null // IPC 取消、finally 和服务销毁只能记一次。
            recordHeld(startedAt, (SystemClock.elapsedRealtime() - at).coerceIn(0L, limitMillis))
        }
    }

    suspend fun run(timeoutMillis: Long, block: suspend () -> Unit) {
        try {
            if (acquire(timeoutMillis)) block()
        } finally { release() }
    }

    companion object {
        // 与既有客户端推理超时一致：最长录音的两倍处理时间 + 1 分钟准备余量。
        const val MAX_MILLIS = CaptureTuning.MAX_SEGMENT_SAMPLES * 1000L / CaptureTuning.SAMPLE_RATE * 2 + 60_000L
        fun timeoutMillis(durationMillis: Long) = maxOf(300_000L, 60_000L + durationMillis * 2)
    }
}
