package app.resound.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.resound.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** 只在主线程读写。系统确认 Intent 仅交给当前会话的中转页，或放入不可变通知。 */
internal object InstallRelay {
    var foreground: Activity? = null
    val pending = MutableStateFlow<Pair<Int, Intent>?>(null)

    fun intent(context: Context, session: Int, confirmation: Intent? = null) =
        Intent(context, InstallRelayActivity::class.java)
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, session)
            .putExtra(Intent.EXTRA_INTENT, confirmation)

    fun openFromForeground(session: Int): Boolean {
        val activity = foreground ?: return true
        return runCatching { activity.startActivity(intent(activity, session)) }
            .onFailure { UpdateDiagnostics.record(activity, "install session=$session relay_start_failed=${it.javaClass.simpleName}") }.isSuccess
    }
}

class InstallRelayActivity : ComponentActivity() {
    private val session by lazy { intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) }
    private val engine get() = AppGraph.updater as UpdateEngine
    private var launched = false
    private var left = false
    private val resumed = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launched = savedInstanceState?.getBoolean("launched") == true
        left = savedInstanceState?.getBoolean("left") == true
        UpdateDiagnostics.record(this, "install session=$session relay_opened")
        lifecycleScope.launch {
            if (!engine.pendingRelayIsCurrent(session)) { finish(); return@launch }
            @Suppress("DEPRECATION")
            val supplied = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            combine(InstallRelay.pending, resumed, engine.state) { pending, visible, state ->
                Triple(pending, visible, state)
            }.collect { (pending, visible, state) ->
                if (!visible || isFinishing || launched) return@collect
                if (state !is UpdateState.Installing && supplied == null && pending?.first != session) {
                    finish()
                    return@collect
                }
                val confirm = supplied ?: pending?.takeIf { it.first == session }?.second ?: return@collect
                if (!engine.pendingRelayIsCurrent(session)) { finish(); return@collect }
                if (!resumed.value) return@collect
                runCatching {
                    startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    launched = true
                    lifecycleScope.launch {
                        kotlinx.coroutines.delay(InstallConfirmationRule.TIMEOUT_MS)
                        if (!left && !isFinishing) {
                            engine.unresolved(session, "confirmation_not_visible_30s")
                            finish()
                        }
                    }
                    UpdateNotifications.dismissConfirmation(this@InstallRelayActivity)
                    UpdateDiagnostics.record(this@InstallRelayActivity, "install session=$session confirmation_opened")
                }.onFailure {
                    engine.unresolved(session, "confirmation_launch_failed_${it.javaClass.simpleName}")
                    finish()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed.value = true
        if (launched && left) {
            UpdateDiagnostics.record(this, "install session=$session confirmation_returned")
            lifecycleScope.launch {
                engine.confirmationReturned(session)
                finish()
            }
        }
    }
    override fun onPause() {
        resumed.value = false
        if (launched) left = true
        super.onPause()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("launched", launched)
        outState.putBoolean("left", left)
        super.onSaveInstanceState(outState)
    }
}
