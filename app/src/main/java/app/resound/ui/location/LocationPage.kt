package app.resound.ui.location

import kotlinx.datetime.toKotlinLocalDate
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.resound.capture.CaptureService
import app.resound.location.LocationPoint
import app.resound.location.LocationRepository
import app.resound.location.Place
import app.resound.location.PlaceGroup
import app.resound.location.SpanKind
import app.resound.location.Timeline
import app.resound.location.DayTimeline
import app.resound.location.Places
import app.resound.location.Stay
import app.resound.location.Track
import app.resound.ui.skills.Toggle
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.springClick
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

private val hm = DateTimeFormatter.ofPattern("HH:mm")
private fun clock(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(hm)
private fun span(minutes: Long) = if (minutes < 60) "${minutes} 分钟" else "${minutes / 60} 小时" + (minutes % 60).let { if (it > 0) " $it 分" else "" }

/** 设置 → 足迹：开关、权限、按天看轨迹和停留。 */
@Composable
fun LocationPage(repo: LocationRepository, bottom: Dp) {
    val c = Rs.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val enabled by repo.settings.enabled.collectAsStateWithLifecycle()
    // 权限在系统页改完回来要刷新
    var permTick by remember { mutableIntStateOf(0) }
    // 从系统权限页、定位设置回来，或刚给完权限：重设前台类型，并立刻要一次位置
    fun refreshNow() { permTick++; CaptureService.refreshLocation(); if (repo.settings.enabled.value) repo.tracker.fixNow() }
    LifecycleResumeEffect(Unit) { refreshNow(); onPauseOrDispose { } }
    val fg = remember(permTick) { repo.settings.hasForeground() }
    val bg = remember(permTick) { repo.settings.hasBackground() }
    val systemOn = remember(permTick) { repo.settings.systemEnabled() }
    val askBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshNow() }
    val askForeground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshNow() }
    fun openAppSettings() = context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    var date by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    val day = LocalDate.ofEpochDay(date)
    val points by remember(date) { repo.day(day) }.collectAsStateWithLifecycle(emptyList())
    val track = remember(points) { Track.day(points) }
    val zone = ZoneId.systemDefault()
    val dayFrom = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayTo = if (day == LocalDate.now()) System.currentTimeMillis() else day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val manual by repo.places.manual.collectAsStateWithLifecycle(emptyList())
    val timeline = remember(track, dayTo / 60_000, manual) {
        Timeline.build(track.points, track.stays, dayFrom, dayTo) { la, ln -> Places.nearestManual(manual, la, ln)?.home == true }
    }
    // 正在改的地点：坐标 + 当前显示的地名
    var editing by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var view by rememberSaveable { mutableIntStateOf(0) }
    val error by repo.tracker.lastError.collectAsStateWithLifecycle()
    var confirmClear by remember(date) { mutableStateOf(false) }
    val lookup by repo.settings.lookup.collectAsStateWithLifecycle()
    val hasKey by repo.settings.hasAmapKey.collectAsStateWithLifecycle()
    val latest by remember { repo.latest() }.collectAsStateWithLifecycle(null)
    // 0.2.21：停留点下挂这段时间里的事；当天事件取一次
    val memory = if (app.resound.AppGraph.isMemoryReady) app.resound.AppGraph.memory else null
    val dayEvents by remember(date, memory) { memory?.events(day.toKotlinLocalDate()) ?: kotlinx.coroutines.flow.flowOf(emptyList()) }.collectAsStateWithLifecycle(emptyList())
    var openEvent by remember { mutableStateOf<app.resound.digest.MemoryEvent?>(null) }
    // 地名按约 100 米格子取；先读缓存，再逐个去查
    val places = remember { androidx.compose.runtime.mutableStateMapOf<String, Place?>() }
    val wanted = remember(track.stays, latest) { (listOfNotNull(latest?.let { it.lat to it.lng }) + track.stays.map { it.lat to it.lng }).distinctBy { Places.cell(it.first, it.second) } }
    androidx.compose.runtime.LaunchedEffect(wanted, lookup, hasKey) {
        for ((lat, lng) in wanted) {
            val cell = Places.cell(lat, lng)
            if (places[cell] != null) continue
            places[cell] = repo.places.cached(lat, lng) ?: repo.places.resolve(lat, lng)
        }
    }
    val placeError by repo.places.lastError.collectAsStateWithLifecycle()
    // 手动改过的优先，其次自动查到的
    val placeAt: (Double, Double) -> Place? = { la, ln -> with(Places) { nearestManual(manual, la, ln)?.toPlace() } ?: places[Places.cell(la, ln)] }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottom + 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RsCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("记录全天位置", style = RsType.headline, color = c.text)
                    Spacer(Modifier.height(4.dp))
                    Text("跟随收音服务，约每 5 分钟记一个点；位置记录只存手机本地。", style = RsType.subhead, color = c.textSecondary)
                }
                Spacer(Modifier.width(12.dp))
                Toggle(enabled) { on ->
                    repo.settings.setEnabled(on)
                    if (on && !fg) askForeground.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    else refreshNow()
                }
            }
        }
        if (enabled && (!fg || !bg)) {
            RsCard(color = c.accentSoft) {
                Text(if (!fg) "需要定位权限" else "建议改成“始终允许”", style = RsType.headline, color = c.text)
                Spacer(Modifier.height(4.dp))
                Text(if (!fg) "没有定位权限时不会记录位置。" else "只给“使用时允许”时，手机重启或系统拉起收音后可能记不到位置，需要回到涛声里打开一次。",
                    style = RsType.subhead, color = c.textSecondary)
                Spacer(Modifier.height(10.dp))
                Text("去授权", style = RsType.headline, color = c.accent, modifier = Modifier.springClick {
                    when {
                        !fg -> askForeground.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        android.os.Build.VERSION.SDK_INT >= 30 -> openAppSettings()
                        else -> askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                })
            }
        }
        if (enabled && fg && !systemOn) {
            RsCard(color = c.accentSoft) {
                Text("手机定位总开关没开", style = RsType.headline, color = c.text)
                Spacer(Modifier.height(4.dp))
                Text("权限已经给了，但系统“位置信息”关着，拿不到任何位置。下拉控制中心打开“位置信息”即可。", style = RsType.subhead, color = c.textSecondary)
                Spacer(Modifier.height(10.dp))
                Text("去打开", style = RsType.headline, color = c.accent, modifier = Modifier.springClick {
                    runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                })
            }
        }
        if (enabled && fg && !CaptureService.alive) {
            Text("收音没有开启，足迹也不会记录。", style = RsType.subhead, color = c.warning, modifier = Modifier.padding(horizontal = 4.dp))
        }
        error?.let { if (enabled) Text(it, style = RsType.subhead, color = c.warning, modifier = Modifier.padding(horizontal = 4.dp)) }

        latest?.let { p ->
            RsCard(Modifier.springClick { editing = p.lat to p.lng }) {
                Text("最后位置 · " + clock(p.at) + ago(p.at), style = RsType.caption, color = c.textSecondary)
                Spacer(Modifier.height(6.dp))
                PlaceText(placeAt(p.lat, p.lng), p.lat, p.lng, p.accuracy, lookup)
                Spacer(Modifier.height(6.dp))
                Text("点一下修改名称", style = RsType.caption, color = c.accent)
            }
        }

        // 日期切换
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("‹", style = RsType.title, color = c.accent, modifier = Modifier.springClick { date -= 1 }.padding(horizontal = 14.dp, vertical = 4.dp))
            Text(day.format(DateTimeFormatter.ofPattern("M月d日 EEE", Locale.CHINA)) + if (day == LocalDate.now()) " · 今天" else "",
                style = RsType.headline, color = c.text)
            val canNext = day < LocalDate.now()
            Text("›", style = RsType.title, color = if (canNext) c.accent else c.textTertiary,
                modifier = Modifier.springClick { if (canNext) date += 1 }.padding(horizontal = 14.dp, vertical = 4.dp))
        }

        RsCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("这一天", style = RsType.headline, color = c.text, modifier = Modifier.weight(1f))
                app.resound.ui.skills.Segments(listOf("时间轴", "路线"), view) { view = it }
            }
            Spacer(Modifier.height(12.dp))
            if (track.points.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Text("这一天没有位置记录", style = RsType.subhead, color = c.textTertiary)
                }
            } else if (view == 0) DayTimelineView(timeline, dayFrom, day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), placeAt)
            else TrackCanvas(track.points, track.stays)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                Stat("在家", dur(timeline.homeMillis), Modifier.weight(1f))
                Stat("外出", dur(timeline.awayMillis), Modifier.weight(1f))
                Stat("移动距离", String.format(Locale.US, "%.1f km", track.meters / 1000), Modifier.weight(1f))
            }
        }

        if (track.stays.isNotEmpty()) {
            Text("停留", style = RsType.section, color = c.textSecondary, modifier = Modifier.padding(horizontal = 4.dp))
            RsCard(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
                track.stays.forEachIndexed { i, s ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
                    StayRow(s, placeAt(s.lat, s.lng), lookup, timeline.groupOf(s)) { editing = s.lat to s.lng }
                    app.resound.ui.context.StayEvents(s.start, s.end, dayEvents) { openEvent = it }
                }
            }
        }

        PlaceSettings(repo, lookup, hasKey, placeError) { places.clear() }

        if (points.isNotEmpty()) {
            Text(if (confirmClear) "再点一次确认清空这一天" else "清空这一天", style = RsType.subhead, color = c.critical,
                modifier = Modifier.align(Alignment.CenterHorizontally).springClick {
                    if (!confirmClear) confirmClear = true else scope.launch { repo.clear(day); confirmClear = false }
                }.padding(8.dp))
        }
    }
    if (memory != null) openEvent?.let { e -> app.resound.ui.today.EventSheet(e, memory) { openEvent = null } }
    editing?.let { (la, ln) ->
        EditPlaceSheet(repo, la, ln, placeAt(la, ln), Places.nearestManual(manual, la, ln), context) { editing = null }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    val c = Rs.colors
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = RsType.headline, color = c.text)
        Text(label, style = RsType.caption, color = c.textSecondary)
    }
}

