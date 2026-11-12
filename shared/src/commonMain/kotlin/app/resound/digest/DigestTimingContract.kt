package app.resound.digest

import kotlinx.coroutines.flow.StateFlow

/**
 * 卡 053 · 整理时机的约定（Claude 定，Codex 实现，签名不要自行修改）。
 *
 * 两种方式二选一：按间隔（原来的 15/30/60/自定义分钟）或按时间点（一天里的几个时刻，到点整理，方便避开模型高价时段）。
 * 时间点错过了（没网、关机、被系统推迟到下一个点之后）不补，等下一个点；用户随时可点“现在整理”。
 * 每日回顾、每周回顾生成前先整理一次，不管选的是哪种方式。
 */
enum class DigestMode { Interval, TimePoints }

interface DigestTiming {
    val mode: StateFlow<DigestMode>
    fun setMode(mode: DigestMode)

    /** 一天里的整理时刻，单位是当天 0 点起的分钟（0..1439），升序、去重，最多 [MAX_TIME_POINTS] 个 */
    val timePoints: StateFlow<List<Int>>
    fun addTimePoint(minutes: Int)
    fun removeTimePoint(minutes: Int)

    /** 按时间点时下一次自动整理的时间（毫秒），今天页显示“下次整理 18:00”；按间隔或一个时间点都没设时为 null */
    val nextDigestAt: StateFlow<Long?>

    /** 系统是否允许准点闹钟（安卓 12+ 的“闹钟与提醒”权限）；false 时界面引导去打开，没开也照常排，只是可能晚几分钟 */
    fun canScheduleExact(): Boolean

    companion object { const val MAX_TIME_POINTS = 8 }
}
