package app.resound.skills.core
import app.resound.skills.*
import kotlinx.datetime.LocalDate
object SkillRunRules {
 fun skip(skill: Skill,date: LocalDate,kind: String,history: List<SkillRunData>,started: Long,skipGap: Boolean): String? {
  val auto = kind == SkillSchedule.DAILY || kind == SkillSchedule.AFTER
        if (auto) {
            // 每天 6 次只限定时技能；整理后技能已有“至少隔 N 分钟”，再限次数会让下午、晚上的事等到第二天（10-25 实测）。
            if (!skill.enabled || (kind == SkillSchedule.DAILY && SkillSchedule.autoCount(history, SkillSchedule.date(started)) >= 6)) return "今天自动运行已达上限或技能已关闭"
            if (kind == SkillSchedule.DAILY && date == SkillSchedule.date(started) && SkillSchedule.next(skill, history, started) != started) return "尚未到运行时间"
            if (kind == SkillSchedule.AFTER) {
                val trigger = skill.trigger as? SkillTrigger.AfterDigest ?: return "不是整理后技能"
                val last = history.lastOrNull { it.kind != SkillSchedule.TRIAL && it.date == date.toEpochDay() }
                if (!skipGap && last != null && started - last.at < trigger.minGapMinutes * 60_000L) return "距离上次运行太近"
            }
        }
  return null
 }
 fun resultText(result: SkillRunResult,gateDiscarded: Int): String {
  val text = when(result) {
   is SkillRunResult.Cards -> "出了 ${result.cards.size} 张卡"
   is SkillRunResult.Nothing -> result.reason
   is SkillRunResult.Failed -> result.reason
  }
  return text + if(gateDiscarded > 0) "；门槛复核丢弃 $gateDiscarded 张" else ""
 }
}
