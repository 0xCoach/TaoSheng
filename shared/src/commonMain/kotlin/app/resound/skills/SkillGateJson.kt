package app.resound.skills

import kotlinx.serialization.json.*

/** 缺字段=继承；显式 null=关闭；对象=用户指定。旧文件不需要迁移。 */
data class SkillGateSetting(val gate: SkillGate?, val specified: Boolean)

object SkillGateJson {
    fun read(text: String): SkillGateSetting {
        val root = app.resound.platform.SharedJson.parseToJsonElement(text).jsonObject
        val raw = root["gate"]
        val gate = when (raw) {
            null, JsonNull -> null
            else -> raw.jsonObject.let { j ->
                SkillGate(j.int("minSpeakers", 1), j.int("minSpanMinutes", 0), j.int("minLines", 0),
                    j["countVoiceInput"]?.let {
                        val value = it as? JsonPrimitive
                        require(value != null && !value.isString && value.booleanOrNull != null) { "countVoiceInput 必须是布尔值" }
                        value.boolean
                    } ?: false).also(SkillGateRules::validate)
            }
        }
        return SkillGateSetting(gate, root.containsKey("gate"))
    }

    private fun JsonObject.int(key: String, default: Int): Int {
        if (!containsKey(key)) return default
        val value = get(key) as? JsonPrimitive
        require(value != null && !value.isString && value.intOrNull != null) { "$key 必须是整数" }
        return value.int
    }

    fun write(text: String, gate: SkillGate?, specified: Boolean): String {
        gate?.let(SkillGateRules::validate)
        val root = app.resound.platform.SharedJson.parseToJsonElement(text).jsonObject.toMutableMap()
        if (!specified) root.remove("gate") else root["gate"] = gate?.let {
            buildJsonObject {
                put("minSpeakers", it.minSpeakers)
                put("minSpanMinutes", it.minSpanMinutes)
                put("minLines", it.minLines)
                put("countVoiceInput", it.countVoiceInput)
            }
        } ?: JsonNull
        return app.resound.platform.PrettyJson.encodeToString(JsonObject.serializer(), JsonObject(root))
    }
}
