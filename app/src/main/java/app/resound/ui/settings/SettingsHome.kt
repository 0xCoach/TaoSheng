package app.resound.ui.settings

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.resound.AppGraph
import app.resound.capture.CaptureController
import app.resound.capture.CaptureState
import app.resound.digest.DigestSettings
import app.resound.health.HealthFix
import app.resound.health.HealthIssue
import app.resound.health.HealthReport
import app.resound.health.HealthSeverity
import app.resound.transcribe.TranscribeSettings
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.theme.ThemeMode
import app.resound.ui.verify.springClick
import app.resound.ui.voiceprint.LocalVoiceprint

/*
 * 设置首页（11-12 改版）：
 * 顶部“体检”状态卡（一切正常时收成一行，有问题时列出并给“去处理”）+ 搜索；
 * 每行前加彩色小图标，图标按组用相近色系（记录偏暖、整理偏紫蓝、数据绿、通用灰）；
 * 正常不放点，只有要处理的才亮黄点 / 红点；最底下“高级与诊断”收纳不常用的。
 */

/** 各组图标色：同组相近色系，整体沉稳；组与组之间靠色相区分 */
private object Tints {
    // 记录：暖色系
    val capture = Color(0xFFF07B2E); val devices = Color(0xFFF2994A); val transcript = Color(0xFFE0A030)
    val voiceprint = Color(0xFFE06A50); val env = Color(0xFFD4A017); val location = Color(0xFFC9643A)
    // 整理：紫蓝系
    val model = Color(0xFF7C5CC4); val skills = Color(0xFF5B5BD6); val push = Color(0xFF3B82C4)
    // 数据：绿
    val backup = Color(0xFF34A853)
    // 通用：灰系
    val look = Color(0xFF8E8E93); val update = Color(0xFF7A8794); val about = Color(0xFF9A8F86); val advanced = Color(0xFF636366)
}

