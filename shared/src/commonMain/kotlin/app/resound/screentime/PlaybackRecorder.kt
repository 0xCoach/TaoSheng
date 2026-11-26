package app.resound.screentime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 媒体会话变化的落库实现。四个方法共用一把 Mutex；「记录播放标题」关闭时全部直接返回、不写库。
 * 断开缺口的 code 为 [GapCode.LISTENER_DISCONNECTED]（listener_disconnected）。
 * 落库失败 fail fast 上抛，由平台采集入口接入 FailureNotes（只记固定位置与异常类型，不记标题和包名）。
 */
class PlaybackRecorder(
    private val dao: ScreenTimeDao,
    private val settings: PlaybackTitleSettings,
    private val appNames: suspend (Set<String>) -> Map<String, String>,
) : PlaybackSink {
    private val gate = Mutex()

    private suspend fun record(block: suspend () -> Unit) = gate.withLock {
        if (!settings.enabled.value) return@withLock
        dao.closeOpenPlaybacksBefore(settings.enabledAt)
        block()
    }

    override suspend fun changed(sessionKey: String, pkg: String, title: String?, subtitle: String?, playing: Boolean, at: Long) = record {
        val previous = dao.openPlayback(sessionKey)
        if (!playing) {
            previous?.let { finish(it, at) }
            return@record
        }

        val nextTitle = title?.trim()?.takeUnless { it.isEmpty() }
        val nextSubtitle = subtitle?.trim()?.takeUnless { it.isEmpty() }
        if (previous == null) {
            openRow(sessionKey, pkg, nextTitle, nextSubtitle, at)
            return@record
        }

        if (previous.title != null && nextTitle != null && nextTitle != previous.title) {
            finish(previous, at)
            openRow(sessionKey, pkg, nextTitle, nextSubtitle, at)
        } else {
            val firstMetadata = previous.title == null && nextTitle != null
            dao.updatePlayback(previous.copy(
                title = if (firstMetadata) nextTitle else previous.title,
                subtitle = if (firstMetadata) nextSubtitle else previous.subtitle,
                lastSeenAt = maxOf(previous.lastSeenAt, at),
            ))
        }
    }

    override suspend fun heartbeat(sessionKey: String, at: Long) = record {
        val row = dao.openPlayback(sessionKey) ?: return@record
        dao.updatePlayback(row.copy(lastSeenAt = maxOf(at, row.lastSeenAt)))
    }

    override suspend fun disconnected(at: Long) = record {
        dao.closeOpenPlaybacksAtLastSeen()
        logDisconnectGap(at, Long.MAX_VALUE)
    }

    override suspend fun connected(at: Long) = record {
        if (dao.closePlaybackDisconnectGaps(at) == 0) {
            val newest = dao.openPlaybacks().maxOfOrNull { it.lastSeenAt }
            if (newest != null && newest < at) logDisconnectGap(newest, at)
        }
        dao.closeOpenPlaybacksAtLastSeen()
    }

    private suspend fun finish(row: PlaybackRow, at: Long) {
        dao.updatePlayback(row.copy(untilAt = at, lastSeenAt = maxOf(at, row.lastSeenAt)))
    }

    private suspend fun logDisconnectGap(start: Long, end: Long) {
        dao.recordGap(ScreenCoverageGapRow(
            source = PLAYBACK_SOURCE, fromAt = start, untilAt = end, code = GapCode.LISTENER_DISCONNECTED,
        ))
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

}
