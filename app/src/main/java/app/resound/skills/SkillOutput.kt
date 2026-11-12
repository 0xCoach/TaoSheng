package app.resound.skills
import java.time.LocalDate
import kotlinx.datetime.toKotlinLocalDate
import app.resound.digest.LlmException
import app.resound.digest.LlmError
import app.resound.skills.core.SkillOutput as Core
object SkillOutput {
 data class Parsed(val rows: List<SkillCardRow>, val reason: String)
 fun parse(content: String,skill: Skill,date: LocalDate,input: SkillInput,now: Long,manual: Boolean): Parsed = try {
  val normalized = org.json.JSONObject(content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```")).toString()
  Core.parse(normalized,skill,date.toKotlinLocalDate(),input.snapshot(),now,manual).let { Parsed(it.rows.map { row -> row.android() },it.reason) }
 } catch (_: Exception) { throw LlmException(LlmError.Format) }
}
