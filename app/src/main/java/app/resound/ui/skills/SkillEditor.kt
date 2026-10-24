package app.resound.ui.skills

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.resound.skills.FieldType
import app.resound.skills.Skill
import app.resound.skills.SkillField
import app.resound.skills.SkillOrigin
import app.resound.skills.SkillRange
import app.resound.skills.SkillRepository
import app.resound.skills.SkillScope
import app.resound.skills.SkillSpeakers
import app.resound.skills.SkillTrigger
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.ActionButton
import app.resound.ui.verify.ActionKind
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.SheetInput
import app.resound.ui.verify.springClick
import kotlinx.coroutines.launch

private val ICONS = listOf("star", "chat", "heart", "bulb", "mic", "meeting", "sunset", "calendar_week", "review", "timeline")

private val TYPES = listOf(
    FieldType.Text to "一句话", FieldType.Paragraph to "一段话", FieldType.Bullets to "要点", FieldType.Checklist to "清单",
    FieldType.Quotes to "原话", FieldType.Tags to "标签", FieldType.Number to "数字", FieldType.Rating to "评分",
)

/** 新建技能的起点：只手动、看当天所有人的话、两项内容 */
fun blankSkill(): Skill = Skill(
    id = "my_" + java.lang.Long.toString(System.currentTimeMillis() / 1000, 36),
    name = "", summary = "", icon = "star", origin = SkillOrigin.Mine, version = 1, enabled = true,
    trigger = SkillTrigger.Manual, scope = SkillScope(SkillRange.Day, SkillSpeakers.All, true),
    fields = listOf(
        SkillField("one_line", "一句话", FieldType.Text, "用一句话概括", required = true),
        SkillField("points", "要点", FieldType.Bullets, "列出值得记下的几点"),
    ),
    maxCardsPerDay = 1, push = false, presetId = null, promptCustomized = false,
)

const val BLANK_PROMPT = "从这天的记录里找出 ______ 相关的内容，整理成一张卡。\n只写有原话依据的内容；没有相关内容就不出卡。"

private class FieldDraft(f: SkillField) {
    val key = f.key
    var label by mutableStateOf(f.label)
    var hint by mutableStateOf(f.hint)
    var type by mutableStateOf(f.type)
    var required by mutableStateOf(f.required)
    fun build() = SkillField(key, label.trim(), type, hint.trim(), required)
}

