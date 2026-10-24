package app.resound.ui.preview

import app.resound.skills.CardQuote
import app.resound.skills.FieldType
import app.resound.skills.FieldValue
import app.resound.skills.Skill
import app.resound.skills.SkillCard
import app.resound.skills.SkillField
import app.resound.skills.SkillOrigin
import app.resound.skills.SkillRange
import app.resound.skills.SkillRepository
import app.resound.skills.SkillRunResult
import app.resound.skills.SkillScope
import app.resound.skills.SkillSpeakers
import app.resound.skills.SkillStatus
import app.resound.skills.SkillTrigger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.ZoneId

/** 技能后端（卡 031）合并前界面用的假数据 */
class FakeSkillRepository : SkillRepository {
    private val zone = ZoneId.systemDefault()
    private fun at(date: LocalDate, h: Int, m: Int) = date.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private val meetingFields = listOf(
        SkillField("topic", "议题", FieldType.Text, "", true),
        SkillField("conclusion", "结论", FieldType.Paragraph, "", true),
        SkillField("points", "要点", FieldType.Bullets, ""),
        SkillField("actions", "行动项", FieldType.Checklist, ""),
        SkillField("people", "参与的人", FieldType.Tags, ""),
        SkillField("quotes", "关键原话", FieldType.Quotes, ""),
    )
    private val eveningFields = listOf(
        SkillField("done", "今天做了什么", FieldType.Bullets, "", true),
        SkillField("progress", "推进到哪", FieldType.Paragraph, ""),
        SkillField("tomorrow", "明天计划", FieldType.Checklist, ""),
        SkillField("line", "收工一句话", FieldType.Text, ""),
    )
    private val weeklyFields = listOf(
        SkillField("one_line", "这周一句话", FieldType.Text, "", true),
        SkillField("highlights", "本周要紧的", FieldType.Bullets, ""),
        SkillField("trends", "本周趋势", FieldType.Bullets, ""),
        SkillField("next_week", "下周留意", FieldType.Bullets, ""),
    )

    private val _skills = MutableStateFlow(listOf(
        Skill("timeline", "时间线整理", "把说过的话整理成一件件事，并提取待办、灵感等", "timeline", SkillOrigin.Core, 1, true,
            SkillTrigger.AfterDigest(0), SkillScope(), emptyList(), 99, false, null, false),
        Skill("review", "每日回顾", "每晚把一天压缩成一页：要紧的、发生了什么、待办、灵感", "review", SkillOrigin.Core, 1, true,
            SkillTrigger.Daily(22 * 60), SkillScope(), emptyList(), 1, true, null, false),
        Skill("meeting", "会议速记", "开完会，结论、要点、行动项已经整理好", "meeting", SkillOrigin.Builtin, 1, true,
            SkillTrigger.AfterDigest(60), SkillScope(SkillRange.SinceLastRun), meetingFields, 8, false, null, false),
        Skill("evening_note", "收工打卡", "18:00 把今天做了什么、明天计划整理成一张卡", "sunset", SkillOrigin.Builtin, 1, true,
            SkillTrigger.Daily(18 * 60), SkillScope(speakers = SkillSpeakers.MeOnly), eveningFields, 1, true, null, false),
        Skill("weekly", "每周回顾", "周日晚上回看这一周：要紧的、趋势、下周留意", "calendar_week", SkillOrigin.Builtin, 1, false,
            SkillTrigger.Daily(21 * 60, setOf(7)), SkillScope(SkillRange.Week, includeLines = false), weeklyFields, 1, true, null, false),
    ))
    override val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    private val today = LocalDate.now()
    private val _cards = MutableStateFlow(listOf(
        SkillCard(1, "meeting", "会议速记", "meeting", today, at(today, 10, 30), at(today, 11, 15), "回响技能框架方案评审",
            "定了用统一卡片样式、支持手动生成，卡片放进记忆按天里。", meetingFields, mapOf(
                "topic" to FieldValue(text = "回响要不要借鉴百宝箱做技能"),
                "conclusion" to FieldValue(text = "做。卡片用回响统一样式，可以手动生成，不单独加选项卡，放进记忆按天和按类。"),
                "points" to FieldValue(items = listOf("技能只是说明，不含程序", "自定义分五层：开关、改要求、改卡片、复制、新建", "每个技能都能试跑预览")),
                "actions" to FieldValue(items = listOf("Claude：做技能页和卡片显示", "Codex：技能框架后端"), checked = listOf(false, false)),
                "people" to FieldValue(items = listOf("我", "Claude")),
                "quotes" to FieldValue(quotes = listOf(CardQuote(null, "我", at(today, 10, 42), "我同意你的建议，按照你的最佳方案，开始干吧"))),
            ), emptyList(), at(today, 11, 20), false),
    ))

