package app.resound.skills

/** 文件格式独立于界面约定；来源元数据不混进用户覆盖项。 */
data class SkillDefinition(val skill: Skill, val order: Int = 0, val sourceId: String? = null, val sourceVersion: Int? = null,
    val gateSpecified: Boolean = skill.gate != null)