/**
 * 编辑“我的版本”：名字、图标、什么时候运行、用哪些话、卡片上有什么、给大模型的要求。
 * isNew 时是新建（保存即创建）。官方技能先复制成我的版本再进来。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SkillEditor(skill: Skill, prompt: String, repo: SkillRepository, isNew: Boolean, onSaved: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Rs.colors
    var name by remember { mutableStateOf(skill.name) }
    var summary by remember { mutableStateOf(skill.summary) }
    var icon by remember { mutableStateOf(skill.icon) }
    var trigger by remember { mutableStateOf(skill.trigger) }
    var range by remember { mutableStateOf(skill.scope.range) }
    var speakers by remember { mutableStateOf(skill.scope.speakers) }
    var includeLines by remember { mutableStateOf(skill.scope.includeLines) }
    var maxCards by remember { mutableStateOf(skill.maxCardsPerDay) }
    var text by remember { mutableStateOf(prompt) }
    val fields = remember { mutableStateListOf(*skill.fields.map(::FieldDraft).toTypedArray()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var wish by remember { mutableStateOf("") }
    var drafting by remember { mutableStateOf(false) }
    var draftNote by remember { mutableStateOf<String?>(null) }
    // 和大模型的来回：用户的描述/回答 + 它的追问；最后一句是追问时附带可点的选项
    val turns = remember { mutableStateListOf<app.resound.skills.DraftTurn>() }
    var options by remember { mutableStateOf<List<String>>(emptyList()) }

    /** 智能生成：把对话交给整理模型；缺信息它会追问，够了就把技能填进下面各项，看过再创建 */
    fun send(answer: String) {
        if (drafting || answer.isBlank()) return
        turns.add(app.resound.skills.DraftTurn(true, answer.trim()))
        wish = ""; options = emptyList(); drafting = true; draftNote = null
        scope.launch {
            repo.draft(turns.toList()).onSuccess { step ->
                when (step) {
                    is app.resound.skills.DraftStep.Ask -> { turns.add(app.resound.skills.DraftTurn(false, step.question)); options = step.options }
                    is app.resound.skills.DraftStep.Done -> {
                        val g = step.draft.skill
                        name = g.name; summary = g.summary; icon = g.icon; trigger = g.trigger
                        range = g.scope.range; speakers = g.scope.speakers; includeLines = g.scope.includeLines
                        maxCards = g.maxCardsPerDay; text = step.draft.prompt
                        fields.clear(); fields.addAll(g.fields.map(::FieldDraft))
                        draftNote = "已按你说的填好下面各项，看一眼、改一改再点“创建”。还想调整，直接接着说。"
                    }
                }
            }.onFailure { turns.removeAt(turns.lastIndex); wish = answer; draftNote = it.message ?: "没生成成功" }
            drafting = false
        }
    }

    fun save() {
        val built = fields.map { it.build() }
        error = when {
            name.isBlank() -> "给技能起个名字"
            built.isEmpty() -> "卡片上至少要有一项内容"
            built.any { it.label.isBlank() } -> "每一项内容都要有名字"
            text.isBlank() -> "要求不能为空"
            else -> null
        }
        if (error != null) return
        val s = skill.copy(name = name.trim(), summary = summary.trim().ifEmpty { name.trim() }, icon = icon, trigger = trigger,
            scope = SkillScope(range, speakers, includeLines || speakers == SkillSpeakers.MeOnly), fields = built, maxCardsPerDay = maxCards)
        repo.saveMine(s, text.trim()).onSuccess { onSaved(s.id) }.onFailure { error = it.message ?: "没保存成功" }
    }

    RsSheet(if (isNew) "新建技能" else "改“${skill.name}”", "改完保存，回到技能页可以“试一下”看效果。已出的旧卡不受影响。", onDismiss = onDismiss) {
        if (isNew) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.accentSoft).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("✨ 智能生成", style = RsType.headline, color = c.accent)
                Text("说说你想记什么、什么时候看。信息不够时回响会追问，问清楚了帮你把技能设计好。", style = RsType.caption, color = c.textSecondary)
                // 对话：我的话靠右橙底，追问靠左白底
                turns.forEach { t ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (t.fromUser) Arrangement.End else Arrangement.Start) {
                        Text(t.text, style = RsType.subhead, color = if (t.fromUser) Color.White else c.text,
                            modifier = Modifier.fillMaxWidth(0.82f).wrapContentWidth(if (t.fromUser) Alignment.End else Alignment.Start)
                                .clip(RoundedCornerShape(14.dp)).background(if (t.fromUser) c.accent else c.card).padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
                if (drafting) Text(if (turns.size <= 1) "正在理解你的需求…" else "正在想…", style = RsType.caption, color = c.textTertiary)
                if (options.isNotEmpty() && !drafting) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.forEach { op ->
                        Text(op, style = RsType.subhead, color = c.accent,
                            modifier = Modifier.clip(CircleShape).background(c.card).springClick { send(op) }.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
                BasicTextField(
                    wish, { wish = it },
                    textStyle = RsType.body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth().heightIn(min = if (turns.isEmpty()) 72.dp else 44.dp).clip(RoundedCornerShape(14.dp)).background(c.card).padding(12.dp),
                    decorationBox = { inner -> Box { if (wish.isEmpty()) Text(
                        if (turns.isEmpty()) "比如：每晚帮我记下孩子今天说的有趣的话和学到的新东西" else "回答，或者补充想法",
                        style = RsType.body, color = c.textTertiary); inner() } },
                )
                if (turns.isEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("每次和客户聊完，整理需求和下一步", "记下我的情绪和原因", "统计这周运动了几次").forEach { ex ->
                        Text(ex, style = RsType.caption, color = c.textSecondary,
                            modifier = Modifier.clip(CircleShape).background(c.card).springClick { wish = ex }.padding(horizontal = 10.dp, vertical = 5.dp))
                    }
                }
                ActionButton(when { drafting -> "生成中…"; turns.isEmpty() -> "智能生成"; else -> "发送" }, ActionKind.Primary,
                    enabled = !drafting && wish.isNotBlank()) { send(wish) }
                draftNote?.let { Text(it, style = RsType.subhead, color = c.text) }
            }
        }

        Label("名字和说明")
        SheetInput(name, "名字，如：孩子成长记录") { name = it }
        SheetInput(summary, "一句话说明它做什么") { summary = it }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            ICONS.forEach { i ->
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (i == icon) c.accent.copy(alpha = 0.18f) else Color.Transparent)
                    .springClick { icon = i }.padding(2.dp)) { SkillIcon(i, 28.dp) }
            }
        }

        Label("什么时候运行")
        val tIndex = when (trigger) { is SkillTrigger.Daily -> 0; is SkillTrigger.AfterDigest -> 1; SkillTrigger.Manual -> 2 }
        Segments(listOf("每天定时", "说完自动识别", "只手动"), tIndex) {
            trigger = when (it) { 0 -> (trigger as? SkillTrigger.Daily) ?: SkillTrigger.Daily(21 * 60); 1 -> SkillTrigger.AfterDigest(60); else -> SkillTrigger.Manual }
        }
        Hint(when (trigger) {
            is SkillTrigger.Daily -> "${triggerText(trigger)}运行；保存后可在技能页里调时间和星期。"
            is SkillTrigger.AfterDigest -> "每轮整理后看新内容，发现了就出卡；最多每小时一次。"
            SkillTrigger.Manual -> "只在你点“生成”时运行。"
        })

        Label("用哪些记录")
        Segments(listOf("当天", "本周", "上次以来"), range.ordinal) { range = SkillRange.entries[it] }
        Segments(listOf("所有人说的", "只用我说的"), if (speakers == SkillSpeakers.All) 0 else 1) { speakers = if (it == 0) SkillSpeakers.All else SkillSpeakers.MeOnly }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("带上原话", style = RsType.body, color = c.text)
                Text(if (speakers == SkillSpeakers.MeOnly) "只用我说的话，要靠原话分辨是谁说的，必须带上"
                    else "更准，花费更多；关掉只看整理好的事件", style = RsType.caption, color = c.textSecondary)
            }
            Toggle(includeLines || speakers == SkillSpeakers.MeOnly) { if (speakers != SkillSpeakers.MeOnly) includeLines = it }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("每天最多几张", style = RsType.body, color = c.text, modifier = Modifier.weight(1f))
            MiniStep("−") { maxCards = (maxCards - 1).coerceAtLeast(1) }
            Text("$maxCards", style = RsType.headline, color = c.text, modifier = Modifier.padding(horizontal = 14.dp))
            MiniStep("+") { maxCards = (maxCards + 1).coerceAtMost(20) }
        }

        Label("卡片上有什么")
        fields.forEachIndexed { i, f ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.background).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlainInput(f.label, "这一项的名字", Modifier.weight(1f), bold = true) { f.label = it }
                    if (i > 0) MiniStep("↑") { fields.add(i - 1, fields.removeAt(i)) }
                    if (i < fields.lastIndex) MiniStep("↓") { fields.add(i + 1, fields.removeAt(i)) }
                    MiniStep("✕") { fields.removeAt(i) }
                }
                PlainInput(f.hint, "告诉大模型这一项写什么", Modifier.fillMaxWidth()) { f.hint = it }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TYPES.forEach { (t, label) ->
                        val on = f.type == t
                        Text(label, style = RsType.caption, color = if (on) Color.White else c.textSecondary,
                            modifier = Modifier.clip(CircleShape).background(if (on) c.accent else c.card).springClick { f.type = t }
                                .padding(horizontal = 10.dp, vertical = 5.dp))
                    }
                    val req = f.required
                    Text(if (req) "必须有 ✓" else "可以空", style = RsType.caption, color = if (req) c.accent else c.textTertiary,
                        modifier = Modifier.clip(CircleShape).background(if (req) c.accentSoft else c.card).springClick { f.required = !req }
                            .padding(horizontal = 10.dp, vertical = 5.dp))
                }
            }
        }
        Text("＋ 加一项", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.accent,
            modifier = Modifier.clip(CircleShape).background(c.accentSoft).springClick {
                val used = fields.map { it.key }.toSet()
                val key = generateSequence(fields.size + 1) { it + 1 }.map { "f$it" }.first { it !in used }
                fields.add(FieldDraft(SkillField(key, "", FieldType.Bullets, "")))
            }.padding(horizontal = 14.dp, vertical = 8.dp))
        Hint("“必须有”的项为空时不出卡。")

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Label("给大模型的要求（提示词）") }
            // 我的版本留着复制/新建时的那份要求，改坏了可以放回来（保存后生效）
            if (!isNew && text != repo.defaultPrompt(skill.id)) Text("恢复默认要求", style = RsType.caption, color = c.accent,
                modifier = Modifier.padding(top = 4.dp).clip(CircleShape).background(c.accentSoft).springClick { text = repo.defaultPrompt(skill.id) }
                    .padding(horizontal = 10.dp, vertical = 5.dp))
        }
        Hint("写清楚找什么、怎么写、什么情况不出卡。“只算我说的话、不编造、带出处”等总规则会自动加上。")
        BasicTextField(
            text, { text = it },
            textStyle = RsType.subhead.copy(color = c.text, lineHeight = 20.sp), cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp).clip(RoundedCornerShape(14.dp)).background(c.background)
                .verticalScroll(rememberScrollState()).padding(14.dp),
        )

        error?.let { Text(it, style = RsType.subhead, color = c.critical) }
        ActionButton(if (isNew) "创建" else "保存", ActionKind.Primary) { save() }
    }
}

@Composable
private fun Label(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(text, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = Rs.colors.text)
}

@Composable
private fun Hint(text: String) = Text(text, style = RsType.caption, color = Rs.colors.textTertiary)

@Composable
private fun MiniStep(text: String, onClick: () -> Unit) {
    val c = Rs.colors
    Box(Modifier.padding(start = 4.dp).size(30.dp).clip(CircleShape).background(c.card).springClick(onClick), contentAlignment = Alignment.Center) {
        Text(text, style = RsType.subhead, color = c.textSecondary)
    }
}

@Composable
private fun PlainInput(value: String, hint: String, modifier: Modifier, bold: Boolean = false, onChange: (String) -> Unit) {
    val c = Rs.colors
    val style = (if (bold) RsType.body.copy(fontWeight = FontWeight.SemiBold) else RsType.subhead).copy(color = c.text)
    BasicTextField(value, onChange, singleLine = bold, textStyle = style, cursorBrush = SolidColor(c.accent),
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(c.card).padding(horizontal = 10.dp, vertical = 8.dp),
        decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, style = style.copy(color = c.textTertiary)); inner() } })
}