    override fun cards(date: LocalDate): Flow<List<SkillCard>> = _cards.map { l -> l.filter { it.date == date }.sortedBy { it.at } }
    override fun cardsOf(skillId: String): Flow<List<SkillCard>> = _cards.map { l -> l.filter { it.skillId == skillId }.sortedByDescending { it.at } }
    override fun card(id: Long): Flow<SkillCard?> = _cards.map { l -> l.firstOrNull { it.id == id } }
    override val skillsWithCards: Flow<List<String>> = _cards.map { l -> l.sortedByDescending { it.at }.map { it.skillId }.distinct() }

    private val _status = MutableStateFlow(mapOf(
        "meeting" to SkillStatus("meeting", false, at(today, 11, 20), "出了 1 张卡", false, null, 3200),
        "evening_note" to SkillStatus("evening_note", false, null, null, false, at(today, 18, 0), 0),
        "weekly" to SkillStatus("weekly", false, null, null, false, null, 0),
    ))
    override val status: StateFlow<Map<String, SkillStatus>> = _status.asStateFlow()

    private fun edit(id: String, f: (Skill) -> Skill) = _skills.update { l -> l.map { if (it.id == id && it.origin != SkillOrigin.Core) f(it) else it } }
    override fun setEnabled(skillId: String, enabled: Boolean) = edit(skillId) { it.copy(enabled = enabled) }
    override fun setSchedule(skillId: String, minutes: Int, weekdays: Set<Int>) = edit(skillId) { it.copy(trigger = SkillTrigger.Daily(minutes, weekdays)) }
    override fun setPush(skillId: String, push: Boolean) = edit(skillId) { it.copy(push = push) }
    override fun setPreset(skillId: String, presetId: String?) = edit(skillId) { it.copy(presetId = presetId) }

    private val prompts = mutableMapOf<String, String>()
    override fun defaultPrompt(skillId: String) = "（示例要求）只有多人围绕具体议题、持续约 10 分钟以上的讨论才算会议。结论先行；行动项写清谁做。"
    override fun prompt(skillId: String) = prompts[skillId] ?: defaultPrompt(skillId)
    override fun savePrompt(skillId: String, text: String): Result<Unit> {
        if (text.isBlank()) return Result.failure(IllegalArgumentException("要求不能为空"))
        prompts[skillId] = text; edit(skillId) { it.copy(promptCustomized = true) }; return Result.success(Unit)
    }
    override fun resetPrompt(skillId: String) { prompts.remove(skillId); edit(skillId) { it.copy(promptCustomized = false) } }

    override suspend fun runNow(skillId: String, date: LocalDate): SkillRunResult {
        delay(1200)
        return SkillRunResult.Nothing("示例数据：这天没有够格的内容")
    }
    override suspend fun trial(skillId: String, date: LocalDate, promptOverride: String?): SkillRunResult {
        delay(1200)
        return SkillRunResult.Cards(_cards.value.take(1).map { it.copy(id = -1, date = date) })
    }
    override fun deleteCard(id: Long) = _cards.update { l -> l.filterNot { it.id == id } }

    override fun copyAsMine(skillId: String): Result<String> {
        val src = _skills.value.firstOrNull { it.id == skillId } ?: return Result.failure(IllegalArgumentException("没有这个技能"))
        val id = "my_$skillId"
        _skills.update { it + src.copy(id = id, name = src.name + "（我的）", origin = SkillOrigin.Mine) }
        return Result.success(id)
    }
    override fun saveMine(skill: Skill, prompt: String): Result<Unit> { _skills.update { l -> l.map { if (it.id == skill.id) skill else it } }; return Result.success(Unit) }
    override suspend fun draft(conversation: List<app.resound.skills.DraftTurn>): Result<app.resound.skills.DraftStep> {
        delay(1200)
        if (conversation.count { it.fromUser } < 2) return Result.success(app.resound.skills.DraftStep.Ask("想每天什么时候看？", listOf("每晚 21:00", "每次发生时")))
        return Result.success(app.resound.skills.DraftStep.Done(app.resound.skills.SkillDraft(
            _skills.value.first { it.id == "evening_note" }.copy(id = "my_draft", name = "示例技能", origin = SkillOrigin.Mine), "（示例要求）")))
    }
    override fun deleteMine(skillId: String) = _skills.update { l -> l.filterNot { it.id == skillId && it.origin == SkillOrigin.Mine } }
}
