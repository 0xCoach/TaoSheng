package app.resound.screentime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 媒体会话变化的落库实现。四个方法共用一把 Mutex；「记录播放标题」关闭时全部直接返回、不写库。
 * 断开缺口的 code 为 [GapCode.LISTENER_DISCONNECTED]（listener_disconnected）。
 * 落库失败 fail fast 上抛，由调用方记一行 `playback:` 前缀日志（不记标题）。
 */
class PlaybackRecorder(
    private val dao: ScreenTimeDao,
    private val settings: PlaybackTitleSettings,
    private val appNames: suspend (Set<String>) -> Map<String, String>,
) : PlaybackSink {
    private val gate = Mutex()

    override suspend fun changed(sessionKey: String, pkg: String, title: String?, subtitle: String?, playing: Boolean, at: Long) {
        gate.withLock {
            if (!settings.enabled.value) return@withLock
            dao.closeOpenPlaybacksBefore(settings.enabledAt)
            val cleanTitle = blankToNull(title)
            val cleanSubtitle = blankToNull(subtitle)
            val open = dao.openPlayback(sessionKey)
            if (!playing) {
                if (open != null) dao.updatePlayback(open.copy(untilAt = at, lastSeenAt = maxOf(open.lastSeenAt, at)))
                return@withLock
            }
            when {
                open == null -> openRow(sessionKey, pkg, cleanTitle, cleanSubtitle, at)
                open.title != null && cleanTitle != null && open.title != cleanTitle -> {
                    dao.updatePlayback(open.copy(untilAt = at, lastSeenAt = maxOf(open.lastSeenAt, at)))
                    openRow(sessionKey, pkg, cleanTitle, cleanSubtitle, at)
                }
                open.title == null && cleanTitle != null ->
                    dao.updatePlayback(open.copy(title = cleanTitle, subtitle = cleanSubtitle, lastSeenAt = maxOf(open.lastSeenAt, at)))
                else -> dao.updatePlayback(open.copy(lastSeenAt = maxOf(open.lastSeenAt, at)))
            }
        }
    }

    override suspend fun heartbeat(sessionKey: String, at: Long) {
        gate.withLock {
            if (!settings.enabled.value) return@withLock
            dao.closeOpenPlaybacksBefore(settings.enabledAt)
            val open = dao.openPlayback(sessionKey) ?: return@withLock
            dao.updatePlayback(open.copy(lastSeenAt = maxOf(open.lastSeenAt, at)))
        }
    }

    override suspend fun disconnected(at: Long) {
        gate.withLock {
            if (!settings.enabled.value) return@withLock
            dao.closeOpenPlaybacksBefore(settings.enabledAt)
            dao.closeOpenPlaybacksAtLastSeen()
            dao.recordGap(
                ScreenCoverageGapRow(
                    source = PLAYBACK_SOURCE, fromAt = at, untilAt = Long.MAX_VALUE,
                    code = GapCode.LISTENER_DISCONNECTED,
                ),
            )
        }
    }

    override suspend fun connected(at: Long) {
        gate.withLock {
            if (!settings.enabled.value) return@withLock
            dao.closeOpenPlaybacksBefore(settings.enabledAt)
            val closed = dao.closePlaybackDisconnectGaps(at)
            if (closed == 0) {
                val open = dao.openPlaybacks()
                if (open.isNotEmpty()) {
                    val from = open.maxOf { it.lastSeenAt }
                    if (from < at) {
                        dao.recordGap(
                            ScreenCoverageGapRow(
                                source = PLAYBACK_SOURCE, fromAt = from, untilAt = at,
                                code = GapCode.LISTENER_DISCONNECTED,
                            ),
                        )
                    }
                }
            }
            dao.closeOpenPlaybacksAtLastSeen()
        }
    }

    private suspend fun openRow(sessionKey: String, pkg: String, title: String?, subtitle: String?, at: Long) {
        val name = appNames(setOf(pkg))[pkg] ?: pkg
        dao.insertPlayback(
            PlaybackRow(
                sessionKey = sessionKey, pkg = pkg, name = name, title = title, subtitle = subtitle,
                fromAt = at, untilAt = null, lastSeenAt = at,
            ),
        )
    }

    private fun blankToNull(value: String?): String? {
        val trimmed = value?.trim()
        return if (trimmed.isNullOrEmpty()) null else trimmed
    }
}
