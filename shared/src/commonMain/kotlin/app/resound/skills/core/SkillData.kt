package app.resound.skills.core
import app.resound.skills.*
import kotlinx.datetime.LocalDate
import app.resound.net.Obj as JSONObject
import app.resound.net.Arr as JSONArray

data class SkillCardData(
    val id: Long = 0,
    val skillId: String, val skillVersion: Int, val skillName: String, val icon: String,
    val date: Long, val entity: String, val at: Long, val endAt: Long?, val title: String, val summary: String,
    val fieldsJson: String, val valuesJson: String, val evidenceJson: String,
    val generatedAt: Long, val manual: Boolean, val deletedAt: Long? = null,
    /** 合并后的旧 entity 也要保留，避免换 entity 后软删除失效。 */
    val aliasesJson: String = "[]",
) {
    fun card() = SkillCard(id, skillId, skillName, icon, LocalDate.fromEpochDays(date.toInt()), at, endAt, title, summary,
        SkillJson.fields(JSONArray(fieldsJson)), SkillJson.values(JSONObject(valuesJson)),
        JSONArray(evidenceJson).elements().map { (it as Number).toLong() }, generatedAt, manual)
    fun entities() = JSONArray(aliasesJson).elements().map { it.toString() }.toSet() + entity
}

data class SkillRunData(
    val id: Long = 0,
    val skillId: String, val date: Long, val at: Long, val ok: Boolean, val result: String, val tokens: Long,
    val kind: String, val retryable: Boolean = false,
    val purpose: String = "skill:$skillId",
)
