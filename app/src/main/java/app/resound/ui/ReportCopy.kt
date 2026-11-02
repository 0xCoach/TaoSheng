package app.resound.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.resound.review.DailyReview
import app.resound.skills.FieldType
import app.resound.skills.SkillCard
import app.resound.ui.verify.ActionButton
import app.resound.ui.verify.ActionKind
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
        DisableSelection {
            ReportCopySheet(document.text, document.blocks,
                onCopy = { copyReportText(context, it, "已复制所选内容") },
                onDismiss = { selecting = null })
        }
    }
}
