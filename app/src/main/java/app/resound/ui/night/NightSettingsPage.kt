package app.resound.ui.night

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import app.resound.night.SleepWindow
import app.resound.night.SleepWindowKind
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.resound.AppGraph
import app.resound.envsound.EnvModelState
import app.resound.night.NightSettings
import app.resound.night.PersonalKind
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.topic.ChoicePill
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.RsToggle
import app.resound.ui.verify.springClick

/** 设置里“夜里”那一行的副标题 */
@Composable
fun nightSummary(): String? {
    if (!AppGraph.isNightReady) return null
    val s by AppGraph.night.settings.collectAsStateWithLifecycle()
    return if (s.enabled) "记录睡眠、鼾声、咳嗽和梦话" else "未开启"
}

/**
 * 设置 → 夜里。上面是总开关和停录冲突，中间“怎么记”“早上”“推送”三组，底部说明数据去向。
 * 时间段用胶囊点选，不弹时间选择器。
 */
@Composable
fun NightSettingsPage(bottom: Dp) {
    val c = Rs.colors
    val repo = AppGraph.night
    val s by repo.settings.collectAsStateWithLifecycle()
    val conflict by repo.quietConflict.collectAsStateWithLifecycle()
    fun save(next: NightSettings) = repo.update(next)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottom + 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RsCard {
            NightSwitch("夜里记录", "手机放床头，睡着后自动开始：记下几点睡、几点起，有没有打鼾、咳嗽、说梦话", s.enabled) { save(s.copy(enabled = it)) }
            if (s.enabled && AppGraph.isEnvSoundReady) {
                val model by AppGraph.envSound.model.collectAsStateWithLifecycle()
                when (val m = model) {
                    is EnvModelState.Downloading -> Note("正在下载识别模型 ${(m.progress * 100).toInt()}%")
                    is EnvModelState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Note("识别模型下载失败", Modifier.weight(1f), c.warning)
                        Text("重试 ›", style = RsType.subhead, color = c.accent, modifier = Modifier.springClick { AppGraph.envSound.downloadModel() })
                    }
                    EnvModelState.NotDownloaded -> Note("准备下载识别模型（约 27 MB）")
                    EnvModelState.Ready -> Unit
                }
            }
        }
        conflict?.let { rule ->
            RsCard(color = c.accentSoft) {
                Text("“${rule.name}”停录开着", style = RsType.headline, color = c.text)
                Spacer(Modifier.height(4.dp))
                Text("%d:%02d–%d:%02d 不录音，这段时间夜里什么也记不到。".format(rule.startMinute / 60, rule.startMinute % 60, rule.endMinute / 60, rule.endMinute % 60),
                    style = RsType.subhead, color = c.textSecondary)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    app.resound.ui.verify.Pill("关掉这条停录", filled = true) {
                        if (AppGraph.isQuietScheduleReady) AppGraph.quietSchedule.save(rule.copy(enabled = false))
                    }
                }
            }
        }
        if (!s.enabled) {
            Foot()
            return@Column
        }
        Label("怎么记")
        RsCard {
            NightSwitch("整晚完整识别", "在充电或电量 30% 以上时，整晚每段都认，最准。关掉后夜里先存下声音，充电时再认", s.fullNight) { save(s.copy(fullNight = it)) }
            Line()
            SleepWindowsEditor(s.windows)
            Line()
            NightSwitch("同屋有人", "鼾声、咳嗽分不出是谁的，打开后写成“卧室里的鼾声”", s.sharedRoom) { save(s.copy(sharedRoom = it)) }
            Line()
            NightSwitch("睡前电量提醒", "没充电且电量低于 30% 时提醒一次", s.lowBatteryReminder) { save(s.copy(lowBatteryReminder = it)) }
        }
        Label("早上")
        RsCard {
            NightSwitch("问我记梦", "起床后问一句“要记一下梦吗？”", s.dreamPrompt) { save(s.copy(dreamPrompt = it)) }
            Line()
            NightSwitch("鼾声中断提醒", "最近几晚鼾声中断比较多时，提示一次可以找医生看看", s.breakReminder) { save(s.copy(breakReminder = it)) }
            Line()
            NightSwitch("个人判断", "用你点的“是 / 不是”学会分辨你卧室里的声音，攒够了才启用", s.personalModel) { save(s.copy(personalModel = it)) }
            if (s.personalModel) {
                val status by AppGraph.nightInsights.personal.collectAsStateWithLifecycle(initialValue = emptyList())
                status.forEach { p ->
                    val name = if (p.kind == PersonalKind.Snore) "鼾声" else "咳嗽"
                    val line = when {
                        p.active -> "$name：已启用 · 帮你排除 ${p.excludedCount} 段" + (p.accuracy?.let { " · 准确率 ${(it * 100).toInt()}%" } ?: "")
                        p.yes < 20 || p.no < 10 -> if (p.no < 3 && p.yes >= 10) "$name：误报很少，暂时用不上" else "$name：还在学 · 是 ${p.yes}/20 · 不是 ${p.no}/10"
                        else -> "$name：学够了，但还没比默认规则准，先不启用"
                    }
                    Note(line)
                }
                // 卡 205：清空重学是唯一不可逆的操作，确认一次
                var confirm by remember { mutableStateOf(false) }
                var resetting by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                if (status.any { it.yes + it.no > 0 }) Text("清空重学", style = RsType.subhead, color = Rs.colors.accent,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp).springClick { confirm = true }.padding(vertical = 6.dp))
                if (confirm) androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirm = false },
                    containerColor = Rs.colors.card,
                    title = { Text("清空重学？", style = RsType.headline, color = Rs.colors.text) },
                    text = { Text("会忘掉已经学到的分辨方法，从头再学。你点过的“是 / 不是”还在，录音不受影响。", style = RsType.body, color = Rs.colors.textSecondary) },
                    confirmButton = { Text(if (resetting) "正在清空…" else "清空重学", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = Rs.colors.warning,
                        modifier = Modifier.springClick {
                            if (resetting) return@springClick
                            resetting = true
                            scope.launch { runCatching { AppGraph.nightInsights.resetPersonal() }; resetting = false; confirm = false }
                        }.padding(12.dp)) },
                    dismissButton = { Text("取消", style = RsType.body, color = Rs.colors.textSecondary, modifier = Modifier.springClick { confirm = false }.padding(12.dp)) },
                )
            }
        }
        Label("推送")
        RsCard {
            NightSwitch("推送里包含夜里", "一天的线索开头写“昨晚”，只推文字，录音不上传", s.includeInPush) { save(s.copy(includeInPush = it)) }
            Line()
            NightSwitch("周报", "每周一早上汇总上周作息、一个发现、一个可以试试的办法", s.weeklyReport) { save(s.copy(weeklyReport = it)) }
        }
        RsCard(Modifier.springClick { NightUi.trendOpen = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("趋势与周报", style = RsType.body, color = c.text)
                    Spacer(Modifier.height(2.dp))
                    Text("每晚作息、鼾声变化、试试看的对比、给医生的一页", style = RsType.caption, color = c.textSecondary)
                }
                Text("›", style = RsType.headline, color = c.textTertiary)
            }
        }
        Foot()
    }
    // 设置页里也能打开趋势面板
    if (NightUi.trendOpen) NightTrendSheet { NightUi.trendOpen = false }
}

