package app.resound.screentime

/**
 * 屏幕时间的读取约定（Claude 定稿，签名不得自行修改）。
 * 本版播放记录只读走 [PlaybackQueries]；[ScreenTimeReader] 仍保留签名，本 PR 不装配实现。
 * 区间一律左闭右开、纪元毫秒。
 */

/** 带显示名的使用段。[name] 已脱敏（去换行、替换【】｜《》、截到 40 码点）；查不到显示名时等于 [pkg]。 */
data class NamedAppSession(val pkg: String, val name: String, val from: Long, val until: Long)

/** 覆盖缺口。[code] 取 [GapCode] 之一；[source] 为 [SCREEN_SOURCE] 或 "playback"；[fix] 是一句中文修复办法。 */
data class CoverageGap(val code: String, val source: String, val from: Long, val until: Long, val fix: String)

/**
 * 设备播放记录一行：只表示手机在播放，不代表本人在听。
 * [until] 已按「结束时刻，未结束则最后观测时刻」截止，并裁到查询区间内。
 * [name]、[title]、[subtitle] 都已按 [NamedAppSession.name] 同样规则脱敏；没有标题时为 null。
 */
data class DevicePlayback(val pkg: String, val name: String, val title: String?, val subtitle: String?, val from: Long, val until: Long)

/**
 * 一次查询的结果。[enabled] 为 false 表示屏幕时间总开关关闭：其余字段为空，调用方不得输出任何屏幕数据。
 * [coverageUntil] 是已成功采集到的时刻（可能早于查询终点）；从未采集过为 null。
 * [devicePlayback] 按开始时刻升序；[gaps] 同时含 screen 与 playback 两个来源。
 */
data class ScreenTimeResult(
    val enabled: Boolean,
    val sessions: List<NamedAppSession>,
    val coverageUntil: Long?,
    val gaps: List<CoverageGap>,
    val devicePlayback: List<DevicePlayback> = emptyList(),
) {
    companion object {
        val DISABLED = ScreenTimeResult(enabled = false, sessions = emptyList(), coverageUntil = null, gaps = emptyList())
    }
}

/** 缺口代码，共六个；coverage_lag 只在查询时现算，不落库。 */
object GapCode {
    const val USAGE_ACCESS_MISSING = "usage_access_missing"
    const val NOTIFICATION_ACCESS_MISSING = "notification_access_missing"
    const val PULL_FAILED = "pull_failed"
    const val COVERAGE_LAG = "coverage_lag"
    const val LISTENER_DISCONNECTED = "listener_disconnected"
    const val DISABLED = "disabled"
}

/** 播放记录的覆盖来源名（与 [SCREEN_SOURCE] 并列）。 */
const val PLAYBACK_SOURCE = "playback"

/**
 * 媒体会话变化的接收方。安卓通知监听服务经 AppGraph 取得并调用；实现负责「记录播放标题」门控
 * （关闭时全部调用直接返回、不写库）与落库。[sessionKey] 是平台给的不透明会话标识，同一会话不变。
 * 时间一律纪元毫秒。落库失败照常上抛（fail fast），由调用方记一行 `playback:` 前缀的日志。
 */
interface PlaybackSink {
    /** 会话状态或标题变化；[playing] 为 false 表示暂停、停止或会话消失。 */
    suspend fun changed(sessionKey: String, pkg: String, title: String?, subtitle: String?, playing: Boolean, at: Long)
    /** 播放中且进度确实在前进时，平台每 60 秒调用一次，刷新该会话的最后观测时刻。 */
    suspend fun heartbeat(sessionKey: String, at: Long)
    /** 监听服务连上（含进程重启后首次连上）；随后平台会对当前每个会话各调一次 [changed]。 */
    suspend fun connected(at: Long)
    /** 监听服务断开。 */
    suspend fun disconnected(at: Long)
}

/** 屏幕时间读取入口。实现负责总开关门控、查询前增量拉取与缺口汇总。 */
fun interface ScreenTimeReader {
    suspend fun sessions(from: Long, until: Long): ScreenTimeResult
}
