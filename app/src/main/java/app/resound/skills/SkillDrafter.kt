package app.resound.skills

import app.resound.appDisplayName
import org.json.JSONObject
import app.resound.skills.core.SkillDrafter as Core
object SkillDrafter {
 val SYSTEM get() = Core.system(appDisplayName)
 const val MAX_ASKS = Core.MAX_ASKS
 fun user(conversation: List<DraftTurn>) = Core.user(conversation)
 fun step(content: String, id: String, mustFinish: Boolean) = Core.step(JSONObject(content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()).toString(), id, mustFinish)
 fun parse(j: JSONObject, id: String) = Core.parse(app.resound.net.Obj(j.toString()), id)
}
