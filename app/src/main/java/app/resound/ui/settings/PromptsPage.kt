package app.resound.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.resound.prompts.PromptInfo
import app.resound.prompts.PromptTemplates
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.ActionButton
import app.resound.ui.verify.ActionKind
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.springClick

/** 设置 → 整理指令：三个模板的列表，点开编辑；改过的标“已修改”，可恢复默认 */
@Composable
fun PromptsPage(repo: PromptTemplates, bottomPadding: Dp) {
    val c = Rs.colors
    val list by repo.list.collectAsState()
    var editing by remember { mutableStateOf<PromptInfo?>(null) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding + 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("大模型整理、回顾、问问时用的指令。一般不用改；想调整语气或侧重点时再动，改坏了可以恢复默认。",
            style = RsType.subhead, color = c.textSecondary, modifier = Modifier.padding(horizontal = 4.dp))
        RsCard(padding = PaddingValues(vertical = 4.dp)) {
            list.forEachIndexed { i, p ->
                Row(Modifier.fillMaxWidth().springClick { editing = p }.padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.title, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                            if (p.customized) {
                                Spacer(Modifier.width(6.dp))
                                Text("已修改", style = RsType.caption, color = c.accent,
                                    modifier = Modifier.clip(CircleShape).background(c.accentSoft).padding(horizontal = 7.dp, vertical = 1.dp))
                            }
                        }
                        Text(p.description, style = RsType.caption, color = c.textSecondary)
                    }
                    Text("›", style = RsType.title, color = c.textTertiary)
                }
                if (i != list.lastIndex) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
            }
        }
    }
    editing?.let { p -> PromptEditor(repo, p) { editing = null } }
}

@Composable
private fun PromptEditor(repo: PromptTemplates, info: PromptInfo, onDismiss: () -> Unit) {
    val c = Rs.colors
    var text by remember { mutableStateOf(repo.text(info.id)) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    RsSheet(info.title, info.description, onDismiss = onDismiss) {
        if (info.requiredPlaceholders.isNotEmpty()) {
            Text("这些占位符要保留（运行时会换成真实内容）", style = RsType.caption, color = c.textTertiary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                info.requiredPlaceholders.forEach { ph ->
                    val present = ph in text
                    Text(ph, style = RsType.caption.copy(fontFamily = FontFamily.Monospace), color = if (present) c.textSecondary else c.critical,
                        modifier = Modifier.clip(CircleShape).background(if (present) c.background else c.critical.copy(alpha = 0.1f)).padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
        }
        BasicTextField(
            text, { text = it; error = null },
            textStyle = RsType.subhead.copy(color = c.text, lineHeight = 20.sp), cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 420.dp).clip(RoundedCornerShape(14.dp)).background(c.background)
                .verticalScroll(rememberScrollState()).padding(14.dp),
        )
        error?.let { Text(it, style = RsType.subhead, color = c.critical) }
        ActionButton("保存", ActionKind.Primary, enabled = text != repo.text(info.id)) {
            repo.save(info.id, text).onSuccess { onDismiss() }.onFailure { error = it.message ?: "没保存成功" }
        }
        // 总是留着恢复入口，防止改坏了找不回来
        val isDefault = !info.customized && text == repo.defaultText(info.id)
        ActionButton(when { isDefault -> "现在用的就是默认要求"; confirmReset -> "确定恢复默认？再点一次"; else -> "恢复默认要求" },
            ActionKind.Danger, enabled = !isDefault) {
            if (!confirmReset) confirmReset = true
            else if (!info.customized) text = repo.defaultText(info.id) // 只是还没保存的改动：放回默认文字即可
            else { repo.reset(info.id); onDismiss() }
        }
    }
}

/** 某一个模板的提示词卡：预览前三行，点“改”打开编辑；技能页的“基础”三项各用一张 */
@Composable
fun PromptCard(repo: PromptTemplates, id: String) {
    val c = Rs.colors
    val info = repo.list.collectAsState().value.firstOrNull { it.id == id } ?: return
    var editing by remember { mutableStateOf(false) }
    RsCard(Modifier.springClick { editing = true }, padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("给大模型的要求（提示词）", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text, modifier = Modifier.weight(1f))
            if (info.customized) {
                Text("已修改", style = RsType.caption, color = c.accent,
                    modifier = Modifier.clip(CircleShape).background(c.accentSoft).padding(horizontal = 8.dp, vertical = 2.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text("改 ›", style = RsType.subhead, color = c.accent)
        }
        Spacer(Modifier.height(6.dp))
        Text(info.description, style = RsType.caption, color = c.textTertiary)
        Spacer(Modifier.height(4.dp))
        Text(repo.text(id), style = RsType.subhead, color = c.textSecondary, maxLines = 3)
    }
    if (editing) PromptEditor(repo, info) { editing = false }
}
