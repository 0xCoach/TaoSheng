package app.resound.capture

/** 单调时钟计量真实打开的 AudioRecord，跨读取线程和采样线程共享。 */
class MicOpenClock(private val now: () -> Long) {
    private var openedAt: Long? = null
    private var total = 0L
    @Synchronized fun setOpen(open: Boolean) {
        if (open && openedAt == null) openedAt = now()
        if (!open) { openedAt?.let { total += (now() - it).coerceAtLeast(0) }; openedAt = null }
    }
    @Synchronized fun totalMillis(): Long = total + (openedAt?.let { (now() - it).coerceAtLeast(0) } ?: 0)
}
