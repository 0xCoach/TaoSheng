package app.resound.push

import app.resound.OutputTime
import app.resound.envsound.EnvSoundPresentation
import app.resound.transcribe.VoiceInputMetadata

import app.resound.data.*
import app.resound.skills.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import kotlinx.datetime.toKotlinLocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** 纯内容生成；更新时间由上传器在内容改变时填入，空跑不会让所有指纹失效。 */
internal class MemoryFolder(private val today: LocalDate, private val zone: ZoneId = ZoneId.systemDefault()) {
    companion object {
        const val UPDATED = "{{RESOUND_UPDATED}}"

        /** 更新时间使用当前手机时区并写明偏移。 */
        fun updatedText(at: Long): String = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm xxx"))
        fun dailyPath(date: LocalDate) = MemoryPaths.daily(date.toKotlinLocalDate())
        fun transcriptPath(date: LocalDate) = MemoryPaths.transcript(date.toKotlinLocalDate())
        fun weekPath(date: LocalDate) = MemoryPaths.week(date.toKotlinLocalDate())
        fun card(type: String, date: String, content: String, title: String, count: Int, body: String) =
            PushBodyRules.card(type, date, content, title, count, body)
    }
    /** 给人看的状态：英文枚举名换成中文 */
    private fun statusText(status: Any): String = when (status.toString()) {
        "Pending" -> "待确认"; "Accepted" -> "进行中"; "Done" -> "已完成"; "Ignored" -> "已忽略"; else -> status.toString()
    }
    private val historical get() = app.resound.platform.HistoricalZones.history()
    private fun date(at: Long) = day(at, ZoneId.of(historical.zoneAt(at).id))
    private val outputTime = OutputTime(zone, today, { at -> ZoneId.of(historical.zoneAt(at).id) })
    private fun time(at: Long) = outputTime.moment(at)
    private fun cn(date: LocalDate) = date.format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.CHINESE))
    private fun item(i: MemoryItemEntity, snapshot: PushSnapshot): String {
        val origin = snapshot.events.find { it.id == i.eventId }?.let { "[[${dailyPath(date(it.startAt))}#E${it.id}]]" } ?: when (i.origin) { "typed" -> "手动添加"; "voice" -> "语音添加"; else -> "" }
        val repeat = snapshot.repeats[i.id] ?: 1
        return "- I${i.id} · ${i.category}${i.kind?.let { " / $it" }.orEmpty()} · ${i.content} · ${statusText(i.status)}" +
            (if (repeat > 1) " · 说过 $repeat 次" else "") + (i.who?.let { " · $it" } ?: "") + (i.dueAt?.let { " · 到期 ${time(it)}" } ?: i.dueText?.let { " · $it" }.orEmpty()) + " · $origin"
    }
    private suspend fun review(r: ReviewEntity): String = buildString {
        appendLine(r.brief)
        JSONArray(r.sectionsJson).objects().forEach { section ->
            appendLine("### ${section.optString("title")}")
            section.optJSONArray("lines")?.let { lines -> repeat(lines.length()) { index ->
                val line = lines.get(index)
                appendLine("- ${if (line is JSONObject) line.optString("text") else line}")
                val eventId = if (line is JSONObject) line.optLong("eventId").takeIf { it > 0 } else section.optJSONArray("eventIds")?.optLong(index)?.takeIf { it > 0 }
                EnvSoundPresentation.line(eventId).takeIf { it.isNotBlank() }?.let { appendLine(it) }
            } }
        }
    }
    private suspend fun skill(row: SkillCardRow): String = buildString {
        val c = row.card()
        appendLine("### ${c.skillName} · ${c.endAt?.let { outputTime.event(c.at, it) } ?: time(c.at)} · ${c.title}"); appendLine(c.summary)
        c.fields.forEach { field ->
            val v = c.values[field.key] ?: return@forEach
            appendLine("#### ${field.label}")
            when (field.type) {
                FieldType.Text, FieldType.Paragraph -> appendLine(v.text.orEmpty())
                FieldType.Bullets -> v.items.forEach { appendLine("- $it") }
                FieldType.Checklist -> v.items.forEachIndexed { i, text -> appendLine("- [${if (v.checked.getOrNull(i) == true) "x" else " "}] $text") }
                FieldType.Quotes -> v.quotes.forEach { q -> appendLine("> ${time(q.at)} · ${q.speaker ?: "某人"}：${q.text.replace("\n", "\n> ")}") }
                FieldType.Number -> appendLine("${v.number ?: ""} ${v.unit.orEmpty()}")
                FieldType.Tags -> appendLine(v.items.joinToString(" · "))
                FieldType.Rating -> appendLine("${v.rating ?: 0} / 5")
            }
        }
    }
    suspend fun generate(input: PushSnapshot, s: PushSubscription): Map<String, String> =
        EnvSoundPresentation.prefetched(input.events.minOfOrNull { it.startAt }, input.events.maxOfOrNull { it.endAt }) { generateFiles(input, s) }
    private suspend fun generateFiles(input: PushSnapshot, s: PushSubscription): Map<String, String> {
        val data = input.validated()
        val files = sortedMapOf<String, String>()
        val events = data.events.filter { RecordKind.Timeline in s.records || (RecordKind.Starred in s.records && it.starred) }
        val days = (events.map { date(it.startAt) } + data.items.flatMap { listOfNotNull(date(it.createdAt), it.doneAt?.let(::date), it.dueAt?.let(::date)) } +
            data.cards.map { LocalDate.ofEpochDay(it.date) } + data.reviews.filter { it.type == "daily" }.map { LocalDate.parse(it.startDate) } + data.lines.map { date(it
                .startAt) }).filter { it <= today && (data.startDate == null || it >= data.startDate) }.toSortedSet()
        days.forEach { d ->
            val es = events.filter { date(it.startAt) == d }
            val lines = data.lines.filter { date(it.startAt) == d }
            val rs = data.reviews.filter { it.type == "daily" && it.startDate == d.toString() }
            val cs = data.cards.filter { LocalDate.ofEpochDay(it.date) == d }
            val items = data.items.filter { date(it.createdAt) == d || it.dueAt?.let(::date) == d || it.doneAt?.let(::date) == d }
            val title = "${cn(d)} · ${es.maxByOrNull { if (it.starred) 2 else if (it.important) 1 else 0 }?.title ?: rs.firstOrNull()?.brief?.lineSequence()?.firstOrNull() ?: "当日记录"}等 ${es.size} 件事"
            val body = buildString {
                appendLine("本文时间为 ${historical.caption(d.toKotlinLocalDate())}\n")
                appendLine("<!-- resound:reviews:start -->")
                rs.forEach { appendLine("## 当天回顾\n${review(it)}") }
                appendLine("<!-- resound:reviews:end -->")
                if (es.isNotEmpty()) appendLine("## 时间线")
                es.forEach { e ->
                    appendLine("<a id=\"E${e.id}\"></a>\n### E${e.id} · ${outputTime.event(e.startAt, e.endAt)} ${if (e.starred) "★ 重点 · " else ""}${e.title}${if (e.important) " · 要紧" else ""}")
                    appendLine("场景：${e.scene} · 参与人：${JSONArray(e.peopleJson).strings().joinToString("、")}")
                    appendLine(e.summary); EnvSoundPresentation.line(e.id).takeIf { it.isNotBlank() }?.let { appendLine(it) }; JSONArray(e.pointsJson).strings().forEach { appendLine("- $it") }
                    data.items.filter { it.eventId == e.id }.forEach { appendLine(item(it, data)) }
                }
                val sources = items.map { it.eventId }.distinct().filter { id -> es.none { it.id == id } }
                sources.mapNotNull { id -> data.events.find { it.id == id && date(it.startAt) == d } }.forEach { e ->
                    appendLine("<a id=\"E${e.id}\"></a>\n### E${e.id} · 来源：${e.title}")
                }
                if (items.isNotEmpty()) {
                    appendLine("## 当天待办与提取项")
                    items.forEach { i ->
                        val tags = buildList { if (date(i.createdAt) == d) add("新提"); if (i.dueAt?.let(::date) == d) add("到期"); if (i.doneAt?.let(::date) == d) add("完成") }
                        appendLine(item(i, data) + " · " + tags.joinToString(" / "))
                    }
                }
                if (RecordKind.Starred in s.records) es.filter { it.starred }.forEach { appendLine(progress(it, data)) }
                appendLine("<!-- resound:cards:start -->")
                if (cs.isNotEmpty()) { appendLine("## 技能卡片"); cs.forEach { appendLine(skill(it)) } }
                appendLine("<!-- resound:cards:end -->")
                if (lines.isNotEmpty()) appendLine("\n原话：[[${transcriptPath(d)}]]")
            }
            files[dailyPath(d)] = card("每日记录", "$d", "回顾、时间线、当天条目、技能卡片", title, es.size + items.size + cs.size + rs.size, body)
            if (lines.isNotEmpty()) files[transcriptPath(d)] = card("原话", "$d", "按时间排列的原话", "${cn(d)} · 原话", lines.size,
                lines.joinToString("\n\n") { "### L${it.id} · ${time(it.startAt)} · ${it.speakerName ?: it.speakerTag ?: "某人"}\n${it.text}${VoiceInputMetadata.markdown(it
                    .voiceInputJson, s.lineContext)}" })
        }
        data.reviews.filter { it.type != "daily" }.groupBy { weekPath(LocalDate.parse(it.startDate)) }.forEach { (path, rows) ->
            val monday = LocalDate.parse(rows.first().startDate).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); val end = monday.plusDays(6)
            val title = "${monday.get(IsoFields.WEEK_BASED_YEAR)}年第${monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}周（${monday.monthValue}月${monday.dayOfMonth}日—${end.monthValue}月${end.dayOfMonth}日）"
            files[path] = card("每周回顾", "$monday—$end", "每周回顾", title, rows.size, rows.map { review(it) }.joinToString("\n"))
        }
        if (s.layout == FolderLayout.ByCategory) {
            files["recent-days-index.md"] = card("索引", "最近30天", "每天一行", "最近 30 天", 30, (0L..29L).map { today.minusDays(it) }.joinToString("\n") { d ->
                val es = events.filter { date(it.startAt) == d }
                val brief = data.reviews.firstOrNull { it.type == "daily" && it.startDate == "$d" }?.brief ?: es.firstOrNull()?.title ?: "暂无记录"
                "- $d · ${d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.CHINESE)} · ${es.size} 件事 · ${brief.replace("\n", " ")} · ${es.count { it
                    .starred }} 个重点" + if (d in days) " · [[${dailyPath(d)}]]" else ""
            })
            if (RecordKind.Starred in s.records) {
                val stars = events.filter { it.starred }
                files["starred-highlights.md"] = card("我的重点", "订阅范围", "标星与进展", "我的重点", stars.size, stars.joinToString("\n") { "- E${it.id} · ${it.title} · [[${dailyPath(
                    date(it.startAt))}#E${it.id}]]\n${progress(it, data)}" })
            }
            if (RecordKind.Todos in s.records) {
                val tasks = data.items.filter { it.category in setOf("待办", "约定", "备忘") }
                files["todos-and-appointments.md"] = card("待办与约定", "进行中及最近30天完成", "状态、到期和来源", "待办与约定", tasks.size, tasks.joinToString("\n") { item(it, data) })
            }
            listOf(RecordKind.Ideas to "ideas", RecordKind.Decisions to "decisions-and-facts").filter { it.first in s.records }.forEach { (kind, name) ->
                val rows = data.items.filter { if (kind == RecordKind.Ideas) it.category == "灵感" else it.category in setOf("决定", "关键信息", "知识") }
                val cutoff = today.minusYears(1)
                val recent = rows.filter { date(it.createdAt) >= cutoff }
                files["$name.md"] = card(kind.title, "$cutoff—$today", "最近一年，旧条目见 archive", kind.title, recent.size, recent.joinToString("\n") { item(it, data) })
                rows.filter { date(it.createdAt) < cutoff }.groupBy { date(it.createdAt).year }.forEach { (year, old) ->
                    files["archive/$year/$name.md"] = card(kind.title, "$year", "一年以前的归档", "$year 年 · ${kind.title}", old.size, old.joinToString("\n") { item(it, data) })
                }
            }
        }
        files["README.md"] = card("使用说明", "订阅范围", "文件与智能体使用方法", "记忆 · Resound-Memory", files.size, """
            | 文件 / 文件夹 | 内容 | 更新 |
            | --- | --- | --- |
            | daily/ | 每日回顾、时间线、条目、卡片 | 按订阅触发 |
            | weekly/ | 每周回顾 | 回顾生成后 |
            | transcripts/ | 原话（仅开启时） | 按订阅触发 |
            ${if (s.layout == FolderLayout.ByCategory) (
                "| recent-days-index.md | 最近30天导航 | 同步时 |\n" +
                    "| starred-highlights.md | 重点与进展 | 同步时 |\n" +
                    "| todos-and-appointments.md | 待办与约定 | 同步时 |\n" +
                    "| ideas.md、decisions-and-facts.md | 最近一年提取项 | 同步时 |\n" +
                    "| archive/ | 一年以前的提取项 | 同步时 |\n" +
                    "| inbox/ | 预留；只建空目录，不读取 | 不处理 |\n" +
                    "| my-notes/ | 归用户所有 | 声声不息永不读写、不删除 |"
                ) else ""}

            ## 给智能体的用法
            问某天在哪、用了什么、听了什么，读那天 daily 文件开头的“一天的线索”。
            ${if (s.layout == FolderLayout.ByCategory) "先读 recent-days-index.md → 某天细节读 daily/ → 核对原话读 transcripts/；重点和待办直接读汇总文件。" else "先按日期读 daily/，其中包含当天汇总；核对原话读 transcripts/；每周总结读 weekly/。"}
            my-notes/ 归用户；其余自动生成的文件请勿手动修改，改了会被覆盖。inbox/ 预留，不读取。
            缺少某类文件表示未订阅或没有内容。条目 I 编号、事件 E 编号用于引用来源。

            ## 智能体读取须知
            - 本文件夹由用户手机上的「声声不息」App 自动写入：全天录音 → 转写 → 整理成事件、待办和回顾。用户问“我之前……”“我答应过谁……”“某天做了什么”时来这里查。
            - 按日期找：每日记录在 daily/年-月/年-月-日-星期_daily-record.md（如 daily/2026-11/2026-11-16-Mon_daily-record.md），当天原话在 transcripts/ 下同名的 _transcript.md，每周回顾在 weekly/。路径都能按日期直接拼出，一般不用列目录。
            - 时间以每天文档开头写的 UTC 偏移为准。内容由录音自动整理，可能有错字或认错人；拿不准时以原话为准，并告诉用户“记录里是这么写的”，不要补写记录里没有的内容。
            - 通过 WebDAV 只读访问：只用 GET（和列目录用的 PROPFIND），不要 PUT、DELETE、MOVE、MKCOL。内容只用来回答用户本人，不要转发或上传到别处。
            - 本说明随声声不息更新，以这里写的为准。
        """.lines().joinToString("\n") { it.trimStart() }.trim())
        return files
    }
    private fun progress(e: MemoryEventEntity, data: PushSnapshot): String {
        val related = data.items.filter { it.eventId == e.id }
        val completed = related.any { (it.doneAt ?: 0) > (e.starredAt ?: Long.MAX_VALUE) }
        return "进展：${related.joinToString("；") { "I${it.id} ${it.content}（${statusText(it.status)}）" }.ifBlank { "暂无相关条目" }}；标星后${if (completed) "已有完成" else "尚无完成"}。"
    }
}