@Composable
private fun StayRow(s: Stay, place: Place?, lookup: Boolean, group: PlaceGroup?, onEdit: () -> Unit) {
    val c = Rs.colors
    // 点一下修改这个地点的名称
    Row(Modifier.fillMaxWidth().springClick(onEdit).padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                group?.let { Box(Modifier.size(8.dp).clip(CircleShape).background(groupColor(it))); Spacer(Modifier.width(6.dp)) }
                Text("${clock(s.start)}–${clock(s.end)} · ${span(s.minutes)}" + if (group?.home == true) " · 家" else "", style = RsType.caption, color = c.textSecondary)
            }
            Spacer(Modifier.height(2.dp))
            PlaceText(place, s.lat, s.lng, s.accuracy, lookup)
        }
        Text("›", style = RsType.headline, color = c.textTertiary, modifier = Modifier.padding(start = 8.dp))
    }
}

/** 地名一行、详细地址一行、坐标一行；还没查到时只显示坐标。 */
@Composable
private fun PlaceText(place: Place?, lat: Double, lng: Double, accuracy: Float, lookup: Boolean) {
    val c = Rs.colors
    val coord = String.format(Locale.US, "%.5f, %.5f · ±%d 米", lat, lng, accuracy.toInt())
    if (place != null) {
        Text(place.name, style = RsType.headline, color = c.text)
        if (place.address.isNotBlank() && place.address != place.name) Text(place.address, style = RsType.subhead, color = c.textSecondary)
        Text(coord + " · " + if (place.manual) "手动" else place.source, style = RsType.caption, color = c.textTertiary)
    } else {
        Text(if (lookup) "正在查地名…" else "未开启地名", style = RsType.body, color = c.textSecondary)
        Text(coord, style = RsType.caption, color = c.textTertiary)
    }
}

