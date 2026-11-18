package app.resound.ui.night

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import app.resound.night.DoctorNight
import app.resound.night.DoctorReport
import app.resound.night.NightConfidence
import app.resound.night.NightEventKind
import app.resound.night.NightState
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 给医生的一页（11-18 改版）：A4 两页，只写事实和图。
 * 第 1 页：四个数字、每晚作息图、每晚鼾声柱、对比发现与附带录音；第 2 页：每晚明细表。
 * 浅色底 + 夜空紫，与 App 一致；睡着/鼾声靠深浅区分，黑白打印也分得清。
 */
internal object DoctorPdf {
    private const val W = 595f
    private const val H = 842f
    private const val M = 40f
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private const val INK = 0xFF2A2340.toInt()
    private const val GRAY = 0xFF7B7591.toInt()
    private const val LINE = 0xFFE7E2F0.toInt()
    private const val CARD = 0xFFF5F0FD.toInt()
    private const val SLEEP = 0xFFC3A8EE.toInt()
    private const val SNORE = 0xFF5E36A8.toInt()
    private const val BED = 0xFFE9E4F2.toInt()
    private const val ZEBRA = 0xFFFAF8FD.toInt()

    fun write(r: DoctorReport, out: OutputStream) {
        val doc = PdfDocument()
        val p1 = doc.startPage(PdfDocument.PageInfo.Builder(W.toInt(), H.toInt(), 1).create())
        pageOne(p1.canvas, r); footer(p1.canvas, 1); doc.finishPage(p1)
        val p2 = doc.startPage(PdfDocument.PageInfo.Builder(W.toInt(), H.toInt(), 2).create())
        pageTwo(p2.canvas, r); footer(p2.canvas, 2); doc.finishPage(p2)
        doc.writeTo(out); doc.close()
    }

    /** 预览和截图自查用：把第 [page] 页（1 或 2）画到画布上，画布单位是 PDF 点 */
    fun draw(c: Canvas, r: DoctorReport, page: Int) { c.drawColor(android.graphics.Color.WHITE); if (page == 1) pageOne(c, r) else pageTwo(c, r); footer(c, page) }

    fun file(dir: File, r: DoctorReport): File = File(dir, "睡眠记录-${r.from}至${r.to}.pdf").also { f -> f.outputStream().use { write(r, it) } }

    // ---------- 第 1 页 ----------

