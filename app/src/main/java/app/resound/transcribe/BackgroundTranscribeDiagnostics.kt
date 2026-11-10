package app.resound.transcribe

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.work.Data
import java.util.UUID

/** 只在主进程写诊断；foreground 指界面可见，不把录音前台服务算作界面前台。 */
internal object BackgroundTranscribeDiagnostics : Application.ActivityLifecycleCallbacks {
    val processInstance = UUID.randomUUID().toString()
    @Volatile var foreground = false
        private set
    @Volatile private var backgroundTransitions = 0L
    private var started = 0
    const val ENQUEUED_AT = "earlyEnqueuedAt"
    const val PROCESS = "earlyProcess"
    const val FOREGROUND = "earlyForeground"
    const val BACKGROUND_TRANSITIONS = "earlyBackgroundTransitions"

    fun input(): Data = Data.Builder()
        .putString(TranscribeWork.MODE, TranscribeWork.MODE_REALTIME)
        .putLong(ENQUEUED_AT, SystemClock.elapsedRealtime())
        .putString(PROCESS, processInstance).putBoolean(FOREGROUND, foreground)
        .putLong(BACKGROUND_TRANSITIONS, backgroundTransitions).build()

    fun delayed(context: Context, input: Data) {
        val enqueuedProcess = input.getString(PROCESS) ?: return // 升级前的任务没有采样。
        val rebuilt = enqueuedProcess != processInstance
        val background = !foreground || !input.getBoolean(FOREGROUND, false) ||
            input.getLong(BACKGROUND_TRANSITIONS, 0) != backgroundTransitions
        val wait = SystemClock.elapsedRealtime() - input.getLong(ENQUEUED_AT, 0)
        if (background && (rebuilt || wait >= 30_000L))
            record(context, "realtime-delayed", rebuilt.toString(), waitMillis = if (rebuilt) null else wait)
    }

    fun record(context: Context, stage: String, rebuilt: String = "unknown", pid: Int? = null, waitMillis: Long? = null) {
        PipelineTimes.record(context, "${System.currentTimeMillis()} 后台转写 stage=$stage foreground=$foreground " +
            "processRecreated=$rebuilt" + (pid?.let { " pid=$it" } ?: "") +
            (waitMillis?.let { " waitMillis=$it" } ?: ""))
    }

    override fun onActivityStarted(activity: Activity) { started++; foreground = true }
    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0) { foreground = false; backgroundTransitions++ }
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
