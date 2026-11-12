package app.resound.platform

import kotlinx.serialization.json.Json

/** 后续 JSON 迁移共用的配置；严格校验仍由各协议负责。 */
val SharedJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** 带缩进的输出（写入技能文件、调试展示）；全局只建一次，避免每次调用都新建格式对象 */
val PrettyJson = Json { prettyPrint = true }
