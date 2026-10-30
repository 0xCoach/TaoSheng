package app.resound.ui.ask

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType

/** 回答正文里的一块：段落、标题、列表项、引用、分隔线 */
internal sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    /** [marker] 是“•”或“1.”；[depth] 缩进层级 */
    data class Item(val marker: String, val text: String, val depth: Int) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Rule : MdBlock
}

internal object AnswerMarkdown {
    private val heading = Regex("""^(#{1,6})\s+(.*)$""")
    private val bullet = Regex("""^(\s*)[-*+•]\s+(.*)$""")
    private val numbered = Regex("""^(\s*)(\d{1,3})(?:[.)]\s+|、\s*)(.*)$""")
    private val rule = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
    private val tableRule = Regex("""^\s*\|?\s*:?-{3,}.*$""")

    /** 按行切块；连续的普通行并成一段。流式输出时最后一行可能不完整，照常解析即可 */
    fun blocks(text: String): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        val para = StringBuilder()
        fun flush() { if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim()); para.clear() }
        for (raw in text.replace("\r", "").lines()) {
            val line = raw.trimEnd()
            when {
                line.isBlank() -> flush()
                rule.matches(line) -> { flush(); out += MdBlock.Rule }
                tableRule.matches(line) && line.contains('-') && line.contains('|') -> {} // 表格的分隔行不显示
                else -> {
                    val h = heading.matchEntire(line)
                    val bl = bullet.matchEntire(line)
                    val nu = numbered.matchEntire(line)
                    val t = line.trim()
                    when {
                        h != null -> { flush(); out += MdBlock.Heading(h.groupValues[1].length, h.groupValues[2]) }
                        bl != null -> { flush(); out += MdBlock.Item("•", bl.groupValues[2], bl.groupValues[1].length / 2) }
                        nu != null -> { flush(); out += MdBlock.Item(nu.groupValues[2] + ".", nu.groupValues[3], nu.groupValues[1].length / 2) }
                        t.startsWith(">") -> { flush(); out += MdBlock.Quote(t.removePrefix(">").trim()) }
                        // 表格行：去掉两边竖线，格子之间用“ · ”隔开
                        t.length > 1 && t.startsWith("|") && t.endsWith("|") -> { flush(); out += MdBlock.Paragraph(t.trim('|').split('|').joinToString(" · ") { it.trim() }) }
                        else -> { if (para.isNotEmpty()) para.append('\n'); para.append(line) }
                    }
                }
            }
        }
        flush()
        return out
    }

    private val inline = Regex("""\*\*(.+?)\*\*|__(.+?)__|~~(.+?)~~|`([^`]+)`|\*(?!\s)(.+?)(?<!\s)\*|\[\^(\d+)]""")

    /** 行内：**粗体**、*斜体*、`代码`、~~删除线~~、[^n] 出处小数字；流式末尾半截的标记先不显示 */
    fun inline(text: String, accent: Color, codeBg: Color, base: SpanStyle = SpanStyle()): AnnotatedString = buildAnnotatedString {
        var i = 0
        for (m in inline.findAll(text)) {
            append(text.substring(i, m.range.first))
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> withStyle(base.copy(fontWeight = FontWeight.SemiBold)) { append(inline(g[1], accent, codeBg, base.copy(fontWeight = FontWeight.SemiBold))) }
                g[2].isNotEmpty() -> withStyle(base.copy(fontWeight = FontWeight.SemiBold)) { append(inline(g[2], accent, codeBg, base.copy(fontWeight = FontWeight.SemiBold))) }
                g[3].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(g[3]) }
                g[4].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 14.sp)) { append(" ${g[4]} ") }
                g[5].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inline(g[5], accent, codeBg, base)) }
                g[6].isNotEmpty() -> withStyle(SpanStyle(color = accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, baselineShift = BaselineShift.Superscript)) { append(g[6]) }
            }
            i = m.range.last + 1
        }
        // 还没闭合的 **、`、[^ 先藏起来，免得流式输出时闪出符号
        append(text.substring(i).substringBefore("[^").replace("**", "").replace("__", "").replace("`", ""))
    }
}

/** 问问的回答正文：轻量 Markdown（标题、列表、引用、分隔线、粗体斜体、代码、出处小数字） */
@Composable
internal fun AnswerText(text: String) {
    val c = Rs.colors
    val body = RsType.body.copy(lineHeight = 24.sp)
    val codeBg = c.background
    fun md(s: String, base: SpanStyle = SpanStyle()) = AnswerMarkdown.inline(s, c.accent, codeBg, base)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (b in AnswerMarkdown.blocks(text)) when (b) {
            is MdBlock.Paragraph -> Text(md(b.text), style = body, color = c.text)
            is MdBlock.Heading -> Text(md(b.text, SpanStyle(fontWeight = FontWeight.SemiBold)),
                style = (if (b.level <= 2) RsType.headline else body).copy(fontWeight = FontWeight.SemiBold), color = c.text,
                modifier = Modifier.padding(top = 4.dp))
            is MdBlock.Item -> Row(Modifier.padding(start = (b.depth * 16).dp)) {
                Text(b.marker, style = body.copy(fontWeight = if (b.marker == "•") FontWeight.Bold else FontWeight.Medium),
                    color = if (b.marker == "•") c.accent else c.textSecondary, modifier = Modifier.widthIn(min = if (b.marker == "•") 16.dp else 22.dp))
                Text(md(b.text), style = body, color = c.text, modifier = Modifier.weight(1f))
            }
            is MdBlock.Quote -> Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(c.accent.copy(alpha = 0.5f)))
                Text(md(b.text), style = body, color = c.textSecondary, modifier = Modifier.padding(start = 10.dp).weight(1f))
            }
            MdBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(0.5.dp).background(c.separator))
        }
    }
}
