package app.resound.envsound

import app.resound.AppGraph
import app.resound.review.DailyReview
import app.resound.skills.*
import kotlinx.coroutines.flow.*
import java.time.DayOfWeek

/** 仅在显示/导出阶段附加，不写事件摘要、不进入整理或回顾提示词。 */
object EnvSoundPresentation {
    internal fun changes(): Flow<Unit> {
        val repo = if(AppGraph.isEnvSoundReady) AppGraph.envSound as? LocalEnvSoundRepository else null
        return if(repo == null) flowOf(Unit) else combine(repo.settings, repo.presentation, repo.eventMetadata) { _, _, _ -> Unit }
    }
    private val weeklyEnvironmentKeys = setOf("env_sound_overview", "env_sound_observations", "env_sound_impression")
    private fun withoutWeeklyEnvironment(card: SkillCard) = card.copy(
        fields = card.fields.filter { it.key !in weeklyEnvironmentKeys }, values = card.values - weeklyEnvironmentKeys)

    /** 周报仅在页尾显示声音标签；旧逐事件环境字段也在读取时移除，不改存档。 */
    fun decorate(card: SkillCard): SkillCard = decorateCurrent(if (card.skillId == "weekly") withoutWeeklyEnvironment(card) else card)
    private fun decorateCurrent(card: SkillCard): SkillCard {
        if(!AppGraph.isEnvSoundReady) return card
        val repo = AppGraph.envSound as? LocalEnvSoundRepository ?: return card
        if(!repo.settings.value.showInSummaries) return card
        val start = if(card.skillId == "weekly") card.date.with(DayOfWeek.MONDAY).atStartOfDay(SKILL_ZONE).toInstant().toEpochMilli() else card.at
        val end = if(card.skillId == "weekly") card.date.plusDays(1).atStartOfDay(SKILL_ZONE).toInstant().toEpochMilli() else card.endAt ?: return card
        val events = repo.eventMetadata.value.filter { it.startAt < end && it.endAt > start && it.createdAt <= card.generatedAt }
            .sortedBy { it.startAt }
        if (card.skillId == "weekly") return weekly(card, events.mapNotNull { repo.presentation.value[it.id] })
        val notes = events.mapNotNull { e -> line(e.id).takeIf { it.isNotBlank() }?.let { "${e.title}：$it" } }
        if(notes.isEmpty()) return card
        val key = "env_sound_observations"
        val detail = SkillField(key,"各件事的环境",FieldType.Bullets,"")
        return card.copy(fields = card.fields.filter { it.key != key } + detail,
            values = card.values + (key to FieldValue(items=notes)))
    }
    /** 标签只陈述出现过的声音，最多三个，不推断环境时长、生活地点或情绪。 */
    internal fun weekly(card: SkillCard, sounds: List<EventSounds>): SkillCard {
        val clean = withoutWeeklyEnvironment(card)
        val special = sounds.flatMap { it.sounds }.distinct().sortedBy { it.ordinal }.map { if(it == SpecialSound.Knock) "敲门声" else it.title }
        val tags = sounds.flatMap { it.tags }.distinct().sortedBy { it.ordinal }.map { it.title }
        val impression = (special + tags).distinct().take(3)
        if (impression.isEmpty()) return clean
        val key = "env_sound_impression"
        return clean.copy(fields = clean.fields + SkillField(key, "本周声音印象", FieldType.Tags, ""),
            values = clean.values + (key to FieldValue(items=impression)))
    }
    fun line(eventId: Long?): String {
        if(eventId == null || !AppGraph.isEnvSoundReady) return ""
        val repo = AppGraph.envSound as? LocalEnvSoundRepository ?: return ""
        if(!repo.settings.value.showInSummaries) return ""
        val value = repo.presentation.value[eventId] ?: return ""
        return listOfNotNull(value.tags.takeIf { it.isNotEmpty() }?.joinToString("、", prefix="环境：") { it.title },
            value.sounds.takeIf { it.isNotEmpty() }?.joinToString("、", prefix="期间有") { if(it == SpecialSound.Knock) "敲门声" else it.title }).joinToString("；")
    }
    fun decorate(review: DailyReview): DailyReview = review.copy(sections = review.sections.map { section ->
        section.copy(lines = section.lines.mapIndexed { i, text -> append(text, section.eventIds.getOrNull(i)) })
    })
    fun append(text: String, eventId: Long?): String = line(eventId).let { if(it.isBlank() || text.endsWith(it)) text else "$text\n$it" }
}
