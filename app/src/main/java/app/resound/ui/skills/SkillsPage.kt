package app.resound.ui.skills

import app.resound.appDisplayName
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.resound.digest.LlmPreset
import app.resound.skills.FieldType
import app.resound.skills.Skill
import app.resound.skills.SkillOrigin
import app.resound.skills.SkillRepository
import app.resound.skills.SkillRunResult
import app.resound.skills.SkillStatus
import app.resound.skills.SkillTrigger
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.ActionButton
import app.resound.ui.verify.ActionKind
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.springClick
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 设置 → 技能：像百宝箱一样一排技能，右边开关；点进去看详情、改时间、改要求、试跑。
 * 两个核心功能（时间线整理、每日回顾）列在最前，点进去仍是原来的设置页（由 [corePage] 提供）。
 */
@Composable
fun SkillsPage(
    repo: SkillRepository,
    presets: List<LlmPreset>,
    bottomPadding: Dp,
    corePage: @Composable (String, Dp) -> Unit,
) {
    val backendSkills by repo.skills.collectAsState()
    // “问问”不出卡片，但它的提示词也归到技能页的“基础”里统一改
    val skills = remember(backendSkills) {
        val i = backendSkills.indexOfLast { it.origin == SkillOrigin.Core } + 1
        (backendSkills.take(i) + ASK_CORE + backendSkills.drop(i)).map { s -> CORE_SUMMARY[s.id]?.let { s.copy(summary = it) } ?: s }
    }
    val status by repo.status.collectAsState()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val open = skills.firstOrNull { it.id == openId }
    // 正在编辑的技能（新建时是空白模板）
    var editing by remember { mutableStateOf<Pair<Skill, Boolean>?>(null) }
    var copiedNote by remember { mutableStateOf<String?>(null) }
    /** 改官方技能：先复制成我的版本（原版保留），再打开编辑 */
    fun edit(s: Skill) {
        if (s.origin == SkillOrigin.Mine) { editing = s to false; return }
        repo.copyAsMine(s.id).onSuccess { id ->
            openId = id
            copiedNote = "已复制成“我的版本”，官方原版保留不变"
            repo.skills.value.firstOrNull { it.id == id }?.let { editing = it to false }
        }
    }
    val setInner = app.resound.ui.settings.LocalSettingsInner.current
    BackHandler(open != null) { openId = null }
    // 打开某个技能时，顶栏标题换成技能名、返回键回到技能列表
    androidx.compose.runtime.DisposableEffect(open?.id, open?.name) {
        setInner(open?.let { app.resound.ui.settings.InnerPage(it.name, "整理技能") { openId = null } })
        onDispose { setInner(null) }
    }

    AnimatedContent(
        open,
        transitionSpec = {
            if (targetState != null) (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 6 } + fadeOut())
            else (slideInHorizontally { -it / 6 } + fadeIn()) togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
        },
        label = "skillPage",
    ) { current ->
        when {
            current == null -> SkillList(skills, status, repo, bottomPadding, onNew = { editing = blankSkill() to true }) { openId = it }
            current.origin == SkillOrigin.Core -> corePage(current.id, bottomPadding)
            else -> SkillDetail(current, status[current.id], repo, presets, bottomPadding, copiedNote, onEdit = { edit(current) }) { openId = it; copiedNote = null }
        }
    }
    editing?.let { (s, isNew) ->
        SkillEditor(s, if (isNew) BLANK_PROMPT else repo.prompt(s.id), repo, isNew,
            onSaved = { id -> editing = null; openId = id }) { editing = null }
    }
}

private val CORE_SUMMARY = mapOf(
    "timeline" to "把说过的话整理成一件件事，并提取待办、灵感等",
    "review" to "每晚把一天整理成一页回顾",
)

private val ASK_CORE = Skill("ask", "问问", "打字问你的记忆，回答附原话出处", "chat", SkillOrigin.Core, 1, true,
    SkillTrigger.Manual, app.resound.skills.SkillScope(), emptyList(), 1, false, null, false)

