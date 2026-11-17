package app.resound.ui.night

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            Text("夜里从几点开始", style = RsType.body, color = c.text, modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.height(8.dp))
            Choices(listOf(20, 21, 22, 23).map { it * 60 }, s.windowStartMinute) { save(s.copy(windowStartMinute = it)) }
            Text("到第二天几点", style = RsType.body, color = c.text, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(8.dp))
            Choices(listOf(10, 11, 12, 13).map { it * 60 }, s.windowEndMinute) { save(s.copy(windowEndMinute = it)) }
            Spacer(Modifier.height(10.dp))
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
                        p.active -> "$name：已启用 · 学了 ${p.yes + p.no} 段" + (p.accuracy?.let { " · 准确率 ${(it * 100).toInt()}%" } ?: "")
                        p.yes < 20 || p.no < 10 -> "$name：还在学 · 是 ${p.yes}/20 · 不是 ${p.no}/10"
                        else -> "$name：学够了，但还没比默认规则准，先不启用"
                    }
                    Note(line)
                }
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
