package app.resound.skills
import kotlinx.coroutines.flow.StateFlow
/** 定义持久化端口；实现负责原子写入并在落盘后发布。 */
interface SkillDefinitionStore {
 fun setEnabled(id: String, enabled: Boolean)
 fun setSchedule(id: String, schedule: SkillTrigger.Daily)
 fun setPush(id: String, push: Boolean)
 fun setPreset(id: String, presetId: String?)
 val skills: StateFlow<List<Skill>>
 fun prompt(id: String): String
 fun defaultPrompt(id: String): String
 fun savePrompt(id: String,text: String): Result<Unit>
 fun resetPrompt(id: String)
 fun copyAsMine(id: String): Result<String>
 fun saveMine(s: Skill,prompt: String): Result<Unit>
 fun deleteMine(id: String)
}