@Composable
private fun Foot() {
    Text("夜里的录音只存在手机里，推送只带文字。没确认的录音 7 天后删除，统计一直保留。结果由手机自动统计，仅供参考，不是医疗检查。",
        style = RsType.caption, color = Rs.colors.textTertiary, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
}

/**
 * 卡 208：睡眠时段列表。每条一行“名字 · 开始 – 最晚记到”，右边开关；点一行原地展开改时间。
 * 夜里可选“自动”（按最近两周作息）；午睡默认关；“再加一段”给夜班、补觉用，最多 3 条。
 */
@Composable
private fun SleepWindowsEditor(windows: List<SleepWindow>) {
    val c = Rs.colors
    val auto by AppGraph.night.automaticWindow.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun save(next: List<SleepWindow>) { error = AppGraph.night.saveWindows(next) }
    Text("睡眠时段", style = RsType.body, color = c.text, modifier = Modifier.padding(top = 10.dp))
    Text("起床后会自动结束，起得早不用改", style = RsType.caption, color = c.textSecondary)
    windows.forEach { w ->
        val shown = if (w.kind == SleepWindowKind.Night && w.auto) auto else w
        val range = "${clockOf(shown.startMinute)} – ${if (shown.endMinute <= shown.startMinute) "次日 " else ""}${clockOf(shown.endMinute)}"
        Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(14.dp)).background(if (open == w.id) c.fill else androidx.compose.ui.graphics.Color.Transparent)) {
            Row(Modifier.fillMaxWidth().springClick { open = if (open == w.id) null else w.id }.padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(w.name, style = RsType.body, color = if (w.enabled) c.text else c.textTertiary)
                    Text(if (w.kind == SleepWindowKind.Night && w.auto) "自动 · $range" else range, style = RsType.caption, color = c.textSecondary)
                }
                app.resound.ui.verify.RsToggle(w.enabled) { on -> save(windows.map { if (it.id == w.id) it.copy(enabled = on) else it }) }
            }
            androidx.compose.animation.AnimatedVisibility(open == w.id) {
                Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 12.dp)) {
                    if (w.kind == SleepWindowKind.Night) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoicePill("自动", w.auto, Modifier.weight(1f)) { save(windows.map { if (it.id == w.id) it.copy(auto = true) else it }) }
                        ChoicePill("自己设", !w.auto, Modifier.weight(1f)) {
                            save(windows.map { if (it.id == w.id) it.copy(auto = false, startMinute = shown.startMinute, endMinute = shown.endMinute) else it })
                        }
                    }
                    if (w.kind == SleepWindowKind.Night && w.auto) {
                        Text("按你最近两周的作息：$range。记满 7 晚之前先用 20:00 – 次日 12:00", style = RsType.caption, color = c.textSecondary, modifier = Modifier.padding(top = 8.dp))
                    } else {
                        Text("开始", style = RsType.caption, color = c.textSecondary, modifier = Modifier.padding(top = 8.dp))
                        HalfHours(w.startMinute) { m -> save(windows.map { if (it.id == w.id) it.copy(startMinute = m) else it }) }
                        Text("最晚记到", style = RsType.caption, color = c.textSecondary, modifier = Modifier.padding(top = 8.dp))
                        HalfHours(w.endMinute) { m -> save(windows.map { if (it.id == w.id) it.copy(endMinute = m) else it }) }
                    }
                    if (w.kind == SleepWindowKind.Custom) Text("删掉这一段", style = RsType.subhead, color = c.warning,
                        modifier = Modifier.padding(top = 10.dp).springClick { open = null; save(windows.filterNot { it.id == w.id }) })
                }
            }
        }
    }
    error?.let { Text(it, style = RsType.caption, color = c.warning, modifier = Modifier.padding(top = 6.dp)) }
    if (windows.count { it.kind == SleepWindowKind.Custom } < 3) Text("＋ 再加一段（夜班、白天补觉）", style = RsType.subhead, color = c.accent,
        modifier = Modifier.padding(top = 8.dp).springClick {
            val id = "custom-" + System.currentTimeMillis()
            save(windows + SleepWindow(id, SleepWindowKind.Custom, "补觉 ${windows.count { it.kind == SleepWindowKind.Custom } + 1}", true, false, 8 * 60, 16 * 60))
            open = id
        }.padding(vertical = 6.dp))
}

