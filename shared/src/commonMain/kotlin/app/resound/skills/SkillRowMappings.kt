package app.resound.skills

import app.resound.skills.core.*

fun SkillCardRow.snapshot() = SkillCardData(id = id, skillId = skillId, skillVersion = skillVersion, skillName = skillName, icon = icon, date = date, entity = entity, at = at, endAt = endAt, title = title, summary = summary, fieldsJson = fieldsJson, valuesJson = valuesJson, evidenceJson = evidenceJson, generatedAt = generatedAt, manual = manual, deletedAt = deletedAt, aliasesJson = aliasesJson)
fun SkillCardData.android() = SkillCardRow(id = id, skillId = skillId, skillVersion = skillVersion, skillName = skillName, icon = icon, date = date, entity = entity, at = at, endAt = endAt, title = title, summary = summary, fieldsJson = fieldsJson, valuesJson = valuesJson, evidenceJson = evidenceJson, generatedAt = generatedAt, manual = manual, deletedAt = deletedAt, aliasesJson = aliasesJson)
fun SkillRunRow.snapshot() = SkillRunData(id = id, skillId = skillId, date = date, at = at, ok = ok, result = result, tokens = tokens, kind = kind, retryable = retryable, purpose = purpose)
fun SkillRunData.android() = SkillRunRow(id = id, skillId = skillId, date = date, at = at, ok = ok, result = result, tokens = tokens, kind = kind, retryable = retryable, purpose = purpose)
