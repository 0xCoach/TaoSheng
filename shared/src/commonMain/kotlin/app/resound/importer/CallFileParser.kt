@file:OptIn(kotlin.time.ExperimentalTime::class)
package app.resound.importer

import kotlinx.datetime.*
import kotlin.time.Instant
import kotlin.math.abs

/** 所有匹配只消费自己的零件；先遮住号码，避免把号码读成日期。 */
internal object CallFileParser {
    private val mobile = Regex("(?<![0-9])(?:\\+?86[- ]?)?1[3-9][0-9][- ]?[0-9]{4}[- ]?[0-9]{4}(?![0-9])")
    private val land = Regex("(?<![0-9])0[0-9]{2,3}[- ]?[0-9]{7,8}(?![0-9])")
    private val service = Regex("""(?:[（(\[【])([0-9]{5,6})[）)\]】]|[@＠]([0-9]{5,6})(?![0-9])""")
    fun number(raw: String): String? {
        val text = raw.trim().replace(" ", "").replace("-", "")
        val stripped = text.removePrefix("+86").let { if (it.length == 13 && it.startsWith("86")) it.drop(2) else it }
        return stripped.takeIf { Regex("1[3-9][0-9]{9}|0[0-9]{9,11}|[0-9]{5,6}").matches(it) }
    }
    fun parse(fileName: String, end: Long?, recorded: Long?, duration: Long?, zone: TimeZone): CallFileInfo {
        val reference = recorded ?: end?.let { if (duration != null && duration >= 0 && it >= Long.MIN_VALUE + duration) it - duration else it }
        return runCatching { parseParts(fileName, reference, zone) }
            .getOrElse { CallFileInfo(null, null, reference, null, if (reference == null) CallTimeSource.Unknown else CallTimeSource.FileClock) }
    }
    private fun parseParts(file: String, reference: Long?, zone: TimeZone): CallFileInfo {
        var rest = file.replace(Regex("\\.[A-Za-z][A-Za-z0-9]{1,5}$"), "")
        fun erase(range: IntRange) { rest = rest.replaceRange(range, " ".repeat(range.count())) }
        val phone = mobile.find(rest) ?: land.find(rest)
        val serviceMatch = if (phone == null) service.find(rest) else null
        val number = phone?.value?.let(::number) ?: serviceMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
        if (phone != null) erase(phone.range)
        if (serviceMatch != null) erase(serviceMatch.range)
        val full = mutableListOf<Long>()
        val times = mutableListOf<LocalTime>()
        val dateOnly = mutableListOf<Long>()
        fun instant(y: Int, mo: Int, d: Int, h: Int = 0, m: Int = 0, s: Int = 0): Long? =
            runCatching { LocalDateTime(y, mo, d, h, m, s).toInstant(zone).toEpochMilliseconds() }.getOrNull()
        fun match(pattern: String, consume: (MatchResult) -> List<Long>) {
            Regex(pattern).findAll(rest).toList().forEach { found ->
                val candidates = consume(found)
                if (candidates.isNotEmpty()) { full += candidates; erase(found.range) }
            }
        }
        match("(?<![0-9])[0-9]{10,14}(?![0-9])") { f ->
            val v = f.value
            buildList {
                fun date(year: Int, offset: Int, seconds: Boolean) {
                    instant(year, v.substring(offset, offset+2).toInt(), v.substring(offset+2, offset+4).toInt(),
                        v.substring(offset+4, offset+6).toInt(), v.substring(offset+6, offset+8).toInt(),
                        if (seconds) v.substring(offset+8, offset+10).toInt() else 0)?.let(::add)
                }
                if (v.length == 14) date(v.take(4).toInt(), 4, true)
                if (v.length == 12) { date(v.take(4).toInt(), 4, false); date(2000 + v.take(2).toInt(), 2, true) }
                if (v.length == 10 || v.length == 13) {
                    val stamp = v.toLong() * if (v.length == 10) 1000 else 1
                    if (stamp in 946684800000L..4133980799999L) add(stamp)
                }
            }
        }
        match("(?<![0-9])([0-9]{8}|[0-9]{6})[_ .@＠,，()（）\\[\\]【】:-]+([0-9]{6}|[0-9]{4})(?![0-9])") { f ->
            val d = f.groupValues[1]; val t = f.groupValues[2]; val off = d.length - 4
            listOfNotNull(instant(if (off == 4) d.take(4).toInt() else 2000+d.take(2).toInt(), d.substring(off,off+2).toInt(), d.takeLast(2).toInt(),
                t.take(2).toInt(), t.substring(2,4).toInt(), if (t.length == 6) t.takeLast(2).toInt() else 0))
        }
        match("""(?<![0-9])([0-9]{6}|[0-9]{4})[_ .@＠,，()（）\[\]【】:-]+([0-9]{8}|[0-9]{6})(?![0-9])""") { f ->
            val t = f.groupValues[1]; val d = f.groupValues[2]; val off = d.length - 4
            listOfNotNull(instant(if (off == 4) d.take(4).toInt() else 2000+d.take(2).toInt(), d.substring(off,off+2).toInt(), d.takeLast(2).toInt(),
                t.take(2).toInt(),t.substring(2,4).toInt(),if (t.length == 6) t.takeLast(2).toInt() else 0))
        }
        match("(?<![0-9])([0-9]{4})[-.年]([0-9]{1,2})[-.月]([0-9]{1,2})日?(?:[ _T]+([0-9]{1,2})[-.:]([0-9]{2})(?:[-.:]([0-9]{2}))?)?(?![0-9])") { f ->
            val g = f.groupValues
            val candidate = instant(g[1].toInt(),g[2].toInt(),g[3].toInt(),g[4].toIntOrNull()?:0,g[5].toIntOrNull()?:0,g[6].toIntOrNull()?:0)
            if (g[4].isEmpty()) { candidate?.let { dateOnly += it }; erase(f.range); emptyList() } else listOfNotNull(candidate)
        }
        if (reference != null) match("(?<![0-9])([0-9]{1,2})月([0-9]{1,2})日") { f ->
            val year = Instant.fromEpochMilliseconds(reference).toLocalDateTime(zone).year
            instant(year, f.groupValues[1].toInt(), f.groupValues[2].toInt())?.let { dateOnly += it }; erase(f.range); emptyList()
        }
        rest = rest.replace(Regex("""(?<![0-9])[0-9]{4}[-.年][0-9]{1,2}[-.月][0-9]{1,2}日?(?:[ _T]+[0-9]{1,2}[-.:][0-9]{2}(?:[-.:][0-9]{2})?)?(?![0-9])""")) { " ".repeat(it.value.length) }
        val timePattern = Regex("(?:上午|下午|晚上)?\\s*[0-9]{1,2}[点时](?:[0-9]{1,2}分)?(?:[0-9]{1,2}秒)?|(?<![0-9])[0-9]{1,2}[-.:][0-9]{2}(?:[-. :][0-9]{2})?(?![0-9])|(?<![0-9])[0-9]{4}(?![0-9])")
        timePattern.findAll(rest).toList().forEach { f ->
            val digits = Regex("[0-9]+").findAll(f.value).map { it.value.toInt() }.toList()
            var h = if (digits.size == 1 && f.value.trim().length == 4) digits[0]/100 else digits[0]
            val m = if (digits.size == 1 && f.value.trim().length == 4) digits[0]%100 else digits.getOrElse(1) { 0 }
            if (("下午" in f.value || "晚上" in f.value) && h in 1..11) h += 12
            if ("上午" in f.value && h == 12) h = 0
            runCatching { LocalTime(h,m,digits.getOrElse(2) { 0 }) }.getOrNull()?.let { times += it; erase(f.range) }
        }
        val incoming = Regex("来电|呼入|接听|incoming|in_", RegexOption.IGNORE_CASE)
        val outgoing = Regex("去电|呼出|拨出|outgoing|out_", RegexOption.IGNORE_CASE)
        val direction = when { incoming.containsMatchIn(rest) -> CallDirection.Incoming; outgoing.containsMatchIn(rest) -> CallDirection.Outgoing; else -> null }
        rest = rest.replace(incoming, "").replace(outgoing, "")
            .replace(Regex("通话录音|电话录音|录音|call recording|call_rec|callrecord|record|call|voice|rec|未知号码|unknown|私人号码", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[_\\-.,，@＠()（）\\[\\]【】\\s:]+"), "").trim()
        val name = rest.takeIf { it.isNotEmpty() && !it.all(Char::isDigit) }
        full += if (times.isEmpty()) dateOnly else dateOnly.flatMap { day ->
            val date = Instant.fromEpochMilliseconds(day).toLocalDateTime(zone).date
            times.map { LocalDateTime(date,it).toInstant(zone).toEpochMilliseconds() }
        }
        val candidates = full.distinct()
        var at = reference
        var source = if (reference == null) CallTimeSource.Unknown else CallTimeSource.FileClock
        if (candidates.isNotEmpty()) {
            val closest = if (reference == null) candidates.singleOrNull() else candidates.minByOrNull { abs(it.toDouble()-reference) }
            if (candidates.size == 1 || (closest != null && reference != null && abs(closest.toDouble()-reference) <= 36*3600_000L)) {
                at = closest; source = CallTimeSource.FileName
            }
        } else if (times.isNotEmpty() && reference != null) {
            val date = Instant.fromEpochMilliseconds(reference).toLocalDateTime(zone).date
            at = (-1..1).flatMap { offset -> times.map { LocalDateTime(date.plus(offset,DateTimeUnit.DAY),it).toInstant(zone).toEpochMilliseconds() } }
                .minBy { abs(it.toDouble()-reference) }
            source = CallTimeSource.FileNameTimeOnly
        }
        return CallFileInfo(number,name,at,direction,source)
    }
}
