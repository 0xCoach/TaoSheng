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
    /** 周回顾没有逐句事件出处；在输出卡末尾按真实事件单列环境，不让模型猜配对。 */
    fun decorate(card: SkillCard): SkillCard {
        if(!AppGraph.isEnvSoundReady) return card
        val repo = AppGraph.envSound as? LocalEnvSoundRepository ?: return card
        if(!repo.settings.value.showInSummaries) return card
        val start = if(card.skillId == "weekly") card.date.with(DayOfWeek.MONDAY).atStartOfDay(SKILL_ZONE).toInstant().toEpochMilli() else card.at
        val end = if(card.skillId == "weekly") card.date.plusDays(1).atStartOfDay(SKILL_ZONE).toInstant().toEpochMilli() else card.endAt ?: return card
        val events = repo.eventMetadata.value.filter { it.startAt < end && it.endAt > start && it.createdAt <= card.generatedAt }
            .sortedBy { it.startAt }
        val notes = events.mapNotNull { e -> line(e.id).takeIf { it.isNotBlank() }?.let { "${e.title}：$it" } }
        if(notes.isEmpty()) return card
        val key = "env_sound_observations"
        val detail = SkillField(key,"各件事的环境",FieldType.Bullets,"")
        if (card.skillId != "weekly") return card.copy(fields = card.fields.filter { it.key != key } + detail,
            values = card.values + (key to FieldValue(items=notes)))
        return weekly(card, events.mapNotNull { repo.presentation.value[it.id] }, notes)
    }
    /** 概览仅列出现过的类别，不从事件跨度推断环境时长。 */
    internal fun weekly(card: SkillCard, sounds: List<EventSounds>, notes: List<String>): SkillCard {
        val tags = sounds.flatMap { it.tags }.distinct().sortedBy { it.ordinal }.map { it.title }
        val special = sounds.flatMap { it.sounds }.distinct().sortedBy { it.ordinal }.map { if(it == SpecialSound.Knock) "敲门声" else it.title }
        val overview = listOfNotNull(tags.takeIf { it.isNotEmpty() }?.joinToString("、", prefix="记录到的环境："),
            special.takeIf { it.isNotEmpty() }?.joinToString("、", prefix="特别声音："))
        val keys = setOf("env_sound_overview", "env_sound_observations")
        return card.copy(fields = card.fields.filter { it.key !in keys } + listOf(
            SkillField("env_sound_overview", "本周环境", FieldType.Bullets, ""),
            SkillField("env_sound_observations", "各件事的环境", FieldType.Bullets, "")),
            values = card.values + mapOf("env_sound_overview" to FieldValue(items=overview),
                "env_sound_observations" to FieldValue(items=notes)))
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
