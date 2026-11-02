package app.resound.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType

/** 顶部切换、底部复制固定，中间内容独立滚动。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReportCopySheet(fullText: String, blocks: List<ReportCopyBlock>, onCopy: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Rs.colors
    var value by remember(fullText) { mutableStateOf(TextFieldValue(fullText)) }
    var freeSelection by remember(fullText) { mutableStateOf(blocks.isEmpty()) }
    var selected by remember(fullText) { mutableStateOf(emptySet<String>()) }
    val moduleScroll = rememberScrollState()
    val textScroll = rememberScrollState()
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.background,
        dragHandle = { Box(Modifier.padding(vertical = 10.dp).size(32.dp, 4.dp).clip(CircleShape).background(c.separator)) }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).testTag("report-copy-panel")) {
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("选择要复制的内容", style = RsType.title, color = c.text)
                    Text(if (freeSelection) "长按文字，拖动选择任意片段" else "轻点内容卡片，可选择多块",
                        style = RsType.subhead, color = c.textSecondary)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.testTag("report-copy-close").semantics { contentDescription = "关闭选择面板" }) {
                    Canvas(Modifier.size(16.dp)) {
                        drawLine(c.textSecondary, Offset(2f, 2f), Offset(size.width - 2f, size.height - 2f), 2.dp.toPx(), StrokeCap.Round)
                        drawLine(c.textSecondary, Offset(size.width - 2f, 2f), Offset(2f, size.height - 2f), 2.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
            if (blocks.isNotEmpty()) {
                Row(Modifier.padding(horizontal = 22.dp, vertical = 16.dp).fillMaxWidth()
                    .clip(CircleShape).background(c.separator.copy(alpha = 0.65f)).padding(4.dp).selectableGroup()) {
                    CopyMode("按模块", !freeSelection, Modifier.weight(1f)) { freeSelection = false }
                    CopyMode("自由选字", freeSelection, Modifier.weight(1f)) { freeSelection = true }
                }
            } else Spacer(Modifier.height(16.dp))
            if (freeSelection) {
                BasicTextField(value = value, onValueChange = { value = it.copy(text = fullText) }, readOnly = true,
                    textStyle = RsType.body.copy(color = c.text),
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 22.dp)
                        .clip(RoundedCornerShape(20.dp)).background(c.card).verticalScroll(textScroll)
                        .padding(18.dp).testTag("report-copy-selection"))
            } else {
                Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("共 ${blocks.size} 块内容", style = RsType.caption, color = c.textSecondary, modifier = Modifier.weight(1f))
                    CopyTextAction("全选", true) { selected = blocks.map { it.id }.toSet() }
                    CopyTextAction("清空", selected.isNotEmpty()) { selected = emptySet() }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(moduleScroll)
                    .padding(start = 22.dp, end = 22.dp, top = 2.dp, bottom = 16.dp).testTag("report-copy-modules"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    blocks.forEach { block ->
                        key(block.id) {
                            CopyModuleCard(block, block.id in selected) {
                                selected = if (it) selected + block.id else selected - block.id
                            }
                        }
                    }
                }
            }
            val enabled = if (freeSelection) !value.selection.collapsed else selected.isNotEmpty()
            Column(Modifier.fillMaxWidth().background(c.card).testTag("report-copy-footer")) {
                HorizontalDivider(color = c.separator.copy(alpha = 0.7f))
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(if (freeSelection) { if (enabled) "已选文字" else "尚未选择文字" } else "已选 ${selected.size} 块",
                            style = RsType.headline, color = c.text)
                        Text(if (freeSelection) "仅复制选中的片段" else "按原文顺序复制", style = RsType.caption, color = c.textSecondary)
                    }
                    Box(Modifier.widthIn(min = 132.dp).clip(CircleShape)
                        .background(if (enabled) c.accent else c.separator)
                        .clickable(enabled = enabled, role = Role.Button) {
                            if (freeSelection) onCopy(fullText.substring(value.selection.min, value.selection.max))
                            else onCopy(blocks.filter { it.id in selected }.joinToString("\n\n") { it.text })
                        }.padding(horizontal = 20.dp, vertical = 15.dp), contentAlignment = Alignment.Center) {
                        Text(if (freeSelection) "复制选中内容" else "复制所选", style = RsType.body,
                            color = if (enabled) Color.White else c.textTertiary)
                    }
                }
            }
        }
    }
}

@Composable
private fun CopyMode(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Rs.colors
    val background by animateColorAsState(if (selected) c.card else Color.Transparent, tween(160), label = "copyMode")
    Box(modifier.clip(CircleShape).background(background).selectable(selected, role = Role.Tab, onClick = onClick)
        .padding(vertical = 11.dp), contentAlignment = Alignment.Center) {
        Text(label, style = RsType.subhead, color = if (selected) c.text else c.textSecondary)
    }
}

@Composable
private fun CopyTextAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 14.dp)) {
        Text(label, style = RsType.subhead, color = if (enabled) Rs.colors.accent else Rs.colors.textTertiary)
    }
}

@Composable
private fun CopyModuleCard(block: ReportCopyBlock, checked: Boolean, onChecked: (Boolean) -> Unit) {
    val c = Rs.colors
    val background by animateColorAsState(if (checked) c.accentSoft else c.card, tween(180), label = "copyCard")
    val border by animateColorAsState(if (checked) c.accent.copy(alpha = 0.65f) else c.separator.copy(alpha = 0.6f), tween(180), label = "copyBorder")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(background)
        .border(1.dp, border, RoundedCornerShape(18.dp))
        .toggleable(checked, role = Role.Checkbox, onValueChange = onChecked)
        .testTag("report-block:${block.id}").padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(block.title, style = RsType.headline, color = c.text, modifier = Modifier.weight(1f))
            // 固定占位，勾出现时标题和正文不移动；未选中不显示空框。
            Box(Modifier.padding(start = 12.dp).size(22.dp), contentAlignment = Alignment.Center) {
                androidx.compose.animation.AnimatedVisibility(checked, enter = fadeIn(tween(140)) + scaleIn(tween(180), initialScale = 0.8f), exit = fadeOut(tween(100))) {
                    Canvas(Modifier.size(22.dp).clip(CircleShape).background(c.accent).padding(6.dp)) {
                        drawLine(Color.White, Offset(0f, size.height * 0.5f), Offset(size.width * 0.36f, size.height * 0.86f), 1.8.dp.toPx(), StrokeCap.Round)
                        drawLine(Color.White, Offset(size.width * 0.36f, size.height * 0.86f), Offset(size.width, size.height * 0.13f), 1.8.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
        }
        Text(block.text.removePrefix(block.title + "\n"), style = RsType.subhead, color = c.textSecondary,
            maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}
