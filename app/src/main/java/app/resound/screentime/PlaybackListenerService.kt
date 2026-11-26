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
import app.resound.diagnostics.FailureNotes
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
            FailureNotes.record("screentime/PlaybackListenerService.collect", t)
            Log.w(TAG, "playback: " + t.javaClass.simpleName)
        },
    )
    private var manager: MediaSessionManager? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private val registrations = LinkedHashMap<String, Session>()
    private var heartbeatJob: Job? = null
    private var titleSwitchJob: Job? = null

    private inner class Session(val controller: MediaController) : MediaController.Callback() {
        val key = sessionKey(controller)

        override fun onPlaybackStateChanged(state: PlaybackState?) = reportIfCurrent()

        override fun onMetadataChanged(metadata: MediaMetadata?) = reportIfCurrent()

        override fun onSessionDestroyed() {
            if (registrations[key] === this) forgetSession(key)
        }

        fun stop() = controller.unregisterCallback(this)

        fun isAdvancing(): Boolean {
            val state = controller.playbackState ?: return false
            return isPlaying(state) && state.playbackSpeed > 0f
        }

        fun report(playing: Boolean? = null) {
            if (!collectionAllowed()) return
            val active = playing ?: isPlaying(controller.playbackState)
            val metadata = controller.metadata
            val title = pickMeta(metadata, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            val subtitle = pickMeta(metadata, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            val pkg = controller.packageName
            val at = System.currentTimeMillis()
            emit { it.changed(key, pkg, title, subtitle, active, at) }
        }

        private fun reportIfCurrent() {
            if (registrations[key] === this && collectionAllowed()) report()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        manager = getSystemService(MediaSessionManager::class.java)
        watchTitleSwitch()
    }

    private fun collectionAllowed() = manager != null && AppGraph.isPlaybackTitleReady &&
        AppGraph.playbackTitleSettings.enabled.value

    private fun startCollection() {
        if (!collectionAllowed() || sessionsListener != null) return
        val m = manager ?: return
        emit { it.connected(System.currentTimeMillis()) }
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            reconcile(controllers.orEmpty())
        }
        sessionsListener = listener
        m.addOnActiveSessionsChangedListener(listener, componentName, mainHandler)
        reconcile(m.getActiveSessions(componentName))
        startHeartbeat()
    }

    private fun stopCollection() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        sessionsListener?.let { manager?.removeOnActiveSessionsChangedListener(it) }
        sessionsListener = null
        val old = registrations.values.toList()
        registrations.clear()
        old.forEach(Session::stop)
    }

    override fun onListenerDisconnected() {
        stopCollection()
        manager = null
        titleSwitchJob?.cancel()
        titleSwitchJob = null
        emit { it.disconnected(System.currentTimeMillis()) }
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        stopCollection()
        manager = null
        scope.cancel()
        super.onDestroy()
    }

    private fun reconcile(controllers: List<MediaController>) {
        if (!collectionAllowed()) return
        val incoming = controllers.associateBy(::sessionKey)
        val obsolete = registrations.keys - incoming.keys
        obsolete.forEach(::forgetSession)
        for ((key, controller) in incoming) {
            if (key in registrations || !collectionAllowed()) continue
            val session = Session(controller)
            controller.registerCallback(session, mainHandler)
            registrations[key] = session
            session.report()
        }
        Log.i(TAG, "playback: sessions-changed n=" + registrations.size)
    }

    private fun forgetSession(key: String) {
        val session = registrations.remove(key) ?: return
        session.stop()
        session.report(playing = false)
    }

    private fun watchTitleSwitch() {
        titleSwitchJob?.cancel()
        if (!AppGraph.isPlaybackTitleReady) return
        val settings = AppGraph.playbackTitleSettings
        titleSwitchJob = scope.launch(Dispatchers.Main.immediate) {
            settings.enabled.collect { on ->
                updatePlaybackCollection(on && collectionAllowed(), sessionsListener != null,
                    start = ::startCollection, stop = ::stopCollection)
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
                    if (collectionAllowed()) registrations.values.filter(Session::isAdvancing).map(Session::key)
                    else emptyList()
                }
                for (key in keys) emit { it.heartbeat(key, now) }
                Log.i(TAG, "playback: heartbeat n=" + keys.size)
            }
        }
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

        fun pickMeta(md: MediaMetadata?, first: String, second: String): String? =
            md?.getString(first)?.takeUnless(String::isEmpty) ?: md?.getString(second)?.takeUnless(String::isEmpty)
    }
}
