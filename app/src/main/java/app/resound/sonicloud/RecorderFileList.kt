/*
 * [INPUT]: SDK 目录分片及文件名中的本地录音时间。
 * [OUTPUT]: 经校验的目录记录、日期/星期归组及每页最多 40 条的切片。
 * [POS]: 卡内文件展示规则；与导入器共用时间解析，不给未知日期编造时间。
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package app.resound.sonicloud

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONObject

internal data class RecorderDatedFile(val file: RecorderFile, val day: String, val started: Long?)
internal data class RecorderFilePage(val rows: List<RecorderDatedFile>, val index: Int, val pages: Int, val total: Int)
internal const val RECORDER_PAGE_SIZE = 40

internal fun recorderDirectoryChunk(json: JSONObject): List<RecorderFile> {
    require(json.optInt("finish", -1) in 0..1) { "录音卡目录结束标记无效" }
    val rows = requireNotNull(json.optJSONArray("data")) { "录音卡目录数据缺失" }
    return (0 until rows.length()).map { index ->
        val row = rows.getJSONObject(index)
        RecorderFile(row.getString("name"), row.getLong("size"), row.getLong("time")).also {
            require(it.name.isNotBlank() && it.name.length <= 255 && '\u0000' !in it.name && it.size >= 0 && it.seconds >= 0) {
                "录音卡目录记录无效"
            }
        }
    }
}

internal fun recorderDatedFiles(files: List<RecorderFile>): List<RecorderDatedFile> {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd EEEE", Locale.SIMPLIFIED_CHINESE)
    return files.map { file ->
        val time = recorderStart(file.name)
        RecorderDatedFile(file, time?.let {
            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(formatter)
        } ?: "日期未知", time)
    }.sortedWith(compareByDescending<RecorderDatedFile> { it.started ?: Long.MIN_VALUE }.thenBy { it.file.name })
}

// shortcut: SDK 只提供完整目录；这里只分页渲染，厂商提供分页协议后再分批读取。
internal fun recorderFilePage(files: List<RecorderDatedFile>, day: String?, page: Int): RecorderFilePage {
    val filtered = if (day == null) files else files.filter { it.day == day }
    val pages = maxOf(1, (filtered.size + RECORDER_PAGE_SIZE - 1) / RECORDER_PAGE_SIZE)
    val index = page.coerceIn(0, pages - 1)
    return RecorderFilePage(filtered.drop(index * RECORDER_PAGE_SIZE).take(RECORDER_PAGE_SIZE), index, pages, filtered.size)
}
