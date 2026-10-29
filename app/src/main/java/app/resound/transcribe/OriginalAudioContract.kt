package app.resound.transcribe

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/*
 * 卡 062 原声：保留时长、占用与清除、播放定位、播放时暂停收音。界面（Claude）只依赖这里；底层（Codex）实现。
 * 改签名需双方确认。
 */

/** 原声保留时长可选值（天），默认 [DEFAULT_RETENTION_DAYS] */
val RETENTION_DAY_OPTIONS = listOf(1, 3, 7, 30)
const val DEFAULT_RETENTION_DAYS = 3

/** 一段录音的原声位置；[startAt] 是录音开头的绝对时间，句子偏移 = line.startAt - startAt */
data class SegmentAudio(val segmentId: Long, val file: File, val startAt: Long, val endAt: Long)

data class AudioUsage(
    /** 现存原声总大小 */
    val bytes: Long,
    /** 其中标了重点的事的原声 */
    val starredBytes: Long,
    /** 现存原声覆盖的天数 */
    val days: Int,
    /** 还在等转写/声纹的录音段（不会被清除） */
    val pending: Int,
)

/** 清除或缩短保留时长前的预估 / 实际结果 */
data class AudioTrim(val bytes: Long, val days: Int, val segments: Int, val keptPending: Int)

interface OriginalAudio {
    val retentionDays: StateFlow<Int>
    /** 改短时调用前界面先用 [previewTrim] 给出提示；改后立即按新规则清理 */
    fun setRetentionDays(days: Int)

    /** 标了重点的事一直保留原声，默认 true */
    val keepStarred: StateFlow<Boolean>
    fun setKeepStarred(keep: Boolean)

    fun usage(): Flow<AudioUsage>

    /** 如果保留时长改成 [days]，会删掉多少（不删除） */
    suspend fun previewTrim(days: Int): AudioTrim
    /** 清除原声：includeStarred=false 时重点的保留；还在等转写/声纹的一律不删。原话文字不动 */
    suspend fun previewClear(includeStarred: Boolean): AudioTrim
    suspend fun clear(includeStarred: Boolean): AudioTrim

    /** 找到一段录音的原声；文件已删返回 null */
    suspend fun locate(segmentId: Long): SegmentAudio?
    /** 批量查哪些录音段还有原声（界面显示 ▶） */
    suspend fun available(segmentIds: Set<Long>): Set<Long>

    /** 开始播放前调用：暂停日常收音（与录声纹同样做法，不写成用户暂停）；结束、出错、离开页面时调用 [endPlayback] 恢复。重复调用安全 */
    fun beginPlayback()
    fun endPlayback()
}