private fun clockOf(m: Int) = "%d:%02d".format(m / 60, m % 60)

/** 半点选择：一排可横滑的胶囊，选中的滚到看得见 */
@Composable
private fun HalfHours(selected: Int, onPick: (Int) -> Unit) {
    val state = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (selected / 30 - 2).coerceAtLeast(0))
    androidx.compose.foundation.lazy.LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        items(48) { i ->
            val m = i * 30
            ChoicePill(clockOf(m), m == selected, Modifier.width(66.dp)) { onPick(m) }
        }
    }
}

@Composable
private fun Choices(options: List<Int>, selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { m -> ChoicePill("%d:00".format(m / 60), m == selected, Modifier.weight(1f)) { onPick(m) } }
    }
}

@Composable
internal fun NightSwitch(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Rs.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = RsType.body, color = c.text)
            Spacer(Modifier.height(2.dp))
            Text(sub, style = RsType.caption, color = c.textSecondary)
        }
        Spacer(Modifier.width(12.dp))
        RsToggle(on, onChange)
    }
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = Rs.colors.textTertiary) {
    Text(text, style = RsType.caption, color = color, modifier = modifier.padding(vertical = 4.dp))
}

@Composable
internal fun Line() = Box(Modifier.fillMaxWidth().height(0.5.dp).background(Rs.colors.separator))

@Composable
internal fun Label(text: String) {
    Text(text, style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = Rs.colors.textTertiary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp))
}