    private fun pageOne(c: Canvas, r: DoctorReport) {
        val days = dates(r)
        val valid = valid(r)
        var y = M + 18
        text(c, "睡眠记录", M, y, 20f, INK, bold = true)
        text(c, "${r.from} 至 ${r.to}", W - M, y, 10f, GRAY, align = Paint.Align.RIGHT)
        y += 18
        text(c, "共 ${days.size} 天，有记录 ${r.nights.size} 晚，其中可用于统计 ${valid.size} 晚", M, y, 9.5f, GRAY)
        y += 16

        // 四个数字
        val sleep = valid.mapNotNull { it.summary.sleepMillis }
        val onset = valid.mapNotNull { it.summary.times?.onset }.map { eveningMinutes(it) }
        val wake = valid.mapNotNull { it.summary.times?.wake }.map { eveningMinutes(it) }
        val snore = valid.map { it.summary.snoreMillis }
        val sleepHours = sleep.sum() / 3_600_000.0
        val breaks = valid.sumOf { it.summary.snoreBreaks }
        val cards = listOf(
            Triple("平均睡眠", sleep.takeIf { it.isNotEmpty() }?.let { hm(it.average().toLong()) } ?: "—", if (sleep.isEmpty()) "" else "最短 ${hm(sleep.min())}，最长 ${hm(sleep.max())}"),
            Triple("平均入睡 – 起床", if (onset.isEmpty() || wake.isEmpty()) "—" else "${clock(onset.average())} – ${clock(wake.average())}", if (onset.size >= 3) "入睡时间前后差约 ${sd(onset)} 分" else ""),
            Triple("疑似鼾声", if (snore.isEmpty()) "—" else "${(snore.average() / 60_000).roundToInt()} 分/晚", if (sleep.sum() > 0) "占睡眠 ${(snore.sum() * 100.0 / sleep.sum()).roundToInt()}%" else ""),
            Triple("鼾声中断", if (sleepHours <= 0) "—" else "%.1f 次/小时".format(breaks / sleepHours), if (sleepHours > 0) "共 $breaks 次" else ""),
        )
        val gap = 10f; val cw = (W - 2 * M - 3 * gap) / 4; val ch = 64f
        cards.forEachIndexed { i, (label, value, note) ->
            val x = M + i * (cw + gap)
            fill(c, RectF(x, y, x + cw, y + ch), CARD, 10f)
            text(c, label, x + 10, y + 16, 8.5f, GRAY)
            text(c, value, x + 10, y + 38, 15f, INK, bold = true)
            if (note.isNotEmpty()) text(c, note, x + 10, y + 54, 7.5f, GRAY)
        }
        y += ch + 16
        if (valid.isNotEmpty()) {
            val cough = valid.map { it.summary.coughCount }.average()
            val awake = valid.map { it.summary.awakeCount }.average()
            val talk = valid.map { it.summary.talkCount }.average()
            text(c, "平均每晚：疑似咳嗽/清嗓 %.1f 次 · 夜里醒来（2 分钟以上）%.1f 次 · 夜间人声 %.1f 段".format(cough, awake, talk), M, y, 9f, INK)
            y += 22
        } else y += 6

        // 每晚作息
        section(c, "每晚作息", y)
        legend(c, y, listOf(BED to "上床未睡着", SLEEP to "睡着", SNORE to "疑似鼾声"))
        y += 14
        y = sleepChart(c, days, r, y)
        y += 18

        // 每晚鼾声
        section(c, "每晚疑似鼾声（分钟）", y)
        legend(c, y, listOf(SNORE to "鼾声", INK to "上方数字：鼾声中断次数"))
        y += 10
        y = snoreChart(c, days, r, y)
        y += 20

        // 发现与录音
        if (r.findings.isNotEmpty()) {
            section(c, "对比发现", y); y += 16
            r.findings.forEach { y = wrap(c, "· " + it.text, M, y, W - 2 * M, 9f, INK) + 4 }
            y += 8
        }
        if (r.clipInfo.isNotEmpty()) {
            section(c, "随附录音", y); y += 16
            r.clipInfo.forEachIndexed { i, clip ->
                val t = Instant.ofEpochMilli(clip.at).atZone(zone)
                text(c, "睡眠录音-${i + 1}：%d-%02d %d:%02d 疑似%s".format(t.monthValue, t.dayOfMonth, t.hour, t.minute, kind(clip.kind)), M, y, 9f, INK)
                y += 14
            }
        }
    }