private fun ago(at: Long): String {
    val m = (System.currentTimeMillis() - at) / 60_000
    return when { m < 1 -> "（刚刚）"; m < 60 -> "（$m 分钟前）"; m < 24 * 60 -> "（${m / 60} 小时前）"; else -> "（${m / 1440} 天前）" }
}

/** 地名设置：开关 + 高德 Key（可选）。 */
@Composable
private fun PlaceSettings(repo: LocationRepository, lookup: Boolean, hasKey: Boolean, error: String?, onChanged: () -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    RsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("显示地名", style = RsType.headline, color = c.text)
                Spacer(Modifier.height(4.dp))
                Text("把停留处的坐标发给${if (hasKey) "高德" else "手机系统的地理服务"}查地址；查过的地方存在本机，不重复查。",
                    style = RsType.subhead, color = c.textSecondary)
            }
            Spacer(Modifier.width(12.dp))
            Toggle(lookup) { repo.settings.setLookup(it); if (it) onChanged() }
        }
        if (lookup) {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("高德 Key（可选）", style = RsType.body, color = c.text)
                    Text(if (hasKey) "已填写，地名更准" else "不填用系统地理服务；国内建议填高德 Web 服务 Key", style = RsType.caption, color = c.textSecondary)
                }
                Text(if (hasKey) "更换" else "填写", style = RsType.headline, color = c.accent, modifier = Modifier.springClick { editing = !editing; key = "" }.padding(8.dp))
            }
            if (editing) {
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.text.BasicTextField(
                    value = key, onValueChange = { key = it.trim() }, singleLine = true,
                    textStyle = RsType.body.copy(color = c.text), cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).background(c.fill).padding(horizontal = 14.dp, vertical = 12.dp)) {
                            if (key.isEmpty()) Text("粘贴高德 Web 服务 Key", style = RsType.body, color = c.textTertiary)
                            inner()
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("保存", style = RsType.headline, color = if (key.isNotBlank()) c.accent else c.textTertiary, modifier = Modifier.springClick {
                        if (key.isNotBlank()) scope.launch { repo.settings.setAmapKey(key); repo.places.clearCache(); editing = false; onChanged() }
                    }.padding(vertical = 6.dp))
                    if (hasKey) Text("清除 Key", style = RsType.headline, color = c.critical, modifier = Modifier.springClick {
                        scope.launch { repo.settings.setAmapKey(null); repo.places.clearCache(); editing = false; onChanged() }
                    }.padding(vertical = 6.dp))
                }
            }
            error?.let { Spacer(Modifier.height(8.dp)); Text(it, style = RsType.caption, color = c.warning) }
        }
    }
}

