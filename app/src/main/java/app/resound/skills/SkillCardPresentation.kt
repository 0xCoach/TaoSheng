package app.resound.skills

import org.json.JSONArray
import org.json.JSONObject

fun SkillCardRow.card() = app.resound.envsound.EnvSoundPresentation.decorate(SkillCard(id, skillId, skillName, icon, kotlinx.datetime.LocalDate.fromEpochDays(date.toInt()), at, endAt, title, summary,
        SkillJson.fields(JSONArray(fieldsJson)), SkillJson.values(JSONObject(valuesJson)),
        JSONArray(evidenceJson).elements().map { (it as Number).toLong() }, generatedAt, manual))
    fun SkillCardRow.entities() = JSONArray(aliasesJson).elements().map { it.toString() }.toSet() + entity
