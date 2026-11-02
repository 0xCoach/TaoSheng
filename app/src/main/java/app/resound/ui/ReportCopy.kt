package app.resound.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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

internal fun dailyReviewCopyText(review: DailyReview): String = buildString {
    appendLine("${review.date} · 日报")
    if (review.brief.isNotBlank() && review.sections.none { review.brief in it.lines }) {
        appendLine(); appendLine(review.brief)
    }
    review.sections.filter { it.lines.isNotEmpty() }.forEach { section ->
        appendLine(); appendLine(section.title)
        section.lines.forEach { appendLine("· $it") }
    }
    appendLine(); append("生成于 ${copyStamp(review.generatedAt)}")
}

/** 和详情页共用显示规则，避免复制时重新带回旧周报中已隐藏的逐事件环境明细。 */
internal fun reportFields(card: SkillCard) = card.fields.filterNot {
    card.skillId == "weekly" && it.key in setOf("env_sound_observations", "env_sound_overview")
}

internal fun showReportSummary(card: SkillCard): Boolean = card.summary.isNotBlank() &&
    !(card.skillId == "weekly" && !card.values["one_line"]?.text.isNullOrBlank() && card.fields.any { it.key == "one_line" })

internal fun skillCardCopyText(card: SkillCard): String = buildString {
    appendLine(card.title)
    appendLine("${card.skillName} · ${card.date}")
    appendLine(copyStamp(card.at) + (card.endAt?.let { " — ${copyStamp(it)}" } ?: ""))
    if (showReportSummary(card)) { appendLine(); appendLine(card.summary) }
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
        if (body.isNotBlank()) { appendLine(); appendLine(field.label); appendLine(body) }
    }
    appendLine(); append("生成于 ${copyStamp(card.generatedAt)}")
}

private fun copyReportText(context: android.content.Context, text: String, success: String) {
    val message = try {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("涛声", text))
        success
    } catch (_: RuntimeException) {
        "复制失败，内容可能超出系统剪贴板容量"
    }
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

/** 所有报告共用：完整复制，或在只读全文中选择任意片段；不静默截断。 */
@Composable
internal fun ReportCopyActions(text: () -> String) {
    val context = LocalContext.current
    var selecting by remember { mutableStateOf<String?>(null) }
    DisableSelection {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("复制全部内容", ActionKind.Secondary, Modifier.weight(1f)) {
                copyReportText(context, text(), "已复制全部内容")
            }
            ActionButton("选择部分内容", ActionKind.Secondary, Modifier.weight(1f)) { selecting = text() }
        }
    }
    selecting?.let { fullText ->
        var value by remember(fullText) { mutableStateOf(TextFieldValue(fullText)) }
        RsSheet("选择部分内容", "长按文字，拖动选择范围，再复制选中的内容。", onDismiss = { selecting = null }) {
            ActionButton("复制选中内容", ActionKind.Primary, enabled = !value.selection.collapsed) {
                val selection = value.selection
                if (!selection.collapsed) copyReportText(context, fullText.substring(selection.min, selection.max), "已复制选中内容")
            }
            BasicTextField(value = value, onValueChange = { value = it.copy(text = fullText) }, readOnly = true,
                textStyle = RsType.body.copy(color = Rs.colors.text),
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())
                    .padding(vertical = 12.dp).testTag("report-copy-selection"))
        }
    }
}