@Composable
private fun SkillList(skills: List<Skill>, status: Map<String, SkillStatus>, repo: SkillRepository, bottomPadding: Dp, onNew: () -> Unit, onOpen: (String) -> Unit) {
    val c = Rs.colors
    val core = skills.filter { it.origin == SkillOrigin.Core }
    val builtin = skills.filter { it.origin == SkillOrigin.Builtin }
    val mine = skills.filter { it.origin == SkillOrigin.Mine }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("${appDisplayName}会做的这些事，由你决定开哪些。每张卡都会放进“记忆”的当天里。", style = RsType.subhead, color = c.textSecondary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
        }
        if (core.isNotEmpty()) {
            item { GroupTitle("基础") }
            item {
                RsCard(padding = PaddingValues(vertical = 4.dp)) {
                    core.forEachIndexed { i, s ->
                        Row(Modifier.fillMaxWidth().springClick { onOpen(s.id) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            SkillIcon(s.icon, 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.name, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                                Text(s.summary, style = RsType.caption, color = c.textSecondary, maxLines = 2)
                            }
                            Text("一直开着", style = RsType.caption, color = c.textTertiary)
                            Spacer(Modifier.width(6.dp))
                            Text("›", style = RsType.title, color = c.textTertiary)
                        }
                        if (i != core.lastIndex) Box(Modifier.padding(start = 68.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
                    }
                }
            }
        }
        if (builtin.isNotEmpty()) {
            item { GroupTitle("技能") }
            items(builtin, key = { it.id }) { s -> SkillTile(s, status[s.id], { repo.setEnabled(s.id, it) }) { onOpen(s.id) } }
        }
        if (mine.isNotEmpty()) {
            item { GroupTitle("我的版本") }
            items(mine, key = { it.id }) { s -> SkillTile(s, status[s.id], { repo.setEnabled(s.id, it) }) { onOpen(s.id) } }
        }
        item {
            ActionButton("＋ 新建技能", ActionKind.Secondary, Modifier.padding(top = 6.dp), onClick = onNew)
            Text("也可以点进任一技能，“改卡片和设置”会复制一份我的版本再改，官方原版不动。", style = RsType.caption, color = c.textTertiary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(text, style = RsType.section, color = Rs.colors.textSecondary, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
}

/** 一个技能：图标、名字、一句话、运行时间与上次结果，右边开关 */
@Composable
private fun SkillTile(s: Skill, st: SkillStatus?, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    val c = Rs.colors
    RsCard(Modifier.springClick(onClick), padding = PaddingValues(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SkillIcon(s.icon, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name, style = RsType.headline, color = c.text)
                Text(s.summary, style = RsType.caption, color = c.textSecondary, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(triggerText(s.trigger), style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = if (s.enabled) skillInk(s.icon) else c.textTertiary)
                    statusLine(st)?.let { (text, bad) -> Text(" · $text", style = RsType.caption, color = if (bad) c.critical else c.textTertiary, maxLines = 1) }
                }
            }
            Spacer(Modifier.width(10.dp))
            Toggle(s.enabled, onToggle)
        }
    }
}

internal fun triggerText(t: SkillTrigger): String = when (t) {
    is SkillTrigger.Daily -> "${weekdayText(t.weekdays)} ${hm(t.minutes)}"
    is SkillTrigger.AfterDigest -> "说完自动识别"
    SkillTrigger.Manual -> "手动生成"
}

private val WEEK = listOf("一", "二", "三", "四", "五", "六", "日")

private fun weekdayText(days: Set<Int>): String = when {
    days.isEmpty() || days.size == 7 -> "每天"
    days == setOf(1, 2, 3, 4, 5) -> "工作日"
    days == setOf(6, 7) -> "周末"
    else -> "每周" + days.sorted().joinToString("、") { WEEK[it - 1] }
}

private fun hm(minutes: Int) = "%d:%02d".format(minutes / 60, minutes % 60)

private fun statusLine(st: SkillStatus?): Pair<String, Boolean>? = when {
    st == null -> null
    st.running -> "正在运行…" to false
    st.lastFailed && st.lastResult != null -> st.lastResult to true
    st.lastRunAt != null && st.lastResult != null -> "${shortTime(st.lastRunAt)} ${st.lastResult}" to false
    else -> null
}

private fun shortTime(at: Long): String {
    return app.resound.ui.verify.formatStamp(at)
}

@Composable
internal fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Rs.colors
    val track by animateColorAsState(if (on) c.accent else c.separator, label = "track")
    val x by animateDpAsState(if (on) 20.dp else 0.dp, spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium), label = "knob")
    Box(Modifier.size(width = 50.dp, height = 30.dp).clip(CircleShape).background(track).springClick { onChange(!on) }.padding(2.dp)) {
        Box(Modifier.offset(x = x).size(26.dp).clip(CircleShape).background(Color.White))
    }
}

/** 分段胶囊：一排选项，选中的白底 */
@Composable
internal fun Segments(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = Rs.colors
    Row(Modifier.clip(CircleShape).background(c.separator.copy(alpha = if (c.isDark) 1f else 0.7f)).padding(3.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val bg by animateColorAsState(if (on) c.card else c.card.copy(alpha = 0f), label = "seg")
            Box(Modifier.clip(CircleShape).background(bg).springClick { onSelect(i) }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                Text(label, style = RsType.subhead, color = if (on) c.text else c.textSecondary)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SkillDetail(s: Skill, st: SkillStatus?, repo: SkillRepository, presets: List<LlmPreset>, bottomPadding: Dp,
                        copiedNote: String?, onEdit: () -> Unit, onOpen: (String?) -> Unit) {
    val c = Rs.colors
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding + 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 头部：图标、名字、说明、开关
        RsCard(padding = PaddingValues(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkillIcon(s.icon, 52.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = RsType.title, color = c.text)
                    Text(if (s.origin == SkillOrigin.Mine) "我的版本" else "官方技能", style = RsType.caption, color = c.textTertiary)
                }
                Toggle(s.enabled) { repo.setEnabled(s.id, it) }
            }
            Spacer(Modifier.height(10.dp))
            Text(s.summary, style = RsType.subhead, color = c.textSecondary)
            if (s.upstreamUpdated) {
                Spacer(Modifier.height(8.dp))
                Text("官方版本有更新，可以重新复制一份对照着改", style = RsType.caption, color = c.accent)
            }
        }

        copiedNote?.let { Text(it, style = RsType.subhead, color = c.accent, modifier = Modifier.padding(horizontal = 4.dp)) }

        // 什么时候运行
        RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
            Text("什么时候运行", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
            Spacer(Modifier.height(10.dp))
            when (val t = s.trigger) {
                is SkillTrigger.Daily -> {
                    // 点时间弹出滚轮（24 小时制），和其他时间设置一样
                    var picking by remember { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(hm(t.minutes), style = RsType.number, color = c.accent,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(c.accentSoft).springClick { picking = true }
                                .padding(horizontal = 16.dp, vertical = 6.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("点时间修改", style = RsType.caption, color = c.textTertiary)
                    }
                    if (picking) app.resound.ui.verify.WheelTimeSheet("什么时候运行", t.minutes, onDismiss = { picking = false }) {
                        repo.setSchedule(s.id, it, t.weekdays); picking = false
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..7).forEach { d ->
                            val on = t.weekdays.isEmpty() || d in t.weekdays
                            Box(Modifier.size(34.dp).clip(CircleShape).background(if (on) c.accent else c.background)
                                .springClick {
                                    val cur = t.weekdays.ifEmpty { (1..7).toSet() }
                                    val next = if (d in cur) cur - d else cur + d
                                    if (next.isNotEmpty()) repo.setSchedule(s.id, t.minutes, if (next.size == 7) emptySet() else next)
                                },
                                contentAlignment = Alignment.Center) {
                                Text(WEEK[d - 1], style = RsType.subhead, color = if (on) Color.White else c.textSecondary)
                            }
                        }
                    }
                }
                is SkillTrigger.AfterDigest -> Text("每轮整理之后自动看新内容，发现了就出卡；最多每 ${t.minGapMinutes} 分钟一次。也可以在下面手动生成。",
                    style = RsType.subhead, color = c.textSecondary)
                SkillTrigger.Manual -> Text("只在你手动生成时运行。", style = RsType.subhead, color = c.textSecondary)
            }
            // 定时的和整理后自动出的都能推送；手动生成的本来就在眼前，不推
            if (s.trigger !is SkillTrigger.Manual) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("生成后推送", style = RsType.body, color = c.text)
                        Text(if (s.trigger is SkillTrigger.AfterDigest) "每出一张新卡推送一次，带上卡片内容" else "推送卡片内容，点开看整张卡",
                            style = RsType.caption, color = c.textSecondary)
                    }
                    Toggle(s.push) { repo.setPush(s.id, it) }
                }
            }
        }

        // 用哪个模型
        if (presets.isNotEmpty()) {
            RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
                Text("用哪个模型", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf<LlmPreset?>(null) + presets).forEach { p ->
                        val on = s.presetId == p?.id
                        Text(p?.name ?: "跟随整理模型", style = RsType.subhead, color = if (on) Color.White else c.text,
                            modifier = Modifier.clip(CircleShape).background(if (on) c.accent else c.background).springClick { repo.setPreset(s.id, p?.id) }
                                .padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                }
            }
        }

        // 卡片上有什么
        RsCard(Modifier.springClick(onEdit), padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("卡片上有什么", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text, modifier = Modifier.weight(1f))
                Text("改 ›", style = RsType.subhead, color = c.accent)
            }
            Spacer(Modifier.height(8.dp))
            s.fields.forEach { f ->
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.label, style = RsType.body, color = c.text, modifier = Modifier.weight(1f))
                    if (f.required) Text("必有  ", style = RsType.caption, color = c.accent)
                    Text(typeName(f.type), style = RsType.caption, color = c.textTertiary)
                }
            }
            if (s.origin == SkillOrigin.Builtin) {
                Spacer(Modifier.height(6.dp))
                Text("改内容项会先复制成我的版本，官方原版不动。", style = RsType.caption, color = c.textTertiary)
            }
        }

        // 要求
        RsCard(Modifier.springClick { editing = true }, padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("给大模型的要求（提示词）", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text, modifier = Modifier.weight(1f))
                if (s.promptCustomized) Text("已修改", style = RsType.caption, color = c.accent,
                    modifier = Modifier.clip(CircleShape).background(c.accentSoft).padding(horizontal = 8.dp, vertical = 2.dp))
                Spacer(Modifier.width(6.dp))
                Text("改 ›", style = RsType.subhead, color = c.accent)
            }
            Spacer(Modifier.height(6.dp))
            Text(repo.prompt(s.id), style = RsType.subhead, color = c.textSecondary, maxLines = 3)
        }

        TryCard(s, repo, prompt = null)

        // 运行情况
        st?.let {
            RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
                Text("运行情况", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                Spacer(Modifier.height(8.dp))
                InfoRow("上次", it.lastRunAt?.let { t -> shortTime(t) + (it.lastResult?.let { r -> " · $r" } ?: "") } ?: "还没运行过")
                it.nextRunAt?.let { t -> InfoRow("下次", shortTime(t)) }
                InfoRow("近 30 天用量", if (it.tokens30d < 10_000) "${it.tokens30d} 字元" else "%.1f 万字元".format(it.tokens30d / 10_000.0))
            }
        }

        note?.let { Text(it, style = RsType.subhead, color = c.textSecondary, modifier = Modifier.padding(horizontal = 4.dp)) }
        when (s.origin) {
            SkillOrigin.Builtin -> ActionButton("改卡片和设置（复制成我的版本）", ActionKind.Secondary, onClick = onEdit)
            SkillOrigin.Mine -> ActionButton("改卡片和设置", ActionKind.Secondary, onClick = onEdit)
            SkillOrigin.Core -> Unit
        }
        if (s.origin == SkillOrigin.Mine) {
            ActionButton(if (confirmDelete) "确定删除？已出的卡会保留" else "删除这个技能", ActionKind.Danger) {
                if (!confirmDelete) confirmDelete = true else { repo.deleteMine(s.id); onOpen(null) }
            }
        }
    }
    if (editing) PromptSheet(s, repo) { editing = false }
}

