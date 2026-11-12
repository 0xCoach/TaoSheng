@file:OptIn(kotlin.time.ExperimentalTime::class)
package app.resound.skills.core
import app.resound.skills.*
import kotlinx.datetime.*
import kotlin.time.Instant

object SkillSchedule {
    const val DAILY = "daily"
    const val AFTER = "after_digest"
    const val MANUAL = "manual"
    const val TRIAL = "trial"
    fun automatic(r: SkillRunData) = r.kind == DAILY || r.kind == AFTER
    fun date(at: Long): LocalDate = Instant.fromEpochMilliseconds(at).toLocalDateTime(SKILL_ZONE).date
    fun autoCount(runs: List<SkillRunData>, day: LocalDate) = runs.count { automatic(it) && date(it.at) == day }
    /** 当天已错过但尚未跑的排到现在；只枚举今天及以后，不补昨天。 */
    fun next(skill: Skill, runs: List<SkillRunData>, now: Long, catchUp: Boolean = true): Long? {
        val t = skill.trigger as? SkillTrigger.Daily ?: return null
        if (!skill.enabled || skill.origin == SkillOrigin.Core) return null
        val today = date(now)
        for (offset in 0L..7L) {
            val day = today.plusDays(offset)
            if (t.weekdays.isNotEmpty() && day.dayOfWeek.isoDayNumber !in t.weekdays) continue
            if (autoCount(runs, day) >= 6) continue
            val time = day.startMillis() + t.minutes * 60_000L
            val period = runs.filter { it.kind == DAILY && it.date == day.toEpochDay() }
            if (period.isNotEmpty()) {
                val last = period.last()
                if (last.ok || !last.retryable || period.size >= 2) continue
                val retry = maxOf(time, last.at + 600_000L)
                if (date(retry) != day) continue
                return maxOf(now, retry)
            }
            if (time > now) return time
            if (catchUp && offset == 0L) return now
        }
        return null
    }
}