    /** 每晚一行，横轴 20 点到次日 12 点 */
    private fun sleepChart(c: Canvas, days: List<java.time.LocalDate>, r: DoctorReport, top: Float): Float {
        val left = M + 52; val right = W - M
        val from = 2 * 60; val to = 18 * 60
        fun x(at: Long) = left + (eveningMinutes(at).coerceIn(from, to) - from) * (right - left) / (to - from)
        val row = (300f / days.size.coerceAtLeast(1)).coerceIn(8f, 14f)
        val bar = row * .62f
        val bottom = top + 10 + row * days.size
        for (h in 20..36 step 2) {
            val gx = left + (h - 20) * 60 * (right - left) / (to - from)
            line(c, gx, top + 6, gx, bottom, LINE, .6f)
            text(c, "${h % 24}:00", gx, top + 4, 7f, GRAY, align = Paint.Align.CENTER)
        }
        days.forEachIndexed { i, d ->
            val cy = top + 10 + row * i + row / 2
            if (i % 2 == 1) fill(c, RectF(M, cy - row / 2, right, cy + row / 2), ZEBRA, 0f)
            text(c, "%d-%02d %s".format(d.monthValue, d.dayOfMonth, d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINA)), M, cy + 2.6f, 7f, GRAY)
            val n = r.nights.firstOrNull { it.summary.date.toString() == d.toString() } ?: return@forEachIndexed
            val t = n.summary.times ?: return@forEachIndexed
            val rough = n.summary.confidence == NightConfidence.Rough
            val alpha = if (rough) 110 else 255
            if (t.bedtime < t.onset) fill(c, RectF(x(t.bedtime), cy - bar / 2, x(t.onset), cy + bar / 2), BED, bar / 2, alpha)
            fill(c, RectF(x(t.onset), cy - bar / 2, x(t.wake), cy + bar / 2), SLEEP, bar / 2, alpha)
            n.snore.forEach { s -> fill(c, RectF(x(maxOf(s.start, t.onset)), cy - bar / 2, maxOf(x(minOf(s.end, t.wake)), x(maxOf(s.start, t.onset)) + .8f), cy + bar / 2), SNORE, 0f, alpha) }
            n.awake.filter { it.millis >= 120_000 }.forEach { a -> fill(c, RectF(x(a.start), cy - bar / 2 - .5f, x(a.end), cy + bar / 2 + .5f), if (i % 2 == 1) ZEBRA else 0xFFFFFFFF.toInt(), 0f) }
            if (rough) text(c, "参考", right + 2, cy + 2.4f, 6f, GRAY)
        }
        return bottom
    }

    private fun snoreChart(c: Canvas, days: List<java.time.LocalDate>, r: DoctorReport, top: Float): Float {
        val left = M + 52; val right = W - M; val h = 80f; val base = top + 14 + h
        val minutes = days.map { d -> r.nights.firstOrNull { it.summary.date.toString() == d.toString() }?.summary }
        val max = (minutes.maxOfOrNull { (it?.snoreMillis ?: 0) / 60_000 } ?: 0).coerceAtLeast(10).let { ((it + 9) / 10) * 10 }
        listOf(0, max / 2, max).forEach { v ->
            val gy = base - v * h / max
            line(c, left, gy, right, gy, LINE, .6f)
            text(c, "$v", left - 6, gy + 2.5f, 7f, GRAY, align = Paint.Align.RIGHT)
        }
        val slot = (right - left) / days.size.coerceAtLeast(1)
        days.forEachIndexed { i, d ->
            val s = minutes[i]
            val cx = left + slot * i + slot / 2
            if (i % 5 == 0 || i == days.lastIndex) text(c, "%d-%02d".format(d.monthValue, d.dayOfMonth), cx, base + 10, 6.5f, GRAY, align = Paint.Align.CENTER)
            s ?: return@forEachIndexed
            val m = s.snoreMillis / 60_000f
            if (m > 0) fill(c, RectF(cx - slot * .32f, base - m * h / max, cx + slot * .32f, base), SNORE, 1.5f, if (s.confidence == NightConfidence.Rough) 110 else 255)
            if (s.snoreBreaks > 0) text(c, "${s.snoreBreaks}", cx, base - m * h / max - 3, 6.5f, INK, bold = true, align = Paint.Align.CENTER)
        }
        return base + 12
    }

    // ---------- 第 2 页 ----------

    private fun pageTwo(c: Canvas, r: DoctorReport) {
        var y = M + 18
        text(c, "每晚明细", M, y, 16f, INK, bold = true)
        text(c, "${r.from} 至 ${r.to}", W - M, y, 10f, GRAY, align = Paint.Align.RIGHT)
        y += 20
        val cols = listOf("日期" to 62f, "上床" to 44f, "入睡" to 44f, "起床" to 44f, "睡眠" to 66f, "疑似鼾声" to 58f, "鼾声中断" to 50f, "咳嗽/清嗓" to 50f, "醒来" to 38f, "可信度" to 59f)
        val rowH = 19f
        fill(c, RectF(M, y, W - M, y + rowH), CARD, 6f)
        var x = M + 8
        cols.forEach { (name, w) -> text(c, name, x, y + 12.5f, 8.5f, INK, bold = true); x += w }
        y += rowH
        val nights = r.nights.sortedBy { it.summary.date }
        nights.forEachIndexed { i, n ->
            if (y + rowH > H - M - 24) return@forEachIndexed
            if (i % 2 == 1) fill(c, RectF(M, y, W - M, y + rowH), ZEBRA, 0f)
            val s = n.summary; val t = s.times
            val cells = listOf(s.date.toString().drop(5) + " " + s.date.toJavaDay(), t?.let { clock(it.bedtime) } ?: "—", t?.let { clock(it.onset) } ?: "—", t?.let { clock(it.wake) } ?: "—",
                s.sleepMillis?.let(::hm) ?: "—", "${s.snoreMillis / 60_000} 分", "${s.snoreBreaks}", "${s.coughCount}", "${s.awakeCount}", s.confidence?.title ?: if (s.state == NightState.Ready) "—" else "整理中")
            x = M + 8
            cells.forEachIndexed { k, v -> text(c, v, x, y + 12.5f, 8.5f, if (k == 0) GRAY else INK); x += cols[k].second }
            y += rowH
        }
        line(c, M, y, W - M, y, LINE, .6f)
        y += 16
        wrap(c, "睡眠＝入睡到起床，减去中途醒来；醒来只数 2 分钟以上的。可信度看手机位置、背景响度和记录是否中断。", M, y, W - 2 * M, 8f, GRAY)
    }

    // ---------- 小工具 ----------

    private fun footer(c: Canvas, page: Int) {
        val y = H - M + 14
        line(c, M, y - 12, W - M, y - 12, LINE, .6f)
        text(c, "由手机录音和手机使用情况自动统计，仅供参考，不是医疗检查结果。", M, y, 7.5f, GRAY)
        text(c, "第 $page 页 / 共 2 页", W - M, y, 7.5f, GRAY, align = Paint.Align.RIGHT)
    }

    private fun dates(r: DoctorReport): List<java.time.LocalDate> {
        val from = java.time.LocalDate.parse(r.from.toString()); val to = java.time.LocalDate.parse(r.to.toString())
        return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
    }

    private fun valid(r: DoctorReport): List<DoctorNight> = r.nights.filter { it.summary.state == NightState.Ready && it.summary.confidence != NightConfidence.Rough && it.summary.sleepMillis != null }

    private fun kotlinx.datetime.LocalDate.toJavaDay() = java.time.LocalDate.parse(toString()).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINA)

    private fun kind(k: NightEventKind) = when (k) { NightEventKind.Snore -> "鼾声"; NightEventKind.Cough -> "咳嗽/清嗓"; else -> "声音" }

    private fun hm(ms: Long): String { val m = ms.coerceAtLeast(0) / 60_000; return if (m >= 60) "${m / 60}小时${m % 60}分" else "${m}分" }

    private fun clock(at: Long): String = Instant.ofEpochMilli(at).atZone(zone).let { "%d:%02d".format(it.hour, it.minute) }

    /** [m] 是从 18 点起算的分钟 */
    private fun clock(m: Double): String { val v = m.roundToInt(); return "%d:%02d".format((v / 60 + 18) % 24, v % 60) }

    private fun sd(v: List<Int>): Int { val mean = v.average(); return sqrt(v.sumOf { (it - mean) * (it - mean) } / v.size).roundToInt() }

    private fun section(c: Canvas, title: String, y: Float) = text(c, title, M, y, 11f, INK, bold = true)

    private fun legend(c: Canvas, y: Float, items: List<Pair<Int, String>>) {
        var x = W - M
        items.reversed().forEach { (color, label) ->
            val p = paint(7.5f, GRAY); val w = p.measureText(label)
            text(c, label, x, y, 7.5f, GRAY, align = Paint.Align.RIGHT)
            x -= w + 4
            if (!label.startsWith("上方")) fill(c, RectF(x - 9, y - 6, x, y), color, 2f)
            x -= 16
        }
    }

    private fun paint(size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color; textAlign = align
        typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        c.drawText(s, x, y, paint(size, color, bold, align))

    /** 按宽度换行，返回最后一行的基线 */
    private fun wrap(c: Canvas, s: String, x: Float, y: Float, width: Float, size: Float, color: Int): Float {
        val p = paint(size, color); var line = ""; var yy = y
        for (ch in s) {
            if (p.measureText(line + ch) > width) { c.drawText(line, x, yy, p); line = ""; yy += size * 1.5f }
            line += ch
        }
        if (line.isNotEmpty()) c.drawText(line, x, yy, p)
        return yy + size * .5f
    }

    private fun fill(c: Canvas, r: RectF, color: Int, radius: Float, alpha: Int = 255) {
        if (r.width() <= 0f) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; this.alpha = alpha * (color ushr 24) / 255 }
        if (radius > 0f) c.drawRoundRect(r, radius, radius, p) else c.drawRect(r, p)
    }

    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float) =
        c.drawLine(x1, y1, x2, y2, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = width })
}
