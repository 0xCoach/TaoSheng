package app.resound.screentime

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log
import app.resound.AppGraph
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 只为取得 [MediaSessionManager.getActiveSessions] 授权，不读通知内容。
 * 媒体会话的播放状态和标题变化经 [AppGraph.playbackSink] 报给共用模块。
 */
class PlaybackListenerService : NotificationListenerService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val componentName by lazy { ComponentName(this, PlaybackListenerService::class.java) }
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism(1) + CoroutineExceptionHandler { _, t ->
            Log.w(TAG, "playback: " + t.javaClass.simpleName)
        },
    )
    private var manager: MediaSessionManager? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private val registrations = LinkedHashMap<String, Pair<MediaController, MediaController.Callback>>()
    private var heartbeatJob: Job? = null
    private var titleSwitchJob: Job? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        emit { it.connected(System.currentTimeMillis()) }
        val m = getSystemService(MediaSessionManager::class.java)
        manager = m
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            onSessionsChanged(controllers ?: emptyList())
        }
        sessionsListener = listener
        m.addOnActiveSessionsChangedListener(listener, componentName, mainHandler)
        val sessions = m.getActiveSessions(componentName)
        sessions.forEach { addSession(it) }
        Log.i(TAG, "playback: connected sessions=" + sessions.size)
        startHeartbeat()
        watchTitleSwitch()
    }

    override fun onListenerDisconnected() {
        val listener = sessionsListener
        val m = manager
        if (m != null && listener != null) m.removeOnActiveSessionsChangedListener(listener)
        sessionsListener = null
        manager = null
        for ((controller, callback) in registrations.values) controller.unregisterCallback(callback)
        val n = registrations.size
        registrations.clear()
        heartbeatJob?.cancel()
        heartbeatJob = null
        titleSwitchJob?.cancel()
        titleSwitchJob = null
        emit { it.disconnected(System.currentTimeMillis()) }
        Log.i(TAG, "playback: disconnected sessions=$n")
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun onSessionsChanged(controllers: List<MediaController>) {
        val incoming = LinkedHashMap<String, MediaController>()
        for (c in controllers) incoming[sessionKey(c)] = c
        val gone = registrations.keys.filter { it !in incoming }
        for (key in gone) {
            val (controller, callback) = registrations.remove(key) ?: continue
            controller.unregisterCallback(callback)
            emitChanged(controller, playing = false)
        }
        for ((key, controller) in incoming) {
            if (key !in registrations) addSession(controller)
        }
        Log.i(TAG, "playback: sessions-changed n=" + registrations.size)
    }

    private fun addSession(controller: MediaController) {
        val key = sessionKey(controller)
        if (key in registrations) return
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                emitChanged(controller, playing = isPlaying(controller.playbackState))
            }

            override fun onMetadataChanged(metadata: MediaMetadata?) {
                emitChanged(controller, playing = isPlaying(controller.playbackState))
            }

            override fun onSessionDestroyed() {
                emitChanged(controller, playing = false)
                val removed = registrations.remove(sessionKey(controller))
                if (removed != null) controller.unregisterCallback(removed.second)
            }
        }
        controller.registerCallback(callback, mainHandler)
        registrations[key] = controller to callback
        emitChanged(controller, playing = isPlaying(controller.playbackState))
    }

    private fun watchTitleSwitch() {
        titleSwitchJob?.cancel()
        if (!AppGraph.isPlaybackTitleReady) return
        val settings = AppGraph.playbackTitleSettings
        titleSwitchJob = scope.launch {
            var was = settings.enabled.value
            settings.enabled.collect { on ->
                val turnedOn = !was && on
                was = on
                if (!turnedOn) return@collect
                withContext(Dispatchers.Main.immediate) {
                    for ((_, pair) in registrations) {
                        val controller = pair.first
                        emitChanged(controller, playing = isPlaying(controller.playbackState))
                    }
                }
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                val now = System.currentTimeMillis()
                val keys = withContext(Dispatchers.Main.immediate) {
                    registrations.mapNotNull { (key, pair) ->
                        val state = pair.first.playbackState
                        if (state != null &&
                            state.state == PlaybackState.STATE_PLAYING &&
                            state.playbackSpeed > 0f
                        ) key else null
                    }
                }
                for (key in keys) emit { it.heartbeat(key, now) }
                Log.i(TAG, "playback: heartbeat n=" + keys.size)
            }
        }
    }

    private fun emitChanged(controller: MediaController, playing: Boolean) {
        val md = controller.metadata
        val title = pickMeta(md, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val subtitle = pickMeta(md, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val at = System.currentTimeMillis()
        val key = sessionKey(controller)
        val pkg = controller.packageName
        emit { it.changed(key, pkg, title, subtitle, playing, at) }
    }

    private fun emit(block: suspend (PlaybackSink) -> Unit) {
        val sink = AppGraph.playbackSink ?: return
        scope.launch { block(sink) }
    }

    private companion object {
        const val TAG = "playback"
        const val HEARTBEAT_MS = 60_000L

        // 拼上包名：hashCode 会撞，包名把不同 App 的会话隔开，避免两路播放收成一行。
        // 不用 identityHashCode：每次会话列表回调都会新建 Token 对象，同一会话会被切成多行；Token.hashCode 按会话值计算，稳定。
        fun sessionKey(controller: MediaController) =
            "${controller.packageName}:${controller.sessionToken.hashCode()}"

        fun isPlaying(state: PlaybackState?) = state?.state == PlaybackState.STATE_PLAYING

        fun pickMeta(md: MediaMetadata?, first: String, second: String): String? {
            if (md == null) return null
            val a = md.getString(first)
            if (!a.isNullOrEmpty()) return a
            val b = md.getString(second)
            return b?.takeIf { it.isNotEmpty() }
        }
    }
}
