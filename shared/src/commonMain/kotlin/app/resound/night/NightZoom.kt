package app.resound.night

/** 卡 210：声音详情图当前看的时间段 */
data class NightView(val from: Long, val to: Long) { val span get() = to - from }

/** 卡 210：声音详情图放大缩小的纯计算（平台无关，iOS 共用） */
object NightZoom {
    /** 最多放大到 10 分钟一屏：每根柱 5 秒 */
    const val MIN_SPAN = 10 * 60_000L
    private val STEPS = listOf(1, 2, 5, 10, 15, 30, 60, 120, 180).map { it * 60_000L }

    /** 在 [start, end] 里放一个长度为 span 的窗口，尽量以 center 为中心 */
    fun around(center: Long, span: Long, start: Long, end: Long): NightView {
        val total = (end - start).coerceAtLeast(1)
        val s = span.coerceIn(minOf(MIN_SPAN, total), total)
        val from = (center - s / 2).coerceIn(start, end - s)
        return NightView(from, from + s)
    }

    /** factor > 1 放大；focus 是手指在图上的位置（0～1），放大后那一刻仍在手指下 */
    fun zoom(v: NightView, focus: Float, factor: Float, start: Long, end: Long): NightView {
        val f = focus.coerceIn(0f, 1f)
        val at = v.from + (v.span * f).toLong()
        val total = (end - start).coerceAtLeast(1)
        val span = (v.span / factor.coerceAtLeast(.01f)).toLong().coerceIn(minOf(MIN_SPAN, total), total)
        val from = (at - (span * f).toLong()).coerceIn(start, end - span)
        return NightView(from, from + span)
    }

    fun pan(v: NightView, delta: Long, start: Long, end: Long): NightView {
        val from = (v.from + delta).coerceIn(start, (end - v.span).coerceAtLeast(start))
        return NightView(from, from + v.span)
    }

    /** 双击：整夜 → 1 小时 → 10 分钟 → 回到整夜 */
    fun nextLevel(v: NightView, at: Long, start: Long, end: Long): NightView = when {
        v.span > 60 * 60_000L + 1 -> around(at, 60 * 60_000L, start, end)
        v.span > MIN_SPAN + 1 -> around(at, MIN_SPAN, start, end)
        else -> NightView(start, end)
    }

    /** 刻度间隔：让一屏的刻度不超过 max 个 */
    fun tickStep(span: Long, max: Int = 6): Long = STEPS.firstOrNull { span / it <= max } ?: STEPS.last()

    /** 落在窗口里的刻度时刻；offset 是当地时区相对 UTC 的毫秒，刻度对齐到当地整点/整分 */
    fun ticks(v: NightView, offset: Long, max: Int = 6): List<Long> {
        val step = tickStep(v.span, max)
        var t = (v.from + offset).floorDiv(step) * step - offset
        if (t < v.from) t += step
        return generateSequence(t) { it + step }.takeWhile { it <= v.to }.toList()
    }

    /** 某时刻的读数：最大响度比背景高多少分贝；不在记录范围内返回 null */
    fun aboveBackground(env: NightEnvelope, at: Long): Int? {
        val i = ((at - env.start) / env.stepMillis).toInt()
        if (at < env.start || i !in env.peakDb.indices) return null
        return (env.peakDb[i] - env.backgroundDb[i]).coerceAtLeast(0)
    }

    /** [from, to] 里最响的那一刻（按比背景高多少算），返回该点起点时刻和分贝 */
    fun loudest(env: NightEnvelope, from: Long, to: Long): Pair<Long, Int>? {
        val first = ((from - env.start) / env.stepMillis).toInt().coerceAtLeast(0)
        val last = ((to - env.start) / env.stepMillis).toInt().coerceAtMost(env.peakDb.size - 1)
        if (first > last) return null
        val i = (first..last).maxByOrNull { env.peakDb[it] - env.backgroundDb[it] } ?: return null
        return env.start + i * env.stepMillis to (env.peakDb[i] - env.backgroundDb[i]).coerceAtLeast(0)
    }
}

/**
 * 卡 210：整夜只留最响的几下。每 2 秒喂一次（窗口起点、比背景高多少分贝）；
 * 比已留的都响就先挂起，等后文够了（[NightTuning.LOUD_AFTER]）再交出去存；彼此相隔不到 [NightTuning.LOUD_GAP] 的只留更响的。
 */
class LoudPicker(existing: List<Pick>) {
    data class Pick(val id: Long, val at: Long, val db: Int)
    private val picks = existing.toMutableList()
    private var pending: Pair<Long, Int>? = null

    /** 返回此刻该存下来的那一下（起点、分贝），没有就是 null */
    fun offer(at: Long, db: Int): Pair<Long, Int>? {
        var due: Pair<Long, Int>? = null
        pending?.let { p -> if (at - p.first >= NightTuning.LOUD_AFTER) { due = p; pending = null } }
        if (db < NightTuning.LOUD_MIN_DB) return due
        pending?.let { p -> if (at - p.first < NightTuning.LOUD_AFTER) { if (db > p.second) pending = at to db; return due } }
        val near = picks.filter { kotlin.math.abs(it.at - at) < NightTuning.LOUD_GAP }
        if (near.any { it.db >= db }) return due
        if (near.isEmpty() && picks.size >= NightTuning.LOUD_KEEP && picks.minOf { it.db } >= db) return due
        pending = at to db
        return due
    }

    /** 卡 213：亮屏了——挂着的那一下若离现在不到 [NightTuning.LOUD_AWAKE_MARGIN]，多半是起身拿手机的声音，作废 */
    fun awake(at: Long) { pending?.let { if (at - it.first < NightTuning.LOUD_AWAKE_MARGIN) pending = null } }

    /** 一夜结束时把还挂着的交出去 */
    fun flush(): Pair<Long, Int>? = pending.also { pending = null }

    /** 存好后登记；返回被挤掉、要连录音一起删的旧 id */
    fun kept(id: Long, at: Long, db: Int): List<Long> {
        val replaced = picks.filter { kotlin.math.abs(it.at - at) < NightTuning.LOUD_GAP }
        picks.removeAll(replaced); picks += Pick(id, at, db)
        val extra = picks.sortedByDescending { it.db }.drop(NightTuning.LOUD_KEEP)
        picks.removeAll(extra)
        return (replaced + extra).map { it.id }
    }
}
