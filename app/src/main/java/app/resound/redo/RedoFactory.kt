package app.resound.redo

import android.content.Context
import app.resound.appName
import app.resound.data.*
import app.resound.digest.*
import app.resound.transcribe.*
import app.resound.skills.*
import app.resound.usage.UsagePurpose
import kotlinx.coroutines.*
import org.json.JSONArray
import java.io.File
import java.time.LocalDate

internal fun createRedoEngine(context: Context, db: CaptureDatabase, chooser: AndroidAsrChooser,
    settings: AndroidTranscribeSettings, digestSettings: AndroidDigestSettings, llm: LlmClient,
    hotwords: () -> List<String>, template: () -> String, identify: suspend (List<Long>) -> Unit,
    network: (TranscribeSource) -> Boolean,
): RedoEngine {
    val provider = ConfiguredAsrProvider(context, settings, hotwords)
    val files = SkillFiles(context)
    return RedoEngine(db, chooser, recognize = { segment, source ->
        if (!network(source)) throw AsrException(AsrError.Network)
        val began = System.currentTimeMillis()
        val key = settings.credential(source)
        var sent = 0L; var error: AsrError? = null; var outcome = "Failed"; var log: String? = null
        try {
            val result = provider.recognize(source, AsrAudio(File(segment.path), File(segment.path).extension, segment.endAt - segment.startAt, separateSpeakers = segment.separateSpeakers ?: true), key) {
                if (source != TranscribeSource.Local) {
                    sent = segment.endAt - segment.startAt
                    db.transcribe().reserveAudio(LocalDate.now().toString(), sent, segment.id)
                }
            }
            outcome = if (result.sentences.isEmpty()) "Empty" else "Done"
            log = TranscribePolicy.safeLogId(result.logId, key)
            chooser.record(source, segment.endAt - segment.startAt, System.currentTimeMillis() - began)
            result
        } catch (e: CancellationException) { error = AsrError.Interrupted; throw e }
        catch (e: AsrException) { error = e.type; throw e }
        finally { withContext(NonCancellable) { db.transcribe().attempt(AsrAttemptEntity(segmentId = segment.id, at = began,
            result = outcome, errorType = error?.name, elapsedMillis = System.currentTimeMillis() - began, sentMillis = sent, logId = log)) } }
    }, digest = { lines, presetId ->
        val (preset, key) = digestSettings.skillCredentials(presetId)
        val (text, indexed) = DigestPrompt.transcript(lines, unseparated = db.transcribe().unseparated(lines.map { it.segmentId }.distinct()).toSet())
        val input = DigestPrompt.withTodoContext(DigestPrompt.withHotwords(text, hotwords()), db.memory().unfinishedForDigest(), db.memory().ignoredForDigest(System.currentTimeMillis() - 60L * 86_400_000))
        val reply = llm.chatJson(preset, key, DigestPrompt.system(template(), LocalDate.now(), context.appName), input)
        withContext(NonCancellable) { db.memory().addUsage(LocalDate.now().toString(), reply.promptTokens, reply.completionTokens, UsagePurpose.Digest) }
        val (events, items) = DigestPrompt.parse(reply.content, indexed)
        val at = System.currentTimeMillis()
        RedoDigest(events.map { e -> MemoryEventEntity(startAt = e.startAt, endAt = e.endAt,
            scene = if (db.envSound().media(e.startAt, e.endAt)) "媒体" else e.scene, title = e.title, summary = e.summary,
            pointsJson = JSONArray(e.points).toString(), peopleJson = JSONArray(e.people).toString(), place = e.place,
            emotion = e.emotion, intensity = e.intensity, important = e.important, createdAt = at, model = preset.model) to e.lineIds },
            { ids -> items.map { i -> MemoryItemEntity(eventId = ids[i.eventIndex], category = i.category, kind = i.kind, content = i.content,
                dueText = i.dueText, dueAt = i.dueAt, who = i.who, lineId = i.lineId, status = "Pending", createdAt = at) } })
    }, identify = identify, regenerate = { oldCards, events, lines ->
        // 以候选新事件构造输入，技能结果也先暂存；失败时整个事件替换不落库。
        oldCards.groupBy { it.skillId to it.date }.flatMap { (group, _) ->
            val skill = files.skills.value.find { it.id == group.first } ?: error("相关技能已删除，无法重新生成")
            val date = LocalDate.ofEpochDay(group.second)
            val start = date.atStartOfDay(SKILL_ZONE).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(SKILL_ZONE).toInstant().toEpochMilli()
            val bounded = SkillInputs.boundedLines(lines.filter { it.startAt < end && it.endAt > start }, skill.scope.speakers)
            val indexed = bounded.mapIndexed { i, l -> "L${i + 1}" to l }.toMap()
            val input = SkillInput(buildString {
                appendLine("归属日期：$date；以下是这段时间重新整理后的事件：")
                events.filter { it.first.startAt < end && it.first.endAt > start }.forEach { (e, _) -> appendLine("${SkillInputs.time(e.startAt)}～${SkillInputs.time(e.endAt)} ${e.title}：${e.summary}") }
                appendLine("原话：")
                indexed.forEach { (id, l) -> appendLine("[$id] ${SkillInputs.time(l.startAt)} ${SkillInputs.speaker(l)}：${l.text}") }
            }, indexed)
            val (preset, key) = digestSettings.skillCredentials(skill.presetId)
            val reply = llm.chatJson(preset, key, SkillPrompt.system(skill, files.prompt(skill.id), date), SkillInputs.user(input, hotwords()))
            withContext(NonCancellable) { db.memory().addUsage(LocalDate.now().toString(), reply.promptTokens, reply.completionTokens, UsagePurpose.Skill) }
            SkillOutput.parse(reply.content, skill, date, input, System.currentTimeMillis(), false).rows.take(skill.maxCardsPerDay)
        }
    })
}
