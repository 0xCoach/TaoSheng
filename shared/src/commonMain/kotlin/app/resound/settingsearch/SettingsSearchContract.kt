package app.resound.settingsearch

/*
 * 卡 147：设置搜索的平台无关约定。索引和匹配规则是纯 Kotlin，以后 iOS 直接复用。
 * 本文件不引用 android.* / java.*。
 */

/**
 * 一个可搜到的设置项。
 * [page] 为设置二级页的键，取值与界面 SettingsPage 枚举名一致（如 "Capture"、"Model"、"Advanced"）。
 * [anchor] 为该页内具体选项的键（界面据此滚动并闪一下高亮）；只到页面一级时为 null。
 */
data class SettingsEntry(
    val title: String,
    val page: String,
    val pageTitle: String,
    val anchor: String? = null,
    /** 用户可能用的说法：同义词、口语（如“耗电”“电池”“被杀”都指向后台保活） */
    val keywords: List<String> = emptyList(),
)

interface SettingsSearch {
    val entries: List<SettingsEntry>
    /** 按相关程度排序，最多 [limit] 条；空查询返回空列表 */
    fun search(query: String, limit: Int = 20): List<SettingsEntry>
}
