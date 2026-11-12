package app.resound.skills

import org.json.JSONArray
import org.json.JSONObject
import app.resound.net.Obj
import app.resound.net.Arr
import app.resound.skills.core.SkillJson as Core

/** 安卓 JSON 兼容边界；规则统一在 shared。 */
object SkillJson {
 fun validKey(key: String) = Core.validKey(key)
 fun validate(s: Skill) = Core.validate(s)
 fun parse(text: String, origin: SkillOrigin) = Core.parse(text, origin)
 fun encode(d: SkillDefinition) = Core.encode(d)
 fun trigger(j: JSONObject) = Core.trigger(Obj(j.toString()))
 fun trigger(t: SkillTrigger) = JSONObject(Core.trigger(t).toString())
 fun fields(a: JSONArray) = Core.fields(Arr(a.toString()))
 fun fields(fields: List<SkillField>) = JSONArray(Core.fields(fields).toString())
 fun values(j: JSONObject) = Core.values(Obj(j.toString()))
 fun values(values: Map<String, FieldValue>) = JSONObject(Core.values(values).toString())
}
internal fun JSONArray?.elements(): List<Any> = if (this == null) emptyList() else (0 until length()).map { get(it) }
internal fun JSONObject.nullString(key: String): String? = if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }
