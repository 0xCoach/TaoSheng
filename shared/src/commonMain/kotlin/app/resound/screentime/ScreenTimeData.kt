package app.resound.screentime

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 系统使用情况事件的原始行；type 存 Android UsageEvents.Event 原始整数，其余类型在拉取时丢弃。 */
@Entity(tableName = "screen_events", indices = [Index(value = ["at", "pkg", "type", "cls"], unique = true)])
data class ScreenEventRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val at: Long, val pkg: String,
    /** 界面组件类名；亮熄屏、开关机等系统事件没有类名，存空串。 */
    val cls: String, val type: Int)

/** 包名到显示名；显示名会随系统语言变化，用 updatedAt 记最后刷新。 */
@Entity(tableName = "screen_app_names")
data class ScreenAppNameRow(@PrimaryKey val pkg: String, val name: String, val updatedAt: Long)

/** 拉取覆盖状态；source 预留给第 3 步的媒体播放（"playback"）。coveredUntil 为覆盖进度，floor 是隐私删除下限。 */
@Entity(tableName = "screen_pull_state")
data class ScreenPullStateRow(@PrimaryKey val source: String, val coveredUntil: Long?, val floor: Long = 0)

/** 覆盖缺口及原因；coverage_lag 只在查询时现算，不落库。 */
@Entity(tableName = "screen_coverage_gaps", indices = [Index(value = ["source", "fromAt"])])
data class ScreenCoverageGapRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val source: String,
    val fromAt: Long, val untilAt: Long, val code: String)

/** 设备播放记录一行；untilAt 在播放中为 null。name 是写入时的 App 名快照，查不到时等于 pkg。不存「是否外放」。 */
@Entity(tableName = "playback_rows", indices = [Index("fromAt"), Index("sessionKey")])
data class PlaybackRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val sessionKey: String, val pkg: String,
    val name: String, val title: String?, val subtitle: String?, val fromAt: Long, val untilAt: Long?, val lastSeenAt: Long)

/** 屏幕事件覆盖来源名；播放记录用 [PLAYBACK_SOURCE]。 */
const val SCREEN_SOURCE = "screen"
