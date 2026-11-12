package app.resound.quick

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import app.resound.AppGraph
import app.resound.ResoundApp
import app.resound.capture.CaptureNotifications
import app.resound.capture.CaptureTilePolicy
import app.resound.topic.TopicException
import app.resound.topic.TopicQuickPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 透明的动作入口；等待服务确认后结束，不承载任何界面。 */
class QuickActionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 被系统重建时不重放一次“记一下”等有副作用的操作。
        if (savedInstanceState != null) { finish(); return }
        lifecycleScope.launch {
            try {
                // Android 的麦克风前台服务要求入口真正可见后再启动。
                lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
                val message = execute()
                if (message.isNotEmpty()) Toast.makeText(this@QuickActionActivity, message, Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) { throw e }
            catch (e: TopicException) { Toast.makeText(this@QuickActionActivity, e.message ?: TopicQuickPolicy.FAILED, Toast.LENGTH_SHORT).show() }
            catch (_: Exception) { Toast.makeText(this@QuickActionActivity, QuickText.FAILED, Toast.LENGTH_SHORT).show() }
            finally { finish() }
        }
    }

    private suspend fun execute(): String {
        if (!AppGraph.isReady || !AppGraph.isTopicsReady || !AppGraph.isQuickPrefsReady) return QuickText.FAILED
        val action = QuickAction.entries.firstOrNull { it.name == intent.getStringExtra("quickAction") } ?: return QuickText.FAILED
        val capture = AppGraph.capture
        val topics = AppGraph.topics
        // 冷启动先读回持久化专题，不能把 current 的初始 null 当作“没有专题”。
        (application as ResoundApp).topics.repository.tick()
        val current = topics.current.value
        val topicState = TopicQuickPolicy.state(current?.status)
        val minutes = AppGraph.quickPrefs.lastPauseMinutes.value
        val kind = AppGraph.quickPrefs.lastTopicKind.value
        if (!QuickExecutionRules.allowed(action, intent.getLongExtra("topicId", -1), current?.id ?: 0,
                CaptureTilePolicy.listenState(capture.status.value.state), topicState, minutes, kind)) return QuickText.STALE
        val command = QuickExecutionRules.command(action, topicState)
        if (command.needsMicrophone && (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
                !CaptureNotifications.permitted(this))) return QuickText.PERMISSION
        when (command) {
            QuickCommand.StartTopic -> {
                val result = topics.start(kind, null)
                TopicQuickPolicy.startFeedback(result.error, result.recordingDisabled, result.quietOverride)?.let { return it }
            }
            QuickCommand.MarkTopic -> topics.mark()
            QuickCommand.EndTopic -> topics.end()
            QuickCommand.PauseTopic -> topics.pause()
            QuickCommand.ResumeTopic -> topics.resume()
            QuickCommand.PauseListening -> capture.pause(minutes)
            QuickCommand.ResumeListening -> capture.resume()
            QuickCommand.StartListening -> capture.start()
            QuickCommand.AddTodo -> { startActivity(android.content.Intent(this, app.resound.ui.items.QuickTodoActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); return "" }
        }
        if (command.needsMicrophone) {
            withTimeoutOrNull(5000) {
                capture.status.first { QuickExecutionRules.captureConfirmed(command, CaptureTilePolicy.listenState(it.state)) }
            } ?: return QuickText.CAPTURE_PENDING
        }
        return QuickText.success(action, topicState, minutes, kind)
    }
}