@Composable
internal fun SettingsList(
    capture: CaptureController,
    transcribeSettings: TranscribeSettings,
    digestSettings: DigestSettings,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    bottomPadding: Dp,
    onOpen: (SettingsPage) -> Unit,
) {
    val c = Rs.colors
    val status by capture.status.collectAsStateWithLifecycle()
    val asrKey by transcribeSettings.ready.collectAsStateWithLifecycle()
    val llmKey by digestSettings.hasKey.collectAsStateWithLifecycle()
    val preset by digestSettings.presetId.collectAsStateWithLifecycle()
    val vp = LocalVoiceprint.current?.state?.collectAsStateWithLifecycle()?.value
    var query by rememberSaveable { mutableStateOf("") }
    val report = if (AppGraph.isHealthReady) AppGraph.health.report.collectAsStateWithLifecycle().value else null
    LaunchedEffect(Unit) { if (AppGraph.isHealthReady) AppGraph.health.refresh() }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    app.resound.ui.ScrollToTopOnReselect(listState)
    LazyColumn(
        Modifier.fillMaxSize().background(c.background).windowInsetsPadding(WindowInsets.safeDrawing),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomPadding + 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Column {
                Text("设置", style = RsType.largeTitle, color = c.text, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
                if (AppGraph.isSettingsSearchReady) {
                    Spacer(Modifier.height(4.dp))
                    SearchField(query) { query = it }
                }
            }
        }
        // ---------- 搜索结果 ----------
        if (query.isNotBlank() && AppGraph.isSettingsSearchReady) {
            val hits = AppGraph.settingsSearch.search(query)
            item {
                if (hits.isEmpty()) Text("没找到“${query.trim()}”，换个说法试试", style = RsType.subhead, color = c.textTertiary, modifier = Modifier.padding(horizontal = 4.dp))
                else Group {
                    hits.forEachIndexed { i, e ->
                        if (i > 0) Divider(inset = 18.dp)
                        Row(Modifier.fillMaxWidth().springClick {
                            // “外观”就在设置首页：清空搜索回到首页即可
                            if (e.anchor == "appearance") { query = ""; return@springClick }
                            val page = runCatching { SettingsPage.valueOf(e.page) }.getOrNull()
                            if (page != null) { SettingsHighlight.anchor = e.anchor; onOpen(page) }
                        }.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(e.title, style = RsType.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(e.pageTitle, style = RsType.caption, color = c.textTertiary, maxLines = 1)
                            }
                            Chevron()
                        }
                    }
                }
            }
            return@LazyColumn
        }
        // ---------- 体检 ----------
        report?.let { r -> item { HealthCard(r, capture, onOpen) } }

        // ---------- 记录 ----------
        item {
            GroupSection("记录") {
                val inputLabel = if (AppGraph.isInputsReady)
                    AppGraph.inputs.status.collectAsStateWithLifecycle().value.using.let { if (it.kind == app.resound.audio.InputKind.Phone) null else it.name } else null
                val captureLine = when (status.state) {
                    CaptureState.Listening, CaptureState.Recording -> "正在聆听" + (inputLabel?.let { " · $it" } ?: "")
                    CaptureState.Paused -> (status.quietName?.let { "${it}中" } ?: "已暂停") +
                        (app.resound.ui.verify.resumeAt(status.pausedUntil)?.let { " · ${app.resound.ui.verify.formatUpcoming(it)} 恢复" } ?: "")
                    CaptureState.MicBusy -> "麦克风被占用"
                    CaptureState.Stopped -> "未开启"
                    CaptureState.NoPermission -> "缺少权限"
                }
                SRow(SettingsGlyph.Mic, Tints.capture, "录音与后台", captureLine,
                    alert = if (status.state == CaptureState.Stopped || status.state == CaptureState.NoPermission) c.critical else null) { onOpen(SettingsPage.Capture) }
                if (AppGraph.isDevicesReady) {
                    Divider()
                    SRow(SettingsGlyph.Device, Tints.devices, "外接设备", app.resound.ui.verify.devicesSummary()) { onOpen(SettingsPage.Devices) }
                }
                Divider()
                val asr = transcribeSettings as? app.resound.transcribe.AndroidTranscribeSettings
                val asrSource = asr?.source?.collectAsStateWithLifecycle()?.value?.title ?: "豆包"
                val asrRealtime = asr?.realtime?.collectAsStateWithLifecycle()?.value == true
                val asrInterval = transcribeSettings.intervalMinutes.collectAsStateWithLifecycle().value
                val queue = if (AppGraph.isTranscribeReady) AppGraph.transcripts.queue.collectAsStateWithLifecycle().value else null
                val failed = queue?.failed ?: 0
                val asrLine = when {
                    !asrKey -> "转写未就绪"
                    failed > 0 -> "$failed 段没转成"
                    queue?.running == true -> "正在转写"
                    (queue?.pending ?: 0) > 0 -> "$asrSource · ${queue!!.pending} 段待转"
                    else -> "$asrSource · ${if (asrRealtime) "实时" else "每 $asrInterval 分钟"}"
                }
                SRow(SettingsGlyph.Lines, Tints.transcript, "原话与转写", asrLine, alert = if (!asrKey || failed > 0) c.warning else null) { onOpen(SettingsPage.Transcript) }
                if (vp != null) {
                    Divider()
                    SRow(SettingsGlyph.People, Tints.voiceprint, "声纹与人物",
                        if (vp.ready) "已能认出你" else "还差 ${maxOf(0, 3 - vp.samples)} 段样本", alert = if (vp.ready) null else c.warning) { onOpen(SettingsPage.Voiceprint) }
                }
                app.resound.ui.envsound.envSoundSummary()?.let { v ->
                    Divider()
                    SRow(SettingsGlyph.Wave, Tints.env, "环境音识别", v) { onOpen(SettingsPage.EnvSound) }
                }
                if (AppGraph.isLocationReady) {
                    val locOn = AppGraph.location.settings.enabled.collectAsStateWithLifecycle().value
                    Divider()
                    SRow(SettingsGlyph.Pin, Tints.location, "足迹", if (locOn) "记录全天位置" else "未开启") { onOpen(SettingsPage.Location) }
                }
            }
        }
        // ---------- 整理 ----------
        item {
            GroupSection("整理") {
                val digestInterval = (digestSettings as? app.resound.digest.AndroidDigestSettings)?.intervalMinutes?.collectAsStateWithLifecycle()?.value
                val providerName = if (AppGraph.isLlmProvidersReady) AppGraph.llmProviders.let { p ->
                    val saved by p.saved.collectAsStateWithLifecycle(); val id by p.activeId.collectAsStateWithLifecycle(); saved.firstOrNull { it.id == id }?.name
                } else null
                val model = providerName ?: digestSettings.presets.firstOrNull { it.id == preset }?.name ?: ""
                val timing = if (AppGraph.isDigestTimingReady) AppGraph.digestTiming else null
                val timed = timing?.mode?.collectAsStateWithLifecycle()?.value == app.resound.digest.DigestMode.TimePoints
                val points = timing?.timePoints?.collectAsStateWithLifecycle()?.value.orEmpty()
                val when_ = if (timed) " · 每天 ${points.size} 个时间点" else digestInterval?.let { " · 每 $it 分钟" } ?: ""
                val bal = if (AppGraph.isLlmBalanceReady) AppGraph.llmBalance.state.collectAsStateWithLifecycle().value.balance else null
                val line = if (AppGraph.isLlmBalanceReady) AppGraph.llmBalance.threshold.collectAsStateWithLifecycle().value else 0.0
                val low = bal != null && line > 0 && (bal.total < line || !bal.available)
                val modelLine = when {
                    !llmKey -> "未设置"
                    bal != null && !bal.available -> "$model · 余额已用完"
                    bal != null -> "$model · ¥%.2f".format(bal.total) + if (low) " 余额不多" else ""
                    else -> model + when_
                }
                SRow(SettingsGlyph.Sparkle, Tints.model, SettingsPage.Model.title, modelLine,
                    alert = when { !llmKey || (bal != null && !bal.available) -> c.critical; low -> c.warning; else -> null }) { onOpen(SettingsPage.Model) }
                Divider()
                val skillList = app.resound.ui.skills.skillRepo().skills.collectAsStateWithLifecycle().value
                SRow(SettingsGlyph.Grid, Tints.skills, "整理技能", "开了 ${skillList.count { it.enabled && it.origin != app.resound.skills.SkillOrigin.Core }} 个") { onOpen(SettingsPage.Skills) }
                if (AppGraph.isPushReady) {
                    val channels = AppGraph.push.channels.collectAsStateWithLifecycle(initialValue = emptyList()).value
                    Divider()
                    SRow(SettingsGlyph.Send, Tints.push, "推送通道", channels.count { it.enabled }.let { if (it == 0) "只发手机通知" else "$it 个通道" }) { onOpen(SettingsPage.Push) }
                }
            }
        }
        // ---------- 数据（备份没准备好时整组不显示，不写“开发中”） ----------
        if (AppGraph.isBackupReady) item {
            GroupSection("数据") {
                val b = AppGraph.backup.state.collectAsStateWithLifecycle().value
                val lb = AppGraph.backup.local.collectAsStateWithLifecycle().value
                val parts = buildList {
                    if (b.account != null) add(Triple(b.cloud.title(), b.lastError == null && b.lastBackupAt != null, b.lastBackupAt))
                    if (lb.folder != null) add(Triple("本机", lb.lastError == null && lb.lastAt != null, lb.lastAt))
                }
                val failed = parts.any { !it.second }
                val value = if (parts.isEmpty()) "未开启" else parts.joinToString("  ") { (name, ok, at) -> name + " " + if (ok) backupClock(at!!, short = true) else "失败" }
                SRow(SettingsGlyph.Cloud, Tints.backup, "数据备份", value, alert = if (failed) c.critical else null) { onOpen(SettingsPage.Backup) }
            }
        }
        // ---------- 通用 ----------
        item {
            GroupSection("通用") {
                Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(start = 14.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingsIcon(SettingsGlyph.Sun, Tints.look)
                    Spacer(Modifier.width(12.dp))
                    Text("外观", style = RsType.body, color = c.text, modifier = Modifier.weight(1f))
                    Segmented(listOf(ThemeMode.System to "自动", ThemeMode.Light to "浅色", ThemeMode.Dark to "深色"), themeMode, onThemeMode)
                }
                if (AppGraph.isUpdaterReady) {
                    val updater = AppGraph.updater
                    val (summary, fresh) = updateSummary(updater.currentVersion, updater.state.collectAsStateWithLifecycle().value)
                    Divider()
                    SRow(SettingsGlyph.Up, Tints.update, "版本更新", summary, alert = if (fresh) c.accent else null) { onOpen(SettingsPage.Update) }
                }
                Divider()
                SRow(SettingsGlyph.Info, Tints.about, "关于涛声", "") { onOpen(SettingsPage.About) }
            }
        }
        item {
            Group {
                SRow(SettingsGlyph.Tool, Tints.advanced, "高级与诊断", "整理要求 · 运行记录") { onOpen(SettingsPage.Advanced) }
            }
        }
    }
}

