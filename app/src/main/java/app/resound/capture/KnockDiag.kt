package app.resound.capture

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 背敲诊断计数（只记数字与状态，不含录音正文、密钥等个人数据）。
 * 供 R4.4 背敲验收量化：实际事件率、峰值数、各动作命中、冷却/隐私/授权过滤、识别延迟、
 * 传感器注册/注销次数。数据进程内存留，进程重启清零——验收期同进程内有效。
 */
object KnockDiag {
    val registerCount = AtomicInteger()
    val unregisterCount = AtomicInteger()
    val sensorEvents = AtomicLong()
    /** 采样窗口起点（elapsedRealtime）；hz = sensorEvents*1000/(now-windowStart)。 */
    @Volatile var windowStart = 0L
    val joltPeaks = AtomicInteger()
    /** 按敲数分桶的命中次数（2 敲/3 敲各自成功次数）。传感器线程写、主线程读，须并发安全。 */
    val firesByTaps = java.util.concurrent.ConcurrentHashMap<Int, AtomicInteger>()
    val cooldownFiltered get() = detectorCooldownFiltered
    @Volatile var detectorCooldownFiltered = 0
    val privacyBlocked = AtomicInteger()
    val authDenied = AtomicInteger()
    @Volatile var lastLatencyMs = -1L
    @Volatile var lastAction = "none"
    @Volatile var listening = false

    fun hz(nowMs: Long): Int {
        val span = nowMs - windowStart
        return if (span > 0) (sensorEvents.get() * 1000L / span).toInt() else 0
    }

    fun onFire(taps: Int) {
        firesByTaps.computeIfAbsent(taps) { AtomicInteger() }.incrementAndGet()
    }

    fun summary(nowMs: Long): String = buildString {
        append("reg=").append(registerCount.get()).append('/').append(unregisterCount.get())
        append(" listening=").append(listening)
        append(" events=").append(sensorEvents.get()).append(" hz=").append(hz(nowMs))
        append(" peaks=").append(joltPeaks.get())
        append(" fires=").append(firesByTaps.entries.joinToString(",") { "${it.key}t:${it.value.get()}" })
        append(" cooldown=").append(cooldownFiltered)
        append(" privacy=").append(privacyBlocked.get())
        append(" denied=").append(authDenied.get())
        append(" latency=").append(lastLatencyMs).append("ms")
        append(" last=").append(lastAction)
    }
}