@Composable
private fun Stepper(text: String, onClick: () -> Unit) {
    val c = Rs.colors
    Box(Modifier.size(38.dp).clip(CircleShape).background(c.background).springClick(onClick), contentAlignment = Alignment.Center) {
        Text(text, style = RsType.title, color = c.textSecondary)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val c = Rs.colors
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(label, style = RsType.subhead, color = c.textSecondary, modifier = Modifier.weight(1f))
        Text(value, style = RsType.subhead, color = c.text)
    }
}

private fun typeName(t: FieldType) = when (t) {
    FieldType.Text -> "一句话"
    FieldType.Paragraph -> "一段话"
    FieldType.Bullets -> "要点"
    FieldType.Quotes -> "原话"
    FieldType.Number -> "数字"
    FieldType.Tags -> "标签"
    FieldType.Checklist -> "清单"
    FieldType.Rating -> "评分"
}

/** 试一下：选今天 / 昨天 / 前天，试跑（只预览）或正式生成 */
@Composable
private fun TryCard(s: Skill, repo: SkillRepository, prompt: String?) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    var day by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SkillRunResult?>(null) }
    var saved by remember { mutableStateOf(false) }
    val date = LocalDate.now().minusDays(day.toLong())
    // 生成走后台（切页面不中断），试跑只是预览、留在本页
    val generating = (s.id to date) in repo.generating.collectAsState().value
    val generated = repo.generated.collectAsState().value[s.id to date]
    if (saved && !generating && generated != null) result = generated
    fun go(save: Boolean) {
        if (busy || generating) return
        result = null; saved = save
        if (save) { repo.generate(s.id, date); return }
        busy = true
        scope.launch { result = try { repo.trial(s.id, date, prompt) } finally { busy = false } }
    }
    RsCard(padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
        Text("试一下", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        Text("用这天的真实记录跑一次。试跑只看效果不保存；生成会存进记忆（已有的同一张会更新）。", style = RsType.caption, color = c.textSecondary)
        Spacer(Modifier.height(12.dp))
        Segments(listOf("今天", "昨天", "前天"), day) { day = it }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(if (busy) "试跑中…" else "试跑", ActionKind.Secondary, Modifier.weight(1f), enabled = !busy && !generating) { go(false) }
            ActionButton(if (generating) "后台生成中…" else "生成", ActionKind.Primary, Modifier.weight(1f), enabled = !busy && !generating) { go(true) }
        }
        when (val r = result) {
            null -> Unit
            is SkillRunResult.Nothing -> ResultNote("没出卡：${r.reason}", false)
            is SkillRunResult.Failed -> ResultNote(r.reason, true)
            is SkillRunResult.Cards -> {
                Spacer(Modifier.height(12.dp))
                ResultNote(if (saved) "已生成 ${r.cards.size} 张，放进了记忆里" else "预览 ${r.cards.size} 张（没有保存）", false)
                r.cards.forEach { card ->
                    Spacer(Modifier.height(10.dp))
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.background).padding(14.dp)) {
                        Text(card.title, style = RsType.headline, color = c.text)
                        if (card.summary.isNotBlank()) Text(card.summary, style = RsType.subhead, color = c.textSecondary)
                        Spacer(Modifier.height(12.dp))
                        SkillCardBody(card)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultNote(text: String, bad: Boolean) {
    val c = Rs.colors
    Spacer(Modifier.height(10.dp))
    Text(text, style = RsType.subhead, color = if (bad) c.critical else c.textSecondary)
}

/** 编辑要求：保存 / 恢复默认 / 用改过还没保存的要求试跑 */
@Composable
private fun PromptSheet(s: Skill, repo: SkillRepository, onDismiss: () -> Unit) {
    val c = Rs.colors
    var text by remember { mutableStateOf(repo.prompt(s.id)) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    RsSheet("${s.name} · 要求", "告诉大模型这张卡怎么写。“只算我说的话、不编造、带出处”这些总规则会自动加上，不用写。", onDismiss = onDismiss) {
        BasicTextField(
            text, { text = it; error = null },
            textStyle = RsType.subhead.copy(color = c.text, lineHeight = 20.sp), cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 380.dp).clip(RoundedCornerShape(14.dp)).background(c.background)
                .verticalScroll(rememberScrollState()).padding(14.dp),
        )
        error?.let { Text(it, style = RsType.subhead, color = c.critical) }
        TryCard(s, repo, prompt = text)
        ActionButton("保存", ActionKind.Primary, enabled = text != repo.prompt(s.id)) {
            repo.savePrompt(s.id, text).onSuccess { onDismiss() }.onFailure { error = it.message ?: "没保存成功" }
        }
        // 每个技能都留着一份默认要求：官方的是出厂那份，我的版本是复制或新建时那份
        val isDefault = !s.promptCustomized && text == repo.defaultPrompt(s.id)
        ActionButton(when { isDefault -> "现在用的就是默认要求"; confirmReset -> "确定恢复默认？再点一次"; else -> "恢复默认要求" },
            ActionKind.Danger, enabled = !isDefault) {
            if (!confirmReset) confirmReset = true else { repo.resetPrompt(s.id); onDismiss() }
        }
    }
}
