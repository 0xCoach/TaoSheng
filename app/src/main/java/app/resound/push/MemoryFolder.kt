package app.resound.push

import app.resound.appDisplayName
import app.resound.data.*
import app.resound.skills.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** 纯内容生成；更新时间由上传器在内容改变时填入，空跑不会让所有指纹失效。 */
internal class MemoryFolder(private val today: LocalDate, private val zone: ZoneId = ZoneId.systemDefault()) {
    companion object {
        const val UPDATED = "{{RESOUND_UPDATED}}"

        /** 说明卡里的“更新”时间：北京时间，如 2026-10-24 22:00 */
        fun updatedText(at: Long): String = Instant.ofEpochMilli(at).atZone(ZoneId.of("Asia/Shanghai"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        fun dailyPath(date: LocalDate) = "daily/${date.toString().take(7)}/${date}-${date.format(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH))}_daily-record.md"
        fun transcriptPath(date: LocalDate) = dailyPath(date).replace("daily/", "transcripts/").replace("_daily-record", "_transcript")
        fun weekPath(date: LocalDate): String {
            val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val sunday = monday.plusDays(6)
            val md = DateTimeFormatter.ofPattern("MMdd", Locale.ROOT)
            return "weekly/${monday.get(IsoFields.WEEK_BASED_YEAR)}-W${monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR).toString().padStart(2, '0')}_${monday.format(md)}-${sunday.format(md)}_weekly-review.md"
        }
        fun card(type: String, date: String, content: String, title: String, count: Int, body: String): String =
            "---\n类型: ${JSONObject.quote(type)}\n日期: ${JSONObject.quote(date)}\n内容: ${JSONObject.quote(content)}\n更新: \"$UPDATED\"\n来源: ${appDisplayName}自动生成，请勿手动修改（改了会被覆盖）\n条数: $count\n---\n# $title\n\n${body.trim()}\n"
    }
    /** 给人看的状态：英文枚举名换成中文 */
    private fun statusText(status: Any): String = when (status.toString()) {
        "Pending" -> "待确认"; "Accepted" -> "进行中"; "Done" -> "已完成"; "Ignored" -> "已忽略"; else -> status.toString()
    }
    private fun date(at: Long) = day(at, zone)
    private fun time(at: Long) = Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
    private fun cn(date: LocalDate) = date.format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.CHINESE))
    private fun item(i: MemoryItemEntity, snapshot: PushSnapshot): String {
        val origin = snapshot.events.find { it.id == i.eventId }?.let { "[[${dailyPath(date(it.startAt))}#E${it.id}]]" }.orEmpty()
        val repeat = snapshot.repeats[i.id] ?: 1
        return "- I${i.id} · ${i.category}${i.kind?.let { " / $it" }.orEmpty()} · ${i.content} · ${statusText(i.status)}" +
            (if (repeat > 1) " · 说过 $repeat 次" else "") + (i.who?.let { " · $it" } ?: "") + (i.dueAt?.let { " · 到期 ${date(it)}" } ?: i.dueText?.let { " · $it" }.orEmpty()) + " · $origin"
    }
    private fun review(r: ReviewEntity): String = buildString {
        appendLine(r.brief)
        JSONArray(r.sectionsJson).objects().forEach { section ->
            appendLine("### ${section.optString("title")}")
            section.optJSONArray("lines")?.let { lines -> repeat(lines.length()) { index ->
                val line = lines.get(index)
                appendLine("- ${if (line is JSONObject) line.optString("text") else line}")
            } }
        }
    }
    private fun skill(row: SkillCardRow): String = buildString {
        val c = row.card()
        appendLine("### ${c.skillName} · ${c.title}"); appendLine(c.summary)
        c.fields.forEach { field ->
            val v = c.values[field.key] ?: return@forEach
            appendLine("#### ${field.label}")
            when (field.type) {
                FieldType.Text, FieldType.Paragraph -> appendLine(v.text.orEmpty())
                FieldType.Bullets -> v.items.forEach { appendLine("- $it") }
                FieldType.Checklist -> v.items.forEachIndexed { i, text -> appendLine("- [${if (v.checked.getOrNull(i) == true) "x" else " "}] $text") }
                FieldType.Quotes -> v.quotes.forEach { q -> appendLine("> ${q.speaker ?: "某人"}：${q.text.replace("\n", "\n> ")}") }
                FieldType.Number -> appendLine("${v.number ?: ""} ${v.unit.orEmpty()}")
                FieldType.Tags -> appendLine(v.items.joinToString(" · "))
                FieldType.Rating -> appendLine("${v.rating ?: 0} / 5")
            }
        }
    }
    fun generate(data: PushSnapshot, s: PushSubscription): Map<String, String> {
        val files = sortedMapOf<String, String>()
        val events = data.events.filter { RecordKind.Timeline in s.records || (RecordKind.Starred in s.records && it.starred) }
        val days = (events.map { date(it.startAt) } + data.items.flatMap { listOfNotNull(date(it.createdAt), it.doneAt?.let(::date), it.dueAt?.let(::date)) } +
            data.cards.map { LocalDate.ofEpochDay(it.date) } + data.reviews.filter { it.type == "daily" }.map { LocalDate.parse(it.startDate) } + data.lines.map { date(it.startAt) }).filter { it <= today && (data.startDate == null || it >= data.startDate) }.toSortedSet()
        days.forEach { d ->
            val es = events.filter { date(it.startAt) == d }
            val lines = data.lines.filter { date(it.startAt) == d }
            val rs = data.reviews.filter { it.type == "daily" && it.startDate == d.toString() }
            val cs = data.cards.filter { LocalDate.ofEpochDay(it.date) == d }
            val items = data.items.filter { date(it.createdAt) == d || it.dueAt?.let(::date) == d || it.doneAt?.let(::date) == d }
            val title = "${cn(d)} · ${es.maxByOrNull { if (it.starred) 2 else if (it.important) 1 else 0 }?.title ?: rs.firstOrNull()?.brief?.lineSequence()?.firstOrNull() ?: "当日记录"}等 ${es.size} 件事"
            val body = buildString {
                appendLine("<!-- resound:reviews:start -->")
                rs.forEach { appendLine("## 当天回顾\n${review(it)}") }
                appendLine("<!-- resound:reviews:end -->")
                if (es.isNotEmpty()) appendLine("## 时间线")
                es.forEach { e ->
                    appendLine("<a id=\"E${e.id}\"></a>\n### E${e.id} · ${time(e.startAt)} ${if (e.starred) "★ 重点 · " else ""}${e.title}${if (e.important) " · 要紧" else ""}")
                    appendLine("场景：${e.scene} · 参与人：${JSONArray(e.peopleJson).strings().joinToString("、")}")
                    appendLine(e.summary); JSONArray(e.pointsJson).strings().forEach { appendLine("- $it") }
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
                lines.joinToString("\n\n") { "### L${it.id} · ${time(it.startAt)} · ${it.speakerName ?: it.speakerTag ?: "某人"}\n${it.text}${app.resound.transcribe.VoiceInputMetadata.markdown(it.voiceInputJson, s.lineContext)}" })
        }
        data.reviews.filter { it.type != "daily" }.groupBy { weekPath(LocalDate.parse(it.startDate)) }.forEach { (path, rows) ->
            val monday = LocalDate.parse(rows.first().startDate).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); val end = monday.plusDays(6)
            val title = "${monday.get(IsoFields.WEEK_BASED_YEAR)}年第${monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}周（${monday.monthValue}月${monday.dayOfMonth}日—${end.monthValue}月${end.dayOfMonth}日）"
            files[path] = card("每周回顾", "$monday—$end", "每周回顾", title, rows.size, rows.joinToString("\n", transform = ::review))
        }
        if (s.layout == FolderLayout.ByCategory) {
            files["recent-days-index.md"] = card("索引", "最近30天", "每天一行", "最近 30 天", 30, (0L..29L).map { today.minusDays(it) }.joinToString("\n") { d ->
                val es = events.filter { date(it.startAt) == d }
                val brief = data.reviews.firstOrNull { it.type == "daily" && it.startDate == "$d" }?.brief ?: es.firstOrNull()?.title ?: "暂无记录"
                "- $d · ${d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.CHINESE)} · ${es.size} 件事 · ${brief.replace("\n", " ")} · ${es.count { it.starred }} 个重点" + if (d in days) " · [[${dailyPath(d)}]]" else ""
            })
            if (RecordKind.Starred in s.records) {
                val stars = events.filter { it.starred }
                files["starred-highlights.md"] = card("我的重点", "订阅范围", "标星与进展", "我的重点", stars.size, stars.joinToString("\n") { "- E${it.id} · ${it.title} · [[${dailyPath(date(it.startAt))}#E${it.id}]]\n${progress(it, data)}" })
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
        files["README.md"] = card("使用说明", "订阅范围", "文件与智能体使用方法", "${appDisplayName}记忆 · Resound-Memory", files.size, """
            | 文件 / 文件夹 | 内容 | 更新 |
            | --- | --- | --- |
            | daily/ | 每日回顾、时间线、条目、卡片 | 按订阅触发 |
            | weekly/ | 每周回顾 | 回顾生成后 |
            | transcripts/ | 原话（仅开启时） | 按订阅触发 |
            ${if (s.layout == FolderLayout.ByCategory) "| recent-days-index.md | 最近30天导航 | 同步时 |\n| starred-highlights.md | 重点与进展 | 同步时 |\n| todos-and-appointments.md | 待办与约定 | 同步时 |\n| ideas.md、decisions-and-facts.md | 最近一年提取项 | 同步时 |\n| archive/ | 一年以前的提取项 | 同步时 |\n| inbox/ | 预留；只建空目录，不读取 | 不处理 |\n| my-notes/ | 归用户所有 | 回响永不读写、不删除 |" else ""}

            ## 给智能体的用法
            ${if (s.layout == FolderLayout.ByCategory) "先读 recent-days-index.md → 某天细节读 daily/ → 核对原话读 transcripts/；重点和待办直接读汇总文件。" else "先按日期读 daily/，其中包含当天汇总；核对原话读 transcripts/；每周总结读 weekly/。"}
            my-notes/ 归用户；其余自动生成的文件请勿手动修改，改了会被覆盖。inbox/ 预留，不读取。
            缺少某类文件表示未订阅或没有内容。条目 I 编号、事件 E 编号用于引用来源。
        """.trimIndent())
        return files
    }
    private fun progress(e: MemoryEventEntity, data: PushSnapshot): String {
        val related = data.items.filter { it.eventId == e.id }
        val completed = related.any { (it.doneAt ?: 0) > (e.starredAt ?: Long.MAX_VALUE) }
        return "进展：${related.joinToString("；") { "I${it.id} ${it.content}（${statusText(it.status)}）" }.ifBlank { "暂无相关条目" }}；标星后${if (completed) "已有完成" else "尚无完成"}。"
    }
}
