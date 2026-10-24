package app.resound.diary

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/*
 * 记忆页“按天”导出 Markdown 的约定。界面由 Claude 做，生成与上传由后端做。
 */

interface DiaryRepository {
    /**
     * 生成 from..to（含两端）每天的日记，按日期先后拼成一份 Markdown。
     * includeLines = true 时每天末尾附原话。没有内容的日子跳过；全都没有时返回空字符串。
     */
    suspend fun markdown(from: LocalDate, to: LocalDate, includeLines: Boolean): String

    /** 写成缓存里的 .md 文件并返回可分享的地址（FileProvider），供系统分享面板使用 */
    suspend fun exportFile(from: LocalDate, to: LocalDate, includeLines: Boolean): Uri

    /** 每晚备份时，顺带把日记上传到坚果云“Resound-Diary/日期.md”（默认关） */
    val cloudSync: StateFlow<Boolean>

    fun setCloudSync(enabled: Boolean)
}
