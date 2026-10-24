package app.resound.ui.skills

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.resound.skills.SkillCard
import app.resound.skills.SkillOrigin
import app.resound.skills.SkillRepository
import app.resound.skills.SkillRunResult
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.ActionButton
import app.resound.ui.verify.ActionKind
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.springClick
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 一张技能卡的详情：内容积木 + 重新生成 / 删除 */
@Composable
fun SkillCardSheet(card: SkillCard, repo: SkillRepository, onDismiss: () -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val skillExists = repo.skills.collectAsState().value.any { it.id == card.skillId }
    RsSheet(card.title, "${card.skillName} · ${card.date.format(DateTimeFormatter.ofPattern("M月d日"))} ${cardTime(card)}", onDismiss = onDismiss) {
        if (card.summary.isNotBlank()) {
            Text(card.summary, style = RsType.body, color = c.text,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(skillInk(card.icon).copy(alpha = 0.08f)).padding(14.dp))
        }
        SkillCardBody(card)
        Spacer(Modifier.height(4.dp))
        note?.let { Text(it, style = RsType.subhead, color = c.textSecondary) }
        if (skillExists) ActionButton(if (busy) "重新生成中…" else "重新生成", ActionKind.Secondary, enabled = !busy) {
            busy = true
            scope.launch {
                val r = try { repo.runNow(card.skillId, card.date) } finally { busy = false }
                note = when (r) {
                    is SkillRunResult.Cards -> "已更新"
                    is SkillRunResult.Nothing -> "没出新卡：${r.reason}"
                    is SkillRunResult.Failed -> r.reason
                }
            }
        }
        ActionButton(if (confirmDelete) "确定删除？以后不会再为这件事出卡" else "删除这张卡", ActionKind.Danger) {
            if (!confirmDelete) confirmDelete = true else { repo.deleteCard(card.id); onDismiss() }
        }
    }
}

/** 给某天手动生成：选一个技能，跑完显示结果 */
@Composable
fun MakeCardSheet(date: LocalDate, repo: SkillRepository, onDismiss: () -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    val skills = repo.skills.collectAsState().value.filter { it.origin != SkillOrigin.Core }
    var running by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<Pair<String, String>?>(null) }
    val label = if (date == LocalDate.now()) "今天" else date.format(DateTimeFormatter.ofPattern("M月d日"))
    RsSheet("给$label 生成卡片", "选一个技能，用这天的记录生成；已有的同一张会更新。关着的技能也能手动生成。", onDismiss = onDismiss) {
        skills.forEach { s ->
            val busy = running == s.id
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.background)
                .springClick {
                    if (running != null) return@springClick
                    running = s.id; result = null
                    scope.launch {
                        val r = try { repo.runNow(s.id, date) } finally { running = null }
                        result = s.id to when (r) {
                            is SkillRunResult.Cards -> "出了 ${r.cards.size} 张，已放进这天"
                            is SkillRunResult.Nothing -> "没出卡：${r.reason}"
                            is SkillRunResult.Failed -> r.reason
                        }
                    }
                }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SkillIcon(s.icon, 38.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                    val sub = when {
                        busy -> "生成中…"
                        result?.first == s.id -> result!!.second
                        else -> s.summary
                    }
                    Text(sub, style = RsType.caption, color = if (busy || result?.first == s.id) skillInk(s.icon) else c.textSecondary, maxLines = 2)
                }
            }
        }
    }
}
