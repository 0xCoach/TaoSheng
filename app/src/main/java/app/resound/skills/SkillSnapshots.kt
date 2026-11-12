package app.resound.skills
import app.resound.skills.core.*
import app.resound.digest.snapshot
internal fun SkillCardRow.snapshot() = SkillCardData(id = id, skillId = skillId, skillVersion = skillVersion, skillName = skillName, icon = icon, date = date, entity = entity, at = at, endAt = endAt, title = title, summary = summary, fieldsJson = fieldsJson, valuesJson = valuesJson, evidenceJson = evidenceJson, generatedAt = generatedAt, manual = manual, deletedAt = deletedAt, aliasesJson = aliasesJson)
internal fun SkillCardData.android() = SkillCardRow(id = id, skillId = skillId, skillVersion = skillVersion, skillName = skillName, icon = icon, date = date, entity = entity, at = at, endAt = endAt, title = title, summary = summary, fieldsJson = fieldsJson, valuesJson = valuesJson, evidenceJson = evidenceJson, generatedAt = generatedAt, manual = manual, deletedAt = deletedAt, aliasesJson = aliasesJson)
internal fun SkillRunRow.snapshot() = SkillRunData(id = id, skillId = skillId, date = date, at = at, ok = ok, result = result, tokens = tokens, kind = kind, retryable = retryable, purpose = purpose)
internal fun SkillRunData.android() = SkillRunRow(id = id, skillId = skillId, date = date, at = at, ok = ok, result = result, tokens = tokens, kind = kind, retryable = retryable, purpose = purpose)
internal open class RoomSkillInputStore(private val dao: SkillDao) : SkillInputStore {
 override suspend fun events(start: Long, end: Long, since: Long, until: Long) = dao.events(start,end,since,until).map { it.snapshot() }
 override suspend fun lines(start: Long, end: Long) = dao.lines(start,end).map { it.skillSnapshot() }
 override suspend fun eventLines(events: List<Long>) = dao.eventLines(events).map { it.skillSnapshot() }
 override suspend fun items(events: List<Long>) = dao.items(events).map { it.snapshot() }
 override suspend fun standaloneItems(start: Long,end: Long) = dao.standaloneItems(start,end).map { it.snapshot() }
 override suspend fun starredEvents(start: Long,end: Long,until: Long) = dao.starredEvents(start,end,until).map { it.snapshot() }
 override suspend fun reviews(start: String,end: String) = dao.reviews(start,end).map { SkillReviewData(it.startDate,it.brief,it.sectionsJson) }
 override suspend fun dayRows(id: String,date: Long) = dao.dayRows(id,date).map { it.snapshot() }
}
internal fun SkillInput.snapshot() = app.resound.skills.core.SkillInput(data, lines.mapValues { it.value.skillSnapshot() })

internal class RoomSkillRunStore(private val dao: SkillDao) : RoomSkillInputStore(dao), SkillRunStore {
 override suspend fun runsOf(id: String) = dao.runsOf(id).map { it.snapshot() }
 override suspend fun newEventStarts(since: Long,until: Long) = dao.newEventStarts(since,until)
 override suspend fun mergeWithReason(row: SkillCardData,max: Int,manual: Boolean): app.resound.skills.core.SkillMergeResult =
  when(val result = dao.mergeWithReason(row.android(),max,manual)) {
   SkillMergeResult.Deleted -> app.resound.skills.core.SkillMergeResult.Deleted
   is SkillMergeResult.Full -> app.resound.skills.core.SkillMergeResult.Full(result.count)
   is SkillMergeResult.Saved -> app.resound.skills.core.SkillMergeResult.Saved(result.row.snapshot(),result.isNew)
  }
 override suspend fun run(row: SkillRunData) { dao.run(row.android()) }
 override suspend fun trimRuns(before: Long) = dao.trimRuns(before)
}

/** 沿用旧模型输入的宽容 JSON 边界；共用层只接收规范 JSON，坏元数据仍保留为不可解析。 */
internal fun app.resound.data.TranscriptLineEntity.skillSnapshot() = snapshot().let { line ->
 line.copy(voiceInputJson = line.voiceInputJson?.let { raw -> runCatching { org.json.JSONObject(raw).toString() }.getOrDefault(raw) })
}
