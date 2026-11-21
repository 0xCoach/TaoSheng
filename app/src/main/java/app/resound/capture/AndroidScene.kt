package app.resound.capture

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.PowerManager
import app.resound.AppGraph
import app.resound.data.CaptureDao
import app.resound.data.SceneSessionDao
import app.resound.data.SceneSessionRow
import app.resound.inbox.IdeaRepository
import app.resound.topic.TopicPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

/*
 * R4 场景感知：安卓侧信号采集 + 决策执行 + 场景会话落库。
 * 决策本身是 shared 的 SceneEngine.decide；这里只负责读系统信号、
 * 把结果交给既有暂停通道，并把暂停类/资源受限场景的会话写进 scene_sessions。
 */
class AndroidScene(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val sessionDao: () -> SceneSessionDao?,
    private val captureDao: () -> CaptureDao?,
    private val ideas: () -> IdeaRepository?,
    private val battery: () -> Pair<Int?, Boolean?>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val currentEnabled = MutableStateFlow(prefs.getBoolean(KEY_AWARE, true))
    val enabled = currentEnabled.asStateFlow()
    private val currentPrivacy = MutableStateFlow(prefs.getBoolean(KEY_PRIVACY, false))
    val privacyPaused = currentPrivacy.asStateFlow()

    fun sessions(limit: Int = 50): Flow<List<SceneSessionRow>> = sessionDao()?.recent(limit) ?: flowOf(emptyList())

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_AWARE, on).commit()
        currentEnabled.value = on
        CaptureService.recheck()
    }

    /** 隐私暂停：写入后立即让服务重新核对；服务没在跑时记录也会在下一次启动时生效。 */
    fun setPrivacy(on: Boolean) {
        prefs.edit().putBoolean(KEY_PRIVACY, on).apply {
            if (on) putLong("manualPausedUntil", Long.MAX_VALUE) else putLong("manualPausedUntil", 0)
        }.commit()
        currentPrivacy.value = on
        if (CaptureService.alive) {
            context.startService(Intent(context, CaptureService::class.java)
                .setAction(CaptureService.PRIVACY).putExtra("on", on).putExtra("route", "scene-ui"))
        }
    }

    /** 记灵感：关联当前音频片段；同一片段短时间内的重复动作不重复建卡。 */
    suspend fun markIdea(note: String? = null): Long? {
        val at = now()
        val segment = captureDao()?.segmentAt(at)
        return ideas()?.markMoment(segment?.id, at, note)
    }

    /** 采集当前信号；CaptureService 每次 reconcile 与界面刷新都会调。 */
    fun signals(at: Long, state: CaptureState, busyDetail: String?): SceneSignals {
        val (percent, charging) = runCatching { battery() }.getOrDefault(null to null)
        val thermal = runCatching {
            context.getSystemService(PowerManager::class.java).currentThermalStatus
        }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        val topic = if (AppGraph.isTopicsReady) AppGraph.topics.current.value else null
        val meeting = topic != null && TopicPolicy.overridesQuiet(topic, at)
        val quiet = (AppGraph.quietSchedule as? AndroidQuietSchedule)?.refresh().takeUnless { meeting }
        val resource = (percent != null && percent < 20 && charging == false) || thermal >= PowerManager.THERMAL_STATUS_MODERATE
        return SceneSignals(now = at,
            manualPausedUntil = prefs.getLong("manualPausedUntil", 0),
            privacyPaused = prefs.getBoolean(KEY_PRIVACY, false),
            topicPaused = topic?.status == app.resound.topic.TopicStatus.Paused,
            quietEndsAt = quiet?.endsAt, quietName = quiet?.rule?.name,
            meetingActive = meeting,
            micBusyReason = busyDetail.takeIf { state == CaptureState.MicBusy },
            resourceLimited = resource,
            sceneAwareEnabled = currentEnabled.value)
    }

    fun decide(at: Long = now()): SceneDecision {
        val status = AppGraph.capture.status.value
        return SceneEngine.decide(signals(at, status.state, status.busyDetail))
    }

    /**
     * 由 CaptureService 在每次 reconcile 时调用：把暂停类/资源受限场景的切换记成会话行。
     * 进程重启后 openSession() 恢复未关闭行，场景变了才关旧开新。
     */
    suspend fun track(d: SceneDecision, at: Long) {
        if (!currentEnabled.value) return
        val dao = sessionDao() ?: return
        val recordable = when (d.scene) {
            RecordingScene.PrivacyPaused, RecordingScene.Paused, RecordingScene.QuietHours, RecordingScene.ResourceLimited -> true
            else -> false
        }
        val open = dao.openSession()
        if (!recordable) {
            if (open != null) dao.close(open.id, at)
            return
        }
        val kind = d.scene.name
        val source = (d.directive as? CaptureDirective.Pause)?.source?.name ?: SceneSource.System.name
        if (open != null && open.kind == kind) return
        if (open != null) dao.close(open.id, at)
        dao.insert(SceneSessionRow(kind = kind, source = source, startedAt = at, reason = d.reason))
    }

    companion object {
        const val KEY_AWARE = "sceneAware"
        const val KEY_PRIVACY = "privacyPaused"
    }
}