/** 搜索结果点进去后，二级页可据此滚动到并闪一下对应选项（按页逐步接入） */
object SettingsHighlight {
    var anchor by mutableStateOf<String?>(null)
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    val c = Rs.colors
    val focus = LocalFocusManager.current
    BasicTextField(value, onChange, singleLine = true, textStyle = RsType.body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(Modifier.clip(RoundedCornerShape(12.dp)).background(if (c.isDark) c.card else Color(0xFFE9E6E1)).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(SettingsGlyph.Search, c.textTertiary, 16.dp)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text("搜索设置，如「耗电」「不录」", style = RsType.body, color = c.textTertiary, maxLines = 1)
                    inner()
                }
                if (value.isNotEmpty()) Text("取消", style = RsType.subhead, color = c.accent,
                    modifier = Modifier.padding(start = 8.dp).springClick { onChange(""); focus.clearFocus() })
            }
        })
}

/** 体检卡：一切正常时一行；有问题时列出（最多 3 条），每条一个“去处理” */
@Composable
private fun HealthCard(r: HealthReport, capture: CaptureController, onOpen: (SettingsPage) -> Unit) {
    val c = Rs.colors
    val context = LocalContext.current
    fun fix(i: HealthIssue) {
        when (i.fix) {
            HealthFix.DisableBatteryOptimization -> runCatching {
                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { onOpen(SettingsPage.Capture) }
            HealthFix.GrantPermissions -> runCatching {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { onOpen(SettingsPage.Capture) }
            HealthFix.StartListening -> capture.start()
            HealthFix.OpenCapture -> onOpen(SettingsPage.Capture)
            HealthFix.OpenTranscript -> onOpen(SettingsPage.Transcript)
            HealthFix.OpenModel -> onOpen(SettingsPage.Model)
            HealthFix.OpenBackup -> onOpen(SettingsPage.Backup)
            HealthFix.TopUp -> {
                val deepseek = AppGraph.isLlmProvidersReady && AppGraph.llmProviders.let { p -> p.saved.value.firstOrNull { it.id == p.activeId.value }?.templateId == "deepseek" }
                if (deepseek) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.deepseek.com/top_up")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                else onOpen(SettingsPage.Model)
            }
        }
    }
    val shape = RoundedCornerShape(20.dp)
    AnimatedContent(r.issues.isEmpty(), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "health") { ok ->
        Column(Modifier.fillMaxWidth().clip(shape).background(c.card)
            .then(if (c.isDark) Modifier.background(Brush.radialGradient(listOf((if (ok) c.good else c.critical).copy(alpha = 0.14f), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset.Zero, radius = 700f)) else Modifier)
            .padding(horizontal = 16.dp, vertical = if (ok) 12.dp else 14.dp)) {
            if (ok) {
                // 一切正常：收成一行，位置让给下面的列表
                val f = r.facts
                val facts = listOfNotNull(
                    f.stableDays?.let { "连续稳定 $it 天" },
                    f.interruptionsToday?.let { "今天中断 $it 次" },
                    f.nextBackupAt?.let { "下次备份 " + nextBackupText(it) },
                ).joinToString(" · ")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Badge(SettingsGlyph.Check, c.good)
                    Spacer(Modifier.width(10.dp))
                    Text("一切正常", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                    if (facts.isNotEmpty()) Text("  $facts", style = RsType.caption, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val critical = r.issues.any { it.severity == HealthSeverity.Critical }
                    Badge(SettingsGlyph.Alert, if (critical) c.critical else c.warning)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("${r.issues.size} 件事要处理", style = RsType.headline, color = c.text)
                        Text("处理完就恢复正常", style = RsType.caption, color = c.textSecondary)
                    }
                }
                Spacer(Modifier.height(4.dp))
                r.issues.forEachIndexed { i, issue ->
                    if (i > 0) Box(Modifier.padding(start = 18.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
                    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.padding(start = 4.dp, end = 10.dp).size(7.dp).clip(CircleShape)
                            .background(if (issue.severity == HealthSeverity.Critical) c.critical else c.warning))
                        Column(Modifier.weight(1f)) {
                            Text(issue.title, style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.text)
                            issue.detail?.let { Text(it, style = RsType.caption, color = c.textSecondary) }
                        }
                        val primary = i == 0
                        Text(issue.fixLabel, style = RsType.caption.copy(fontWeight = FontWeight.SemiBold),
                            color = if (primary) Color.White else c.accent,
                            modifier = Modifier.clip(CircleShape).background(if (primary) c.accent else c.accentSoft)
                                .springClick { fix(issue) }.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
        }
    }
}

private fun nextBackupText(at: Long): String {
    val t = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
    val hm = "%d:%02d".format(t.hour, t.minute)
    // 自动备份在凌晨（约 3:10）：写“凌晨 3:10”，比“明天 3:10”更自然
    if (t.hour < 6) return "凌晨 $hm"
    return if (t.toLocalDate() == java.time.LocalDate.now()) hm else "明天 $hm".takeIf { t.toLocalDate() == java.time.LocalDate.now().plusDays(1) } ?: "${t.monthValue}/${t.dayOfMonth} $hm"
}

@Composable
private fun Badge(glyph: SettingsGlyph, color: Color) {
    Box(Modifier.size(28.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        GlyphIcon(glyph, color, 15.dp, strokeWidth = 2.6f)
    }
}

@Composable
private fun GroupSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, style = RsType.section, color = Rs.colors.textTertiary, modifier = Modifier.padding(start = 4.dp, bottom = 7.dp))
        Group(content)
    }
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    app.resound.ui.verify.RsCard(padding = PaddingValues(vertical = 2.dp)) { content() }
}

/** 分隔线从文字处开始（让开图标），和苹果、小米设置一致 */
@Composable
private fun Divider(inset: Dp = 54.dp) {
    Box(Modifier.padding(start = inset).fillMaxWidth().height(0.5.dp).background(Rs.colors.separator))
}

@Composable
private fun Chevron() = Text("›", style = RsType.headline.copy(fontWeight = FontWeight.Normal), color = Rs.colors.textTertiary)

/** 一行：彩色图标 + 标题 + 右侧状态 + ›；[alert] 不为空时右侧前面亮一个点、文字也用该色（只给要处理的） */
@Composable
private fun SRow(glyph: SettingsGlyph, tint: Color, title: String, value: String, alert: Color? = null, onClick: () -> Unit) {
    val c = Rs.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).springClick(onClick).padding(start = 14.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        SettingsIcon(glyph, tint)
        Spacer(Modifier.width(12.dp))
        Text(title, style = RsType.body, color = c.text, maxLines = 1, softWrap = false)
        Row(Modifier.weight(1f).padding(start = 12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (alert != null && value.isNotEmpty()) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(alert))
                Spacer(Modifier.width(6.dp))
            }
            Text(value, style = RsType.subhead, color = alert ?: c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        Spacer(Modifier.width(8.dp))
        Chevron()
    }
}

/** 分段切换（外观：自动 / 浅色 / 深色） */
@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val c = Rs.colors
    Row(Modifier.clip(RoundedCornerShape(9.dp)).background(c.track).padding(2.dp)) {
        options.forEach { (v, label) ->
            val on = v == selected
            val bg by animateColorAsState(if (on) c.trackThumb else c.trackThumb.copy(alpha = 0f), label = "seg")
            Text(label, style = RsType.subhead.copy(fontWeight = if (on) FontWeight.Medium else FontWeight.Normal),
                color = if (on) c.text else c.textSecondary,
                modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(bg).springClick { onSelect(v) }.padding(horizontal = 11.dp, vertical = 5.dp))
        }
    }
}
