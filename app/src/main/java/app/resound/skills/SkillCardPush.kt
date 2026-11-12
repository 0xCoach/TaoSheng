package app.resound.skills
object SkillCardPush {
 fun title(card: SkillCard) = app.resound.skills.core.SkillCardPush.title(card)
 fun body(card: SkillCard): String {
  val time = app.resound.OutputTime()
  return app.resound.skills.core.SkillCardPush.body(card,time::moment,time::event) { "%.1f".format(it) }
 }
}
