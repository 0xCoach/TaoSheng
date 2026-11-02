package app.resound.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.resound.review.DailyReview
import app.resound.skills.FieldType
import app.resound.skills.SkillCard
import app.resound.ui.verify.ActionButton
import app.resound.ui.verify.ActionKind
import app.resound.ui.verify.RsSheet
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun copyStamp(at: Long): String = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

internal data class ReportCopyBlock(val id: String, val title: String, val text: String)

internal fun dailyReviewCopyBlocks(review: DailyReview): List<ReportCopyBlock> = buildList {
    add(ReportCopyBlock("header", "标题", "${review.date} · 日报"))
    if (review.brief.isNotBlank() && review.sections.none { review.brief in it.lines }) {
        add(ReportCopyBlock("summary", "摘要", review.brief))
    }
    review.sections.forEachIndexed { index, section ->
        if (section.lines.isNotEmpty()) add(ReportCopyBlock("section:$index", section.title,
            section.title + "\n" + section.lines.joinToString("\n") { "· $it" }))
    }
    add(ReportCopyBlock("generated", "生成时间", "生成于 ${copyStamp(review.generatedAt)}"))
}

internal fun dailyReviewCopyText(review: DailyReview): String = dailyReviewCopyBlocks(review).joinToString("\n\n") { it.text }

/** 和详情页共用显示规则，避免复制时重新带回旧周报中已隐藏的逐事件环境明细。 */
internal fun reportFields(card: SkillCard) = card.fields.filterNot {
    card.skillId == "weekly" && it.key in setOf("env_sound_observations", "env_sound_overview")
}

internal fun showReportSummary(card: SkillCard): Boolean = card.summary.isNotBlank() &&
    !(card.skillId == "weekly" && !card.values["one_line"]?.text.isNullOrBlank() && card.fields.any { it.key == "one_line" })

internal fun skillCardCopyBlocks(card: SkillCard): List<ReportCopyBlock> = buildList {
    add(ReportCopyBlock("header", "标题和时间", "${card.title}\n${card.skillName} · ${card.date}\n" +
        copyStamp(card.at) + (card.endAt?.let { " — ${copyStamp(it)}" } ?: "")))
    if (showReportSummary(card)) add(ReportCopyBlock("summary", "摘要", card.summary))
    reportFields(card).forEach { field ->
        val value = card.values[field.key] ?: return@forEach
        val body = when (field.type) {
            FieldType.Text, FieldType.Paragraph -> value.text.orEmpty()
            FieldType.Bullets, FieldType.Tags -> value.items.joinToString("\n") { "· $it" }
            FieldType.Checklist -> value.items.mapIndexed { i, item ->
                "${if (value.checked.getOrNull(i) == true) "☑" else "☐"} $item"
            }.joinToString("\n")
            FieldType.Quotes -> value.quotes.joinToString("\n") { "${it.speaker} · ${copyStamp(it.at)}\n“${it.text}”" }
            FieldType.Number -> value.number?.let { "${it.toString().removeSuffix(".0")}${value.unit?.let { unit -> " $unit" }.orEmpty()}" }.orEmpty()
            FieldType.Rating -> value.rating?.let { "$it / 5" }.orEmpty()
        }
        if (body.isNotBlank()) add(ReportCopyBlock("field:${field.key}", field.label, "${field.label}\n$body"))
    }
    add(ReportCopyBlock("generated", "生成时间", "生成于 ${copyStamp(card.generatedAt)}"))
}

internal fun skillCardCopyText(card: SkillCard): String = skillCardCopyBlocks(card).joinToString("\n\n") { it.text }

private fun copyReportText(context: android.content.Context, text: String, success: String) {
    val message = try {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("涛声", text))
        success
    } catch (_: RuntimeException) {
        "复制失败，内容可能超出系统剪贴板容量"
    }
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

private data class ReportCopySelection(val text: String, val blocks: List<ReportCopyBlock>)

/** 所有报告共用：一键完整复制、按模块多选、只读全文自由选字；不静默截断。 */
@Composable
internal fun ReportCopyActions(blocks: () -> List<ReportCopyBlock> = { emptyList() }, text: () -> String) {
    val context = LocalContext.current
    var selecting by remember { mutableStateOf<ReportCopySelection?>(null) }
    DisableSelection {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("复制全部内容", ActionKind.Secondary, Modifier.weight(1f)) {
                copyReportText(context, text(), "已复制全部内容")
            }
            ActionButton("选择部分内容", ActionKind.Secondary, Modifier.weight(1f)) { selecting = ReportCopySelection(text(), blocks()) }
        }
    }
    selecting?.let { document ->
        val fullText = document.text
        var value by remember(document) { mutableStateOf(TextFieldValue(fullText)) }
        var freeSelection by remember(document) { mutableStateOf(document.blocks.isEmpty()) }
        var selected by remember(document) { mutableStateOf(emptySet<String>()) }
        DisableSelection {
            RsSheet("选择部分内容", "按模块：点击多选，按原文顺序复制。自由选择：长按文字，拖动选择任意片段。",
                onDismiss = { selecting = null }, fixedHeight = true) {
                if (document.blocks.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("按模块选择", if (!freeSelection) ActionKind.Primary else ActionKind.Secondary, Modifier.weight(1f)) { freeSelection = false }
                    ActionButton("自由选择文字", if (freeSelection) ActionKind.Primary else ActionKind.Secondary, Modifier.weight(1f)) { freeSelection = true }
                }
                if (freeSelection) {
                    ActionButton("复制选中内容", ActionKind.Primary, enabled = !value.selection.collapsed) {
                        val selection = value.selection
                        if (!selection.collapsed) copyReportText(context, fullText.substring(selection.min, selection.max), "已复制选中内容")
                    }
                    BasicTextField(value = value, onValueChange = { value = it.copy(text = fullText) }, readOnly = true,
                        textStyle = RsType.body.copy(color = Rs.colors.text),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())
                            .padding(vertical = 12.dp).testTag("report-copy-selection"))
                } else {
                    ActionButton("复制已选模块（${selected.size}）", ActionKind.Primary, enabled = selected.isNotEmpty()) {
                        copyReportText(context, document.blocks.filter { it.id in selected }.joinToString("\n\n") { it.text }, "已复制所选模块")
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton("全选模块", ActionKind.Secondary, Modifier.weight(1f)) { selected = document.blocks.map { it.id }.toSet() }
                        ActionButton("清空选择", ActionKind.Secondary, Modifier.weight(1f), enabled = selected.isNotEmpty()) { selected = emptySet() }
                    }
                    document.blocks.forEach { block ->
                        val checked = block.id in selected
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(if (checked) Rs.colors.accentSoft else Rs.colors.background)
                            .toggleable(checked, role = Role.Checkbox) { selected = if (it) selected + block.id else selected - block.id }
                            .padding(12.dp).testTag("report-block:${block.id}"), verticalAlignment = Alignment.Top) {
                            Checkbox(checked, onCheckedChange = null)
                            Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(block.title, style = RsType.section, color = Rs.colors.text)
                                Text(block.text.removePrefix(block.title + "\n"), style = RsType.subhead, color = Rs.colors.textSecondary,
                                    maxLines = 4, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
