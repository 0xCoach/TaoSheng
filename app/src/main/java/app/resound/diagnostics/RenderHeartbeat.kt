package app.resound.diagnostics

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import java.io.File

/**
 * 渲染心跳取证（R4.4 黑屏冻结）：前台时每帧计数、每 30s 写一行心跳并强制 invalidate 一次。
 * 冻结现场读 files/diag/ui-heartbeat.log：
 * - 心跳行持续但 frames 不涨 → 有绘制请求却产不出帧（渲染线程/合成器侧死）
 * - 心跳行停 → 主线程事件循环都死了（主线程卡死）
 * - 最后一条 lc 行 → 冻结前最后经历的生命周期（折叠重建/前后台切换有迹可查）
 * 环形文件只留最近 200 行，无个人数据（时间/帧数/生命周期名/屏幕配置）。
 */
object RenderHeartbeat : Application.ActivityLifecycleCallbacks {
    private const val TICK_MS = 30_000L
    private const val KEEP_LINES = 200
    private var file: File? = null
    private var frames = 0
    private var forcedSinceLastTick = 0
    private var resumedActivity: Activity? = null
    private var foreground = 0
    private val handler = Handler(Looper.getMainLooper())
    private val choreo = Choreographer.getInstance()

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) { frames++; choreo.postFrameCallback(this) }
    }
    private val ticker = object : Runnable {
        override fun run() { tick(); handler.postDelayed(this, TICK_MS) }
    }

    fun install(app: Application) {
        file = File(app.filesDir, "diag/ui-heartbeat.log").apply { parentFile?.mkdirs() }
        app.registerActivityLifecycleCallbacks(this)
    }

    private fun cfg(a: Activity): String {
        val c = a.resources.configuration
        val m = a.resources.displayMetrics
        return "${m.widthPixels}x${m.heightPixels} sw${c.smallestScreenWidthDp} o${c.orientation}"
    }

    private fun log(line: String) {
        val f = file ?: return
        runCatching {
            val text = if (f.isFile) f.readText() else ""
            val lines = (text.lineSequence().toList() + line).takeLast(KEEP_LINES)
            f.writeText(lines.joinToString("\n") + "\n")
        }
    }

    private fun tick() {
        val a = resumedActivity ?: return
        log("hb ${System.currentTimeMillis()} frames=$frames forced=$forcedSinceLastTick cfg=${cfg(a)}")
        forcedSinceLastTick = 0
        // 强制产一帧：下一 tick 若 frames 仍不涨，渲染链路确认死亡。
        runCatching { a.window?.decorView?.postInvalidateOnAnimation(); forcedSinceLastTick++ }
    }

    override fun onActivityStarted(a: Activity) {
        foreground++
        if (foreground == 1) {
            choreo.postFrameCallback(frameCallback)
            handler.post(ticker)
        }
    }

    override fun onActivityStopped(a: Activity) {
        foreground--
        if (foreground == 0) {
            choreo.removeFrameCallback(frameCallback)
            handler.removeCallbacks(ticker)
            log("lc stopped frames=$frames")
        }
    }

    override fun onActivityCreated(a: Activity, s: Bundle?) { log("lc create ${a.localClassName} cfg=${cfg(a)}") }
    override fun onActivityResumed(a: Activity) { resumedActivity = a; log("lc resume ${a.localClassName} cfg=${cfg(a)}") }
    override fun onActivityPaused(a: Activity) { if (resumedActivity === a) resumedActivity = null; log("lc pause ${a.localClassName}") }
    override fun onActivityDestroyed(a: Activity) { log("lc destroy ${a.localClassName}") }
    override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
}
