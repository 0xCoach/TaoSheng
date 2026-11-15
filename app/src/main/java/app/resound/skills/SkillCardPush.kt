package app.resound.skills

// 同名类型与本文件声明或已有 import 冲突，以下引用保留全限定名：
// app.resound.skills.core.SkillCardPush

import app.resound.OutputTime
object SkillCardPush {
 fun title(card: SkillCard) = app.resound.skills.core.SkillCardPush.title(card)
 fun body(card: SkillCard): String {
  val time = OutputTime()
  return app.resound.skills.core.SkillCardPush.body(card,time::moment,time::event) { "%.1f".format(it) }
 }
}
