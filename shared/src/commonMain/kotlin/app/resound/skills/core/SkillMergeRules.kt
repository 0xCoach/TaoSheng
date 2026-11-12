package app.resound.skills.core
import app.resound.net.Arr as JSONArray
sealed interface SkillMergePlan {
 data class Saved(val row: SkillCardData, val isNew: Boolean, val removeIds: List<Long>) : SkillMergePlan
 data object Deleted : SkillMergePlan
 data class Full(val count: Int) : SkillMergePlan
}
object SkillMergeRules {
 fun merge(incoming: SkillCardData,existing: List<SkillCardData>,max: Int,manual: Boolean): SkillMergePlan {

        if (!manual && existing.any { it.deletedAt != null && incoming.entity in it.entities() }) return SkillMergePlan.Deleted
        var matches = existing.filter { (manual || it.deletedAt == null) && incoming.entity in it.entities() }
        var start = minOf(incoming.at, matches.minOfOrNull { it.at } ?: incoming.at)
        var end = (matches.mapNotNull { it.endAt } + listOfNotNull(incoming.endAt)).maxOrNull()
        // 闭包处理：新时段可能连上两张此前分开的会议卡。
        if (incoming.endAt != null) do {
            val previous = matches.size
            if (!manual && existing.any { it.deletedAt != null && it.endAt != null && it.at <= end!! && it.endAt >= start }) return SkillMergePlan.Deleted
            matches = existing.filter { (manual || it.deletedAt == null) && (it in matches || (it.endAt != null && it.at <= end!! && it.endAt >= start)) }
            start = minOf(start, matches.minOfOrNull { it.at } ?: start)
            end = (matches.mapNotNull { it.endAt } + listOfNotNull(end)).maxOrNull()
        } while (matches.size != previous)
        val activeCount = existing.count { it.deletedAt == null }
        if (matches.none { it.deletedAt == null } && activeCount >= max) return SkillMergePlan.Full(activeCount)
        val keep = matches.minByOrNull { it.id }
        val allEvidence = (matches.flatMap { JSONArray(it.evidenceJson).elements() } + JSONArray(incoming.evidenceJson).elements()).distinct()
        val merged = incoming.copy(id = keep?.id ?: 0, entity = keep?.entity ?: incoming.entity,
            at = if (incoming.endAt != null) start else incoming.at, endAt = end,
            aliasesJson = JSONArray((matches.flatMap { it.entities() } + incoming.entity).distinct()).toString(),
            evidenceJson = JSONArray(allEvidence).toString(), deletedAt = null)
        return SkillMergePlan.Saved(merged, matches.none { it.deletedAt == null }, matches.filter { it.id != keep?.id }.map { it.id })
 }
}
