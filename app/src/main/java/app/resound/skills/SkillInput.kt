package app.resound.skills

// 同名类型与本文件声明或已有 import 冲突，以下引用保留全限定名：
// app.resound.skills.core.SkillPrompt
// app.resound.skills.core.SkillSource

import app.resound.digest.core.TranscriptLineData
import app.resound.appDisplayName
import app.resound.data.TranscriptLineEntity
import app.resound.digest.snapshot
import kotlinx.datetime.toKotlinLocalDate
import java.time.LocalDate
import java.time.ZoneId
import app.resound.skills.core.SkillInputs as Core
fun SKILL_ZONE(at: Long): ZoneId = ZoneId.of(app.resound.platform.HistoricalZones.history().zoneAt(at).id)
fun SKILL_ZONE(date: LocalDate): ZoneId = ZoneId.of(app.resound.platform.HistoricalZones.history().zonesOn(date.toKotlinLocalDate()).first().zoneId)
data class SkillInput(val data: String, val lines: Map<String, TranscriptLineEntity>)
internal typealias SkillSource = app.resound.skills.core.SkillSource
object SkillInputs {
 fun time(at: Long) = Core.time(at)
 fun speaker(l: TranscriptLineEntity) = Core.speaker(l.snapshot())
 fun boundedLines(raw: List<TranscriptLineEntity>, speakers: SkillSpeakers, limit: Int = 40_000): List<TranscriptLineEntity> {
  val byId = raw.associateBy { it.id }
  return Core.boundedLines(raw.map { it.snapshot() }, speakers, limit).map { byId.getValue(it.id) }
 }
 internal fun gateLines(raw: List<TranscriptLineData>, speakers: SkillSpeakers, events: List<app.resound.digest.core.MemoryEventData> = emptyList()) = Core.gateLines(raw,speakers,events)
 suspend fun load(dao: SkillDao,skill: Skill,date: LocalDate,since: Long?,until: Long) = prepare(dao,skill,date,until,source(dao,skill,date,since,until))
 internal suspend fun source(dao: SkillDao,skill: Skill,date: LocalDate,since: Long?,until: Long) = Core.source(RoomSkillInputStore(dao),skill,date.toKotlinLocalDate(),since,until)
 internal suspend fun prepare(dao: SkillDao,skill: Skill,date: LocalDate,until: Long,source: SkillSource): SkillInput {
  val value = Core.prepare(RoomSkillInputStore(dao),skill,date.toKotlinLocalDate(),until,source)
  return SkillInput(value.data,value.lines.mapValues { (_,l) -> TranscriptLineEntity(l.id,l.segmentId,l.startAt,l.endAt,l.text,l.speakerTag,l.speakerName,l.eventId,l
      .digestedAt,l.speakerScore,l.speakerPersonId,l.speakerManual,l.voiceInputJson,l.voiceInputPendingUntil,l.originalText) })
 }
 fun user(input: SkillInput,hotwords: List<String>) = Core.user(input.snapshot(),hotwords)
}
object SkillPrompt {
 val RULES get() = app.resound.skills.core.SkillPrompt.rules(appDisplayName, app.resound.platform.HistoricalZones.history().label(System.currentTimeMillis()))
 fun system(s: Skill,requirements: String,date: LocalDate) = app.resound.skills.core.SkillPrompt.system(s,requirements,date.toKotlinLocalDate(),appDisplayName)
}
