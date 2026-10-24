package app.resound.ui.transcript

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.resound.transcribe.HotwordSettings
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.Pill
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.springClick

/** 热词：手动加的词（可删）+ 自动带上的人名（灰色只读） */
@Composable
fun HotwordCard(settings: HotwordSettings) {
    val c = Rs.colors
    val words by settings.words.collectAsState()
    val auto by settings.automatic.collectAsState()
    var input by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    fun add() {
        val parts = input.split(Regex("[，,、\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        val bad = parts.firstOrNull { it.length > 20 }
        if (bad != null) { note = "每个词最多 20 个字"; return }
        val next = (words + parts).distinct()
        if (next.size > 200) { note = "最多 200 个词"; return }
        settings.setWords(next); input = ""; note = null
    }
    RsCard(padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
        Text("热词", style = RsType.body, color = c.text)
        Text("你的称呼、常听到的人名、公司名和专有名词。转写和整理时会优先认成这些词。", style = RsType.caption, color = c.textSecondary)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                input, { input = it.take(200); note = null }, singleLine = true,
                textStyle = RsType.body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { add() }),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(c.background).padding(horizontal = 14.dp, vertical = 11.dp),
                decorationBox = { inner -> Box { if (input.isEmpty()) Text("输入词语，多个用逗号隔开", style = RsType.body, color = c.textTertiary); inner() } },
            )
            Spacer(Modifier.width(10.dp))
            Pill("添加", filled = input.isNotBlank()) { add() }
        }
        note?.let { Text(it, style = RsType.caption, color = c.critical, modifier = Modifier.padding(top = 6.dp)) }
        if (words.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                words.forEach { w ->
                    Row(Modifier.clip(CircleShape).background(c.accentSoft).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(w, style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.accent)
                        Text("×", style = RsType.subhead, color = c.accent.copy(alpha = 0.7f),
                            modifier = Modifier.clip(CircleShape).springClick { settings.setWords(words - w) }.padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
            }
        }
        if (auto.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("自动带上（来自声纹人物）", style = RsType.caption, color = c.textTertiary)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                auto.forEach { w ->
                    Text(w, style = RsType.subhead, color = c.textSecondary,
                        modifier = Modifier.clip(CircleShape).background(c.background).padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("手机本地转写暂不支持热词，只对云端转写和整理生效。", style = RsType.caption, color = c.textTertiary)
    }
}
