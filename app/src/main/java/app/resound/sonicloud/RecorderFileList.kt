/*
 * [INPUT]: SDK 目录分片及文件名中的本地录音时间。
 * [OUTPUT]: 经校验的目录记录。
 * [POS]: SDK 目录边界，拒绝缺失或格式错误的记录。
 * [PROTOCOL]: 变更时更新此头部，然后检查 CLAUDE.md
 */
package app.resound.sonicloud

import org.json.JSONObject

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