/** 没有地图底图：按经纬度等比例画点和连线，停留处画大圆点。 */
@Composable
private fun TrackCanvas(points: List<LocationPoint>, stays: List<Stay>) {
    val c = Rs.colors
    Canvas(Modifier.fillMaxWidth().height(200.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).background(c.fill)) {
        val minLat = points.minOf { it.lat }; val maxLat = points.maxOf { it.lat }
        val minLng = points.minOf { it.lng }; val maxLng = points.maxOf { it.lng }
        val midLat = Math.toRadians((minLat + maxLat) / 2)
        // 经度按纬度余弦缩放，避免横向被拉长；最少按约 300 米范围画，单点不会顶满
        val spanX = maxOf((maxLng - minLng) * Math.cos(midLat), 0.003)
        val spanY = maxOf(maxLat - minLat, 0.003)
        val pad = 18.dp.toPx()
        val scale = minOf((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY)
        val cx = (minLng + maxLng) / 2; val cy = (minLat + maxLat) / 2
        fun pos(lat: Double, lng: Double) = Offset(
            (size.width / 2 + (lng - cx) * Math.cos(midLat) * scale).toFloat(),
            (size.height / 2 - (lat - cy) * scale).toFloat())
        val path = Path()
        points.forEachIndexed { i, p -> val o = pos(p.lat, p.lng); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
        drawPath(path, c.accent.copy(alpha = 0.55f), style = Stroke(width = 2.5.dp.toPx()))
        points.forEach { drawCircle(c.accent.copy(alpha = 0.5f), 2.dp.toPx(), pos(it.lat, it.lng)) }
        stays.forEach { drawCircle(c.accent, 6.dp.toPx(), pos(it.lat, it.lng)) }
        drawCircle(c.good, 7.dp.toPx(), pos(points.first().lat, points.first().lng))
        drawCircle(c.critical, 7.dp.toPx(), pos(points.last().lat, points.last().lng))
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(c.good)); Text(" 起点  ", style = RsType.caption, color = c.textSecondary)
        Box(Modifier.size(8.dp).clip(CircleShape).background(c.critical)); Text(" 终点  ", style = RsType.caption, color = c.textSecondary)
        Box(Modifier.size(8.dp).clip(CircleShape).background(c.accent)); Text(" 停留", style = RsType.caption, color = c.textSecondary)
    }
}

private fun dur(ms: Long): String {
    val m = ms / 60_000
    return when { m < 60 -> "$m 分"; m % 60 == 0L -> "${m / 60} 小时"; else -> "${m / 60}时${m % 60}分" }
}

/** 地点配色：家用主色，其余按待的时长依次取色，第 6 个以后统一灰。 */
@Composable
private fun groupColor(g: PlaceGroup): androidx.compose.ui.graphics.Color {
    val c = Rs.colors
    if (g.home) return c.accent
    val palette = listOf(androidx.compose.ui.graphics.Color(0xFF4A7FD4), c.good, androidx.compose.ui.graphics.Color(0xFF9B6BD3), c.warning, androidx.compose.ui.graphics.Color(0xFF2FA5A0))
    return palette.getOrElse(g.index) { c.textTertiary }
}

private fun groupName(g: PlaceGroup, place: Place?): String = when {
    g.home && (place == null || place.name == "家") -> "家"
    g.home -> "家 · ${place!!.name}"
    place != null -> place.name
    else -> "地点 ${g.index + 1}"
}

/**
 * 一天 0–24 点的横条：按地点分段上色，路上用浅色，没记录用灰色。
 * 点一下横条显示那段几点到几点、在哪；下面列出各地点一共待了多久。
 */
@Composable
private fun DayTimelineView(tl: DayTimeline, dayStart: Long, dayEnd: Long, placeAt: (Double, Double) -> Place?) {
    val c = Rs.colors
    val total = (dayEnd - dayStart).toFloat()
    var selected by remember(tl) { mutableStateOf<app.resound.location.Span?>(null) }
    val colors = tl.groups.associate { it.index to groupColor(it) }
    fun nameOf(index: Int?) = tl.groups.firstOrNull { it.index == index }?.let { groupName(it, placeAt(it.lat, it.lng)) } ?: ""

    // 选中段的说明
    val sel = selected
    Text(
        if (sel == null) "点时间轴看每段在哪" else "${clock(sel.start)}–${clock(sel.end)} · " + when (sel.kind) {
            SpanKind.Stay -> nameOf(sel.group); SpanKind.Move -> "路上"; SpanKind.Gap -> "没有记录"
        } + " · " + dur(sel.end - sel.start),
        style = RsType.subhead, color = if (sel == null) c.textTertiary else c.text, maxLines = 1,
    )
    Spacer(Modifier.height(8.dp))
    val moveColor = c.textSecondary
    val gapColor = c.separator
    val nowX = System.currentTimeMillis().takeIf { it in dayStart until dayEnd }?.let { (it - dayStart) / total }
    Canvas(Modifier.fillMaxWidth().height(34.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
        .tapFraction(tl) { fx -> val at = dayStart + (fx * total).toLong(); selected = tl.spans.firstOrNull { at in it.start until it.end }.takeIf { it != selected } }) {
        drawRect(gapColor)
        for (sp in tl.spans) {
            val x0 = (sp.start - dayStart) / total * size.width
            val x1 = (sp.end - dayStart) / total * size.width
            val dim = sel != null && sp != sel
            val alpha = if (dim) 0.35f else 1f
            when (sp.kind) {
                SpanKind.Stay -> drawRect((colors[sp.group] ?: moveColor).copy(alpha = alpha), Offset(x0, 0f), androidx.compose.ui.geometry.Size(x1 - x0, size.height))
                SpanKind.Move -> {
                    drawRect(moveColor.copy(alpha = 0.25f * alpha), Offset(x0, 0f), androidx.compose.ui.geometry.Size(x1 - x0, size.height))
                    // 斜纹表示“在路上”
                    clipRect(x0, 0f, x1, size.height) {
                        var x = x0 - size.height
                        while (x < x1) { drawLine(moveColor.copy(alpha = 0.6f * alpha), Offset(x, size.height), Offset(x + size.height, 0f), 1.5.dp.toPx()); x += 6.dp.toPx() }
                    }
                }
                SpanKind.Gap -> Unit
            }
            if (sp == sel) drawRect(c.text, Offset(x0, 0f), androidx.compose.ui.geometry.Size(x1 - x0, size.height), style = Stroke(2.dp.toPx()))
        }
        nowX?.let { drawLine(c.critical, Offset(it * size.width, 0f), Offset(it * size.width, size.height), 2.dp.toPx()) }
    }
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth()) {
        listOf("0", "6", "12", "18", "24").forEachIndexed { i, h ->
            Text(h, style = RsType.caption, color = c.textTertiary, modifier = Modifier.weight(1f),
                textAlign = when (i) { 0 -> androidx.compose.ui.text.style.TextAlign.Start; 4 -> androidx.compose.ui.text.style.TextAlign.End; else -> androidx.compose.ui.text.style.TextAlign.Center })
        }
    }

    // 时间分布
    Spacer(Modifier.height(14.dp))
    val recorded = tl.spans.filter { it.kind != SpanKind.Gap }.sumOf { it.end - it.start }.coerceAtLeast(1)
    val moveMs = tl.spans.filter { it.kind == SpanKind.Move }.sumOf { it.end - it.start }
    val rows = tl.groups.take(5).map { Triple(groupName(it, placeAt(it.lat, it.lng)), it.millis, colors[it.index]!!) } +
        listOfNotNull(tl.groups.drop(5).sumOf { it.millis }.takeIf { it > 0 }?.let { Triple("其他 ${tl.groups.size - 5} 处", it, c.textTertiary) },
            moveMs.takeIf { it > 0 }?.let { Triple("路上", it, moveColor) })
    if (recorded < 10 * 60_000L) {
        val first = tl.spans.firstOrNull { it.kind != SpanKind.Gap }?.start
        Text("刚开始记录" + (first?.let { "（${clock(it)} 起）" } ?: "") + "，约 10 分钟后能看出停留和各地点时长。", style = RsType.subhead, color = c.textTertiary)
        return
    }
    rows.filter { it.second >= 60_000L }.forEach { (name, ms, color) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(name, style = RsType.body, color = c.text, maxLines = 1, modifier = Modifier.width(120.dp))
            Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(c.separator)) {
                Box(Modifier.fillMaxWidth((ms.toFloat() / recorded).coerceIn(0.02f, 1f)).height(6.dp).clip(CircleShape).background(color))
            }
            Text(dur(ms), style = RsType.subhead, color = c.textSecondary, modifier = Modifier.padding(start = 10.dp).width(64.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

/** 点击横条：回调点击位置占宽度的比例。 */
private fun Modifier.tapFraction(key: Any, onTap: (Float) -> Unit) = this.pointerInput(key) {
    detectTapGestures { o -> onTap((o.x / size.width).coerceIn(0f, 0.9999f)) }
}

/** 修改地点：名称、地址、是不是家；也能恢复自动或用地图打开。改动对方圆 150 米生效。 */
@Composable
private fun EditPlaceSheet(repo: LocationRepository, lat: Double, lng: Double, place: Place?, manualRow: app.resound.location.PlaceRow?,
                           context: android.content.Context, onDismiss: () -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(place?.name.orEmpty()) }
    var address by remember { mutableStateOf(place?.address.orEmpty()) }
    var home by remember { mutableStateOf(manualRow?.home == true || place?.name == "家") }
    app.resound.ui.verify.RsSheet("修改这个地点", String.format(Locale.US, "%.5f, %.5f · 方圆约 150 米内都用这个名字", lat, lng), onDismiss = onDismiss) {
        Text("名称", style = RsType.subhead, color = c.textSecondary)
        Spacer(Modifier.height(6.dp))
        EditField(name, "比如：家、公司、健身房") { name = it }
        Spacer(Modifier.height(12.dp))
        Text("地址（可不填）", style = RsType.subhead, color = c.textSecondary)
        Spacer(Modifier.height(6.dp))
        EditField(address, "详细地址") { address = it }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("这里是家", style = RsType.body, color = c.text)
                Text("时间轴按这里算“在家”，不再按夜里自动猜", style = RsType.caption, color = c.textSecondary)
            }
            Toggle(home) { home = it }
        }
        Spacer(Modifier.height(18.dp))
        val canSave = name.isNotBlank() || home
        Box(Modifier.fillMaxWidth().clip(CircleShape).background(if (canSave) c.accent else c.separator).springClick {
            if (canSave) scope.launch {
                repo.places.setManual(manualRow?.lat ?: lat, manualRow?.lng ?: lng, name.ifBlank { "家" }, address, home, manualRow)
                onDismiss()
            }
        }.padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
            Text("保存", style = RsType.headline, color = androidx.compose.ui.graphics.Color.White)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            if (manualRow != null) Text("恢复自动", style = RsType.headline, color = c.critical, modifier = Modifier.springClick {
                scope.launch { repo.places.removeManual(manualRow); onDismiss() }
            }.padding(8.dp))
            Text("在地图中打开", style = RsType.headline, color = c.accent, modifier = Modifier.springClick {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }.padding(8.dp))
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun EditField(value: String, hint: String, onChange: (String) -> Unit) {
    val c = Rs.colors
    androidx.compose.foundation.text.BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = RsType.body.copy(color = c.text), cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).background(c.fill).padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (value.isEmpty()) Text(hint, style = RsType.body, color = c.textTertiary)
                inner()
            }
        },
    )
}
