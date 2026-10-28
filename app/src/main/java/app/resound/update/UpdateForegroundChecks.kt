package app.resound.update

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 进程常驻时再次打开也检查；旋转屏幕不算重新打开。 */
internal class UpdateForegroundChecks(private val updater: UpdateEngine, private val scope: CoroutineScope) : Application.ActivityLifecycleCallbacks {
    private var started = 0
    private var changingConfiguration = false
    override fun onActivityStarted(activity: Activity) {
        if (started++ == 0 && !changingConfiguration) scope.launch { updater.checkNow(startup = true) }
    }
    override fun onActivityStopped(activity: Activity) { started--; changingConfiguration = activity.isChangingConfigurations }
    override fun onActivityCreated(activity: Activity, state: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
