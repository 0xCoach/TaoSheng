package app.resound.platform

import kotlinx.serialization.json.Json

/** 后续 JSON 迁移共用的配置；严格校验仍由各协议负责。 */
val SharedJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}
