@file:OptIn(kotlin.time.ExperimentalTime::class)
package app.resound.digest.core

import app.resound.digest.*
import app.resound.net.Obj as JSONObject
import app.resound.net.Arr as JSONArray
import app.resound.net.NetLlmException as LlmException
import app.resound.net.NetLlmError as LlmError
import kotlinx.datetime.*
import kotlin.time.Instant


/**
 * 合并用的提示词与解析：把几件事的原话合起来，重新整理成**一件**事。
 * 输入排版、日历、语音输入规则沿用 [DigestPrompt]，只换提示词模板与输出结构。
 * 纯逻辑，便于单元测试。
 */
object MergePrompt {
    /** 合并提示词：模板 + 日历 + 语音输入规则。 */
    fun system(template: String, today: LocalDate, appName: String): String =
        template.replace("{{CALENDAR}}", DigestPrompt.calendar(today)).replace("{{APP_NAME}}", appName) +
            "\n\n" + DigestPrompt.VOICE_INPUT_RULES

    /**
     * 合并的输入：几件事已经整理好的标题、摘要、要点（不重读原话，多长的事都能合，提取项原样保留）。
     */
    fun input(events: List<MemoryEventData>, zone: TimeZone, situationHeadings: Map<Long, String> = emptyMap()): String {
        val sections = events.sortedBy { it.startAt }.mapIndexed { index, event ->
            val points = JSONArray(event.pointsJson.ifBlank { "[]" }).strings()
            val people = JSONArray(event.peopleJson.ifBlank { "[]" }).strings()
            val from = clock(event.startAt, zone)
            val to = clock(event.endAt, zone)
            listOfNotNull(
                situationHeadings[event.id],
                "【记录 ${index + 1}】$from–$to · ${event.scene}",
                "标题：${event.title}",
                event.summary.takeUnless { it.isBlank() }?.let { "摘要：$it" },
                points.takeUnless { it.isEmpty() }?.joinToString("；", prefix = "要点："),
                people.takeUnless { it.isEmpty() }?.joinToString("、", prefix = "提到的人："),
                event.place?.let { "地点：$it" },
            ).joinToString("\n")
        }
        return sections.joinToString("\n\n").trim()
    }

    /**
     * 解析大模型回复。
     * `merged` 缺失或格式完全不对时抛 [LlmException]（Format）。
     * 合并后的事件覆盖全部原话行；提取项的行号越界就不带出处，但仍然算这一件事的。
     */
    fun parse(
        content: String,
        lines: List<TranscriptLineData>,
        zone: TimeZone,
    ): Pair<DigestPrompt.ParsedEvent, List<DigestPrompt.ParsedItem>> {
        if (lines.isEmpty()) throw LlmException(LlmError.Format)
        val raw = content.trim()
        val objectText = raw.removePrefix("```json").removePrefix("```").removeSuffix("```")
        val response = try {
            JSONObject(objectText)
        } catch (_: Exception) {
            throw LlmException(LlmError.Format)
        }
        val detail = response.optJSONObject("merged") ?: throw LlmException(LlmError.Format)
        val contributors = detail.optJSONArray("people").strings()
        val event = DigestPrompt.ParsedEvent(
            lineIds = lines.map(TranscriptLineData::id),
            startAt = lines.first().startAt,
            endAt = lines.maxOf { it.endAt },
            scene = detail.optString("scene").ifBlank { "其他" },
            title = detail.optString("title").ifBlank { "未命名" }.take(30),
            summary = detail.optString("summary"),
            points = detail.optJSONArray("points").strings(),
            people = contributors.filter { !it.startsWith("说话人") },
            place = detail.optStringOrNull("place"),
            emotion = detail.optStringOrNull("emotion"),
            intensity = detail.optInt("intensity").takeIf { it in 1..5 },
            important = detail.optBoolean("important"),
        )
        if (event.scene in DigestPrompt.NO_ITEMS_SCENES) return event to emptyList()
        val extracted = response.optJSONArray("items") ?: return event to emptyList()
        val items = (0 until extracted.length()).mapNotNull { index ->
            val row = extracted.optJSONObject(index) ?: return@mapNotNull null
            val kind = row.optString("category")
            val text = row.optString("content").trim()
            if (kind !in DigestPrompt.CATEGORIES || text.isEmpty()) return@mapNotNull null
            val lineNumber = row.optInt("line", -1)
            val source = lineNumber.takeIf { it in 1..lines.size }?.let { lines[it - 1] }
            val deadline = row.optStringOrNull("due")
            DigestPrompt.ParsedItem(
                eventIndex = 0,
                category = kind,
                kind = if (kind == "待办") row.optStringOrNull("kind") else null,
                content = text,
                dueText = deadline,
                dueAt = deadline?.let { DigestPrompt.parseDue(it, zone) },
                who = row.optStringOrNull("who"),
                lineId = source?.id,
            )
        }
        return event to items
    }
}
