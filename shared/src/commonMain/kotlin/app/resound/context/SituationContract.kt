package app.resound.context

/*
 * 卡 130/131：声音、位置、环境音按时间联动。界面（Claude）与整理、问问只依赖这里；底层（Codex）实现。
 * 三者共用同一条时间线（毫秒时间戳），按时间段重叠对齐。全部在手机本地计算，不联网。
 */

/** 一段时间里停留最久的地方 */
data class PlaceAt(
    /** 给人看的名字：手动改过的名字 > 地名缓存 > 地址；都没有为“某地” */
    val name: String,
    /** 这里是不是“家” */
    val home: Boolean,
    /** 在这里停留的起止（可能比查询区间长） */
    val start: Long,
    val end: Long,
)

interface SituationLookup {
    /**
     * [start, end) 内停留最久的地点；这段时间主要在路上、或没有定位记录、或足迹没开，返回 null。
     * 只读本地缓存的地名，不触发联网反查。
     */
    suspend fun placeDuring(start: Long, end: Long): PlaceAt?

    /** [start, end) 内的环境音标签（中文，如“下雨”“车流”），按出现时长降序，最多 [limit] 个；没有返回空 */
    suspend fun envDuring(start: Long, end: Long, limit: Int = 3): List<String>

    /** [from, to) 内在名叫 [placeName] 的地方（名字包含即可，不区分大小写）停留的时间段，按时间排 */
    suspend fun staysAt(placeName: String, from: Long, to: Long): List<PlaceAt>
}
