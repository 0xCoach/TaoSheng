package app.resound.ui.night

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.FileProvider
import app.resound.AppGraph
import app.resound.night.NightConfidence
import app.resound.night.NightEvent
import app.resound.night.NightEventKind
import app.resound.ui.theme.Rs
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 卡 202/203 夜里界面的共用状态：哪个面板开着。放在顶层，通知点进来也能打开。 */
internal object NightUi {
    /** 打开“昨晚”详情（醒来那天） */
    var openDate by mutableStateOf<LocalDate?>(null)
    /** 打开记梦（通知“记梦”或详情里点） */
    var dreamNight by mutableStateOf<Long?>(null)
    var trendOpen by mutableStateOf(false)
    /** 卡 208：打开某次午睡（记录 id） */
    var openNap by mutableStateOf<Long?>(null)
    /** 今日节奏当前显示的午睡，点图时判断落在午睡里还是夜里 */
    var shownNaps: List<app.resound.night.NightSummary> = emptyList()
    /** [from, until) 里有午睡就打开午睡，返回 true */
    fun openNapIn(from: Long, until: Long): Boolean {
        val id = shownNaps.firstOrNull { n -> n.times?.let { it.onset < until && it.wake > from } == true }?.id ?: return false
        openNap = id; return true
    }

    val ready: Boolean get() = AppGraph.isNightReady
}

/** 今天页顶层挂一次：三个面板都从这里出，不跟着列表里的卡片一起被回收 */
@Composable
fun NightSheets() {
    if (!NightUi.ready) return
    NightUi.openDate?.let { NightSheet(it) { NightUi.openDate = null } }
    NightUi.dreamNight?.let { DreamSheet(it) { NightUi.dreamNight = null } }
    if (NightUi.trendOpen) NightTrendSheet { NightUi.trendOpen = false }
    NightUi.openNap?.let { NapSheet(it) { NightUi.openNap = null } }
}

internal fun clock(at: Long): String {
    val t = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    return "%02d:%02d".format(t.hour, t.minute)
}

/** 6 小时 40 分 / 40 分 / 不到 1 分 */
internal fun span(ms: Long): String {
    val m = ms / 60_000
    return when {
        m <= 0 -> "不到 1 分"
        m < 60 -> "$m 分"
        m % 60 == 0L -> "${m / 60} 小时"
        else -> "${m / 60} 小时 ${m % 60} 分"
    }
}

/** 紧凑写法，给数字格和图表：6小时40分 / 40分 */
internal fun hours(ms: Long): String { val m = ms / 60_000; return if (m >= 60) "${m / 60}小时" + if (m % 60 > 0) "${m % 60}分" else "" else "${m}分" }

@Composable
internal fun confidenceColor(conf: NightConfidence?): Color = when (conf) {
    NightConfidence.Good -> Rs.colors.good
    NightConfidence.Fair -> Rs.colors.warning
    NightConfidence.Rough, null -> Rs.colors.textTertiary
}

/** 事件在声音地图和列表里的颜色 */
@Composable
internal fun kindColor(kind: NightEventKind): Color {
    val c = Rs.colors
    return when (kind) {
        NightEventKind.Snore -> SnoreColor
        NightEventKind.Cough -> c.warning
        NightEventKind.SleepTalk -> c.good
        NightEventKind.SnoreBreak -> c.critical
        NightEventKind.Awake -> c.textSecondary
        NightEventKind.Playback -> c.textTertiary
        NightEventKind.Gap, NightEventKind.Turn -> c.separator
    }
}

/** 同屋有人时写“卧室里的”，梦话只有认出是你才写“你” */
internal fun kindTitle(e: NightEvent, sharedRoom: Boolean): String = when (e.kind) {
    NightEventKind.Snore -> if (sharedRoom) "卧室里的鼾声" else "鼾声"
    NightEventKind.Cough -> if (sharedRoom) "卧室里疑似咳嗽/清嗓" else "疑似咳嗽/清嗓"
    NightEventKind.SleepTalk -> if (e.mine) "你的梦话" else "夜间人声"
    NightEventKind.Awake -> "醒了"
    NightEventKind.Playback -> "手机在放声音"
    NightEventKind.Gap -> "没有记录"
    NightEventKind.Turn -> "翻身"
    NightEventKind.SnoreBreak -> "鼾声中断"
}

/** 鼾声、咳嗽有片段，才问“是不是” */
internal fun askable(e: NightEvent) = e.kind == NightEventKind.Snore || e.kind == NightEventKind.Cough

/** 分享面板：文件须在缓存目录（FileProvider 只开放 cache） */
internal fun share(context: Context, files: List<File>, mime: String, title: String) {
    val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) }
    val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).apply { type = mime; putExtra(Intent.EXTRA_STREAM, uris.first()) }
    else Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "*/*"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)) }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** 回听片段：复用声纹样本播放器（同一时间只放一段，播放时暂停收音） */
internal suspend fun playClip(clipId: Long): Boolean {
    val ref = AppGraph.night.audio(clipId) ?: return false
    val file = File(ref.id)
    if (!file.exists()) return false
    app.resound.ui.voiceprint.SamplePlayer.play("night-$clipId", file)
    return true
}

internal fun clipKey(clipId: Long) = "night-$clipId"

/** 夜空紫：鼾声用深紫，和说话的橙、手机的灰蓝、咳嗽的琥珀都分得开 */
internal val SnoreColor = Color(0xFF7E4FC6)
