package app.resound.skills

import org.json.JSONArray
import org.json.JSONObject

/** 文件格式独立于界面约定；来源元数据不混进用户覆盖项。 */
data class SkillDefinition(val skill: Skill, val order: Int = 0, val sourceId: String? = null, val sourceVersion: Int? = null)

object SkillJson {
    private val keyPattern = Regex("[a-z0-9_]+")
    fun validKey(key: String) = key.matches(keyPattern)
    fun validate(s: Skill) {
        require(validKey(s.id)) { "技能 id 只能使用小写英文、数字和下划线" }
        require(s.name.isNotBlank() && s.version >= 1)
        require(s.maxCardsPerDay in 1..20)
        require(s.fields.isNotEmpty() && s.fields.map { it.key }.distinct().size == s.fields.size)
        s.fields.forEach { require(validKey(it.key) && it.label.isNotBlank()) { "字段 key 无效或名称为空" } }
        when (val t = s.trigger) {
            is SkillTrigger.Daily -> require(t.minutes in 0..1439 && t.weekdays.all { it in 1..7 })
            is SkillTrigger.AfterDigest -> require(t.minGapMinutes >= 0)
            SkillTrigger.Manual -> Unit
        }
    }
    fun parse(text: String, origin: SkillOrigin): SkillDefinition {
        val j = JSONObject(text)
        require(j.getInt("formatVersion") == 1) { "不支持的技能文件格式" }
        val scope = j.getJSONObject("scope")
        val s = Skill(j.getString("id"), j.getString("name"), j.getString("summary"), j.getString("icon"),
            origin, j.getInt("version"), j.optBoolean("enabled", true), trigger(j.getJSONObject("trigger")),
            SkillScope(SkillRange.valueOf(scope.getString("range")), SkillSpeakers.valueOf(scope.getString("speakers")), scope.getBoolean("includeLines")),
            fields(j.getJSONArray("fields")), j.getInt("maxCardsPerDay"), j.getBoolean("push"), j.nullString("presetId"), false)
        validate(s)
        val source = j.nullString("sourceId")
        require(source == null || validKey(source))
        return SkillDefinition(s, j.optInt("order", 0), source, if (j.isNull("sourceVersion")) null else j.getInt("sourceVersion"))
    }
    fun trigger(j: JSONObject): SkillTrigger = when (j.getString("type")) {
        "Daily" -> SkillTrigger.Daily(j.getInt("minutes"), j.optJSONArray("weekdays").elements().map { (it as Number).toInt() }.toSet())
        "AfterDigest" -> SkillTrigger.AfterDigest(j.getInt("minGapMinutes"))
        "Manual" -> SkillTrigger.Manual
        else -> error("未知触发方式")
    }
    fun trigger(t: SkillTrigger) = JSONObject().apply {
        when (t) {
            is SkillTrigger.Daily -> put("type", "Daily").put("minutes", t.minutes).put("weekdays", JSONArray(t.weekdays.sorted()))
            is SkillTrigger.AfterDigest -> put("type", "AfterDigest").put("minGapMinutes", t.minGapMinutes)
            SkillTrigger.Manual -> put("type", "Manual")
        }
    }
    fun fields(a: JSONArray): List<SkillField> = a.elements().map { raw ->
        val f = raw as JSONObject
        SkillField(f.getString("key"), f.getString("label"), FieldType.valueOf(f.getString("type")), f.getString("hint"), f.optBoolean("required"))
    }
    fun fields(fields: List<SkillField>) = JSONArray(fields.map { f -> JSONObject().put("key", f.key).put("label", f.label)
        .put("type", f.type.name).put("hint", f.hint).put("required", f.required) })
    fun encode(d: SkillDefinition): String = with(d.skill) {
        JSONObject().put("formatVersion", 1).put("id", id).put("name", name).put("summary", summary).put("icon", icon)
            .put("version", version).put("enabled", enabled).put("trigger", trigger(trigger))
            .put("scope", JSONObject().put("range", scope.range.name).put("speakers", scope.speakers.name).put("includeLines", scope.includeLines))
            .put("fields", fields(fields)).put("maxCardsPerDay", maxCardsPerDay).put("push", push).put("presetId", presetId ?: JSONObject.NULL)
            .put("order", d.order).put("sourceId", d.sourceId ?: JSONObject.NULL).put("sourceVersion", d.sourceVersion ?: JSONObject.NULL).toString(2)
    }
    fun values(values: Map<String, FieldValue>) = JSONObject().apply {
        values.forEach { (key, v) -> put(key, JSONObject().put("text", v.text ?: JSONObject.NULL).put("items", JSONArray(v.items))
            .put("checked", JSONArray(v.checked)).put("number", v.number ?: JSONObject.NULL).put("unit", v.unit ?: JSONObject.NULL)
            .put("rating", v.rating ?: JSONObject.NULL).put("quotes", JSONArray(v.quotes.map { q -> JSONObject()
                .put("lineId", q.lineId ?: JSONObject.NULL).put("speaker", q.speaker).put("at", q.at).put("text", q.text) }))) }
    }
    fun values(j: JSONObject): Map<String, FieldValue> = j.keys().asSequence().associateWith { key ->
        val v = j.getJSONObject(key)
        FieldValue(v.nullString("text"), v.optJSONArray("items").elements().map { it.toString() },
            v.optJSONArray("quotes").elements().map { raw -> val q = raw as JSONObject
                CardQuote(if (q.isNull("lineId")) null else q.getLong("lineId"), q.getString("speaker"), q.getLong("at"), q.getString("text")) },
            if (v.isNull("number")) null else v.getDouble("number"), v.nullString("unit"),
            v.optJSONArray("checked").elements().map { it == true }, if (v.isNull("rating")) null else v.getInt("rating"))
    }
}
internal fun JSONArray?.elements(): List<Any> = if (this == null) emptyList() else (0 until length()).map { get(it) }
internal fun JSONObject.nullString(key: String): String? = if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }
