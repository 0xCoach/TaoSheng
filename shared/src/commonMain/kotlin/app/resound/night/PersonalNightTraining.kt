package app.resound.night

import app.resound.data.writeTransaction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString

class PersonalNightTraining(private val store: NightStore, private val decodeScores: (ByteArray) -> List<Float>,
                            private val digest: (String) -> String) {
    private val dao get() = store.dao
    private val json get() = store.json

    suspend fun train(at: Long, charging: Boolean) = store.personalGate.withLock { trainLocked(at, charging) }

    private suspend fun trainLocked(at: Long, charging: Boolean) {
        val enabled = store.settings.value.personalModel
        val feedback = if (enabled) dao.feedback() else emptyList()
        val saved = if (enabled) dao.personal().first().associateBy { it.kind } else emptyMap()
        val models = mutableMapOf<String,PersonalWeights>()
        val revisions = mutableListOf<String>()
        for (kind in PersonalKind.entries) {
            val selected = feedback.filter { it.kind == kind.name }
            if (selected.count { it.yes } < 20 || selected.count { !it.yes } < 10) continue
            val row = trainKind(kind.name, selected, saved[kind.name], at, charging) ?: continue
            val model = json.decodeFromString<PersonalWeights>(row.payload)
            if (model.active) {
                models[kind.name] = model
                revisions += "${kind.name}=${row.trainedAt}"
            }
        }
        if (models.isEmpty()) {
            clearInactive()
            return
        }
        if (dao.notice("personal-inactive")?.muted != false) dao.notice(NightNoticeRow("personal-inactive",at,false))
        // 只持久化摘要；进程重启后仍能跳过已按相同权重、反馈判断过的夜晚。
        val signature = revisions + feedback.map { "${it.eventId}=${it.yes}" }.sorted()
        val revision = digest(signature.joinToString("|"))
        for (id in dao.nightsToJudge(revision)) {
            if (judgeNight(id, models, revision, at)) store.notifyChanged()
        }
    }

    private suspend fun trainKind(kind: String, selected: List<NightFeedbackRow>, old: NightPersonalRow?,
                                  at: Long, charging: Boolean): NightPersonalRow? {
        val answers = selected.associate { it.eventId to it.yes }
        val previous = old?.let { json.decodeFromString<PersonalWeights>(it.payload).answers }
        val edited = previous?.any { (id, yes) -> answers[id]?.let { it != yes } == true }
            ?: (old != null && selected.any { it.at > old.trainedAt })
        if (!PersonalNightModel.due(selected.size, old?.feedbackCount ?: 0, at, old?.trainedAt, charging, edited)) return old
        val samples = selected.mapNotNull { row -> features(row.scores,row.features)?.let { PersonalExample(it,row.yes) } }
        currentCoroutineContext().ensureActive()
        val trained = PersonalNightModel.train(samples)?.copy(answers=answers) ?: return old
        val row = NightPersonalRow(kind,json.encodeToString(trained),at,selected.size)
        return store.db.writeTransaction {
            // 训练期间的改答和隐私删除优先。新资料由下一次维护处理。
            if (dao.feedback().filter { it.kind == kind }.map { it.id } != selected.map { it.id }) null
            else row.also { dao.personal(it) }
        }
    }

    private suspend fun clearInactive() {
        if (dao.notice("personal-inactive")?.muted == true) return
        val changed = store.db.writeTransaction {
            val stale = dao.vetoedNights()
            for (id in stale) {
                dao.clearVetoes(id)
                store.recompute(id)
                dao.invalidateJudgment(id)
            }
            dao.notice(NightNoticeRow("personal-inactive",0,true))
            stale.isNotEmpty()
        }
        if (changed) store.notifyChanged()
    }

    private suspend fun judgeNight(id: Long, models: Map<String,PersonalWeights>, revision: String, at: Long): Boolean =
        store.db.writeTransaction {
            if (dao.get(id)?.state != NightState.Ready.name) return@writeTransaction false
            var changed = false
            for (row in dao.events(id).filter { it.kind == "Snore" || it.kind == "Cough" }) {
                val event = json.decodeFromString<NightEvent>(row.payload)
                val model = models[row.kind]
                val veto = if (model == null || event.feedback == Feedback.Yes) false else {
                    val clip = event.clipId?.let { dao.clip(it) } ?: continue
                    val x = features(clip.scores,clip.features) ?: continue
                    !PersonalNightModel.permits(model,x)
                }
                if (veto != row.vetoed) { dao.updateEvent(row.copy(vetoed=veto)); changed=true }
            }
            if (changed) store.recompute(id)
            dao.invalidateJudgment(id)
            dao.notice(NightNoticeRow("personal-judge:$id:$revision",at,false))
            changed
        }

    private fun features(scores: ByteArray, encoded: String): List<Double>? {
        val f = runCatching { json.decodeFromString<NightFeatures>(encoded) }.getOrNull() ?: return null
        return decodeScores(scores).map(Float::toDouble) + listOf(f.rhythm,f.modulation,f.aboveBackground.toDouble())
    }
}
