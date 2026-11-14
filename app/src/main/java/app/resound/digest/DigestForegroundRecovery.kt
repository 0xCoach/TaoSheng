package app.resound.digest

import android.app.Activity
import android.app.Application
import android.os.Bundle
import app.resound.ResoundApp

/** 重新打开页面即检查漏点，不要求用户切换设置或重新启动进程。 */
class DigestForegroundRecovery(private val app: ResoundApp) : Application.ActivityLifecycleCallbacks {
    override fun onActivityResumed(activity: Activity) { app.recoverTimedDigest() }
    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
