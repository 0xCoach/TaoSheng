package app.resound.ui.skills

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.resound.skills.CardQuote
import app.resound.skills.FieldType
import app.resound.skills.FieldValue
import app.resound.skills.SkillCard
import app.resound.skills.SkillField
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.springClick
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 每种图标一个柔和的底色（日间 / 夜间各一套），线条用同色系的深色 */
private data class Tint(val light: Color, val dark: Color, val ink: Color)

private val TINTS = mapOf(
    "meeting" to Tint(Color(0xFFDCE6F2), Color(0xFF2E3A48), Color(0xFF4F6E92)),
    "sunset" to Tint(Color(0xFFFCE3CF), Color(0xFF4A3426), Color(0xFFE0782E)),
    "calendar_week" to Tint(Color(0xFFE6E1F3), Color(0xFF36314A), Color(0xFF6E5FA6)),
    "review" to Tint(Color(0xFFF8DCD3), Color(0xFF4A2F2A), Color(0xFFC8624A)),
    "timeline" to Tint(Color(0xFFEDE5D6), Color(0xFF3C3529), Color(0xFF8C7652)),
    "mic" to Tint(Color(0xFFF7E6BF), Color(0xFF473C22), Color(0xFFB98A1C)),
    "heart" to Tint(Color(0xFFF6D9E1), Color(0xFF4A2B34), Color(0xFFC25A78)),
    "bulb" to Tint(Color(0xFFD7EEE4), Color(0xFF243F35), Color(0xFF3F8F6F)),
    "chat" to Tint(Color(0xFFE2ECD8), Color(0xFF313D28), Color(0xFF66864A)),
    "star" to Tint(Color(0xFFFBE9C6), Color(0xFF4A3D22), Color(0xFFD08A16)),
)

@Composable
fun skillInk(icon: String): Color = (TINTS[icon] ?: TINTS.getValue("star")).ink

/** 技能图标：圆角方块底 + 自绘线条 */
@Composable
fun SkillIcon(icon: String, size: Dp = 44.dp) {
    val c = Rs.colors
    val t = TINTS[icon] ?: TINTS.getValue("star")
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(if (c.isDark) t.dark else t.light), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size * 0.5f)) { glyph(icon, t.ink) }
    }
}

private fun DrawScope.glyph(icon: String, ink: Color) {
    val w = size.minDimension * 0.1f
    val s = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val W = size.width
    val H = size.height
    when (icon) {
        "meeting" -> { // 两个人
            drawCircle(ink, W * 0.16f, Offset(W * 0.34f, H * 0.3f), style = s)
            drawCircle(ink, W * 0.13f, Offset(W * 0.72f, H * 0.36f), style = s)
            drawArc(ink, 180f, 180f, false, Offset(W * 0.06f, H * 0.58f), Size(W * 0.56f, H * 0.6f), style = s)
            drawArc(ink, 200f, 140f, false, Offset(W * 0.52f, H * 0.62f), Size(W * 0.42f, H * 0.5f), style = s)
        }
        "sunset" -> { // 半个太阳 + 地平线
            drawArc(ink, 180f, 180f, false, Offset(W * 0.22f, H * 0.36f), Size(W * 0.56f, H * 0.56f), style = s)
            drawLine(ink, Offset(W * 0.04f, H * 0.66f), Offset(W * 0.96f, H * 0.66f), w, StrokeCap.Round)
            drawLine(ink, Offset(W * 0.28f, H * 0.86f), Offset(W * 0.72f, H * 0.86f), w, StrokeCap.Round)
            drawLine(ink, Offset(W * 0.5f, H * 0.04f), Offset(W * 0.5f, H * 0.2f), w, StrokeCap.Round)
            drawLine(ink, Offset(W * 0.14f, H * 0.24f), Offset(W * 0.24f, H * 0.34f), w, StrokeCap.Round)
            drawLine(ink, Offset(W * 0.86f, H * 0.24f), Offset(W * 0.76f, H * 0.34f), w, StrokeCap.Round)
        }
        "calendar_week" -> { // 日历
            drawRoundRect(ink, Offset(W * 0.08f, H * 0.16f), Size(W * 0.84f, H * 0.76f), CornerRadius(W * 0.14f), style = s)
            drawLine(ink, Offset(W * 0.08f, H * 0.4f), Offset(W * 0.92f, H * 0.4f), w)
            drawLine(ink, Offset(W * 0.3f, H * 0.04f), Offset(W * 0.3f, H * 0.24f), w, StrokeCap.Round)
            drawLine(ink, Offset(W * 0.7f, H * 0.04f), Offset(W * 0.7f, H * 0.24f), w, StrokeCap.Round)
            listOf(0.3f, 0.5f, 0.7f).forEach { x -> drawCircle(ink, w * 0.7f, Offset(W * x, H * 0.64f)) }
        }
        "review" -> { // 打开的书
            val p = Path().apply {
                moveTo(W * 0.5f, H * 0.24f); quadraticTo(W * 0.3f, H * 0.12f, W * 0.06f, H * 0.18f)
                lineTo(W * 0.06f, H * 0.84f); quadraticTo(W * 0.3f, H * 0.78f, W * 0.5f, H * 0.9f)
                quadraticTo(W * 0.7f, H * 0.78f, W * 0.94f, H * 0.84f); lineTo(W * 0.94f, H * 0.18f)
                quadraticTo(W * 0.7f, H * 0.12f, W * 0.5f, H * 0.24f); close()
            }
            drawPath(p, ink, style = s)
            drawLine(ink, Offset(W * 0.5f, H * 0.24f), Offset(W * 0.5f, H * 0.88f), w)
        }
        "timeline" -> { // 竖线 + 三个点
            drawLine(ink, Offset(W * 0.24f, H * 0.08f), Offset(W * 0.24f, H * 0.92f), w, StrokeCap.Round)
            listOf(0.2f, 0.5f, 0.8f).forEachIndexed { i, y ->
                drawCircle(ink, w * 1.1f, Offset(W * 0.24f, H * y))
                drawLine(ink, Offset(W * 0.44f, H * y), Offset(W * if (i == 1) 0.78f else 0.92f, H * y), w, StrokeCap.Round)
            }
        }
        "mic" -> {
            drawRoundRect(ink, Offset(W * 0.34f, H * 0.04f), Size(W * 0.32f, H * 0.54f), CornerRadius(W * 0.16f), style = s)
            drawArc(ink, 0f, 180f, false, Offset(W * 0.18f, H * 0.3f), Size(W * 0.64f, H * 0.46f), style = s)
            drawLine(ink, Offset(W * 0.5f, H * 0.76f), Offset(W * 0.5f, H * 0.94f), w, StrokeCap.Round)
        }
        "heart" -> {
            val p = Path().apply {
                moveTo(W * 0.5f, H * 0.88f)
                cubicTo(W * 0.1f, H * 0.62f, W * 0.0f, H * 0.3f, W * 0.26f, H * 0.16f)
                cubicTo(W * 0.4f, H * 0.1f, W * 0.48f, H * 0.2f, W * 0.5f, H * 0.3f)
                cubicTo(W * 0.52f, H * 0.2f, W * 0.6f, H * 0.1f, W * 0.74f, H * 0.16f)
                cubicTo(W * 1.0f, H * 0.3f, W * 0.9f, H * 0.62f, W * 0.5f, H * 0.88f); close()
            }
            drawPath(p, ink, style = s)
        }
        "bulb" -> {
            drawArc(ink, 140f, 260f, false, Offset(W * 0.18f, H * 0.04f), Size(W * 0.64f, H * 0.64f), style = s)
            drawLine(ink, Offset(W * 0.36f, H * 0.64f), Offset(W * 0.36f, H * 0.76f), w, StrokeCap.Round)
            drawLine(ink, Offset(W * 0.64f, H * 0.64f), Offset(W * 0.64f, H * 0.76f), w, StrokeCap.Round)
            drawLine(ink, Offset(W * 0.36f, H * 0.78f), Offset(W * 0.64f, H * 0.78f), w, StrokeCap.Round)
            drawLine(ink, Offset(W * 0.42f, H * 0.94f), Offset(W * 0.58f, H * 0.94f), w, StrokeCap.Round)
        }
        "chat" -> {
            drawRoundRect(ink, Offset(W * 0.06f, H * 0.1f), Size(W * 0.88f, H * 0.62f), CornerRadius(W * 0.2f), style = s)
            drawLine(ink, Offset(W * 0.3f, H * 0.72f), Offset(W * 0.22f, H * 0.92f), w, StrokeCap.Round)
            drawLine(ink, Offset(W * 0.22f, H * 0.92f), Offset(W * 0.5f, H * 0.72f), w, StrokeCap.Round)
        }
        else -> { // 星星
            val p = Path()
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) W * 0.46f else W * 0.2f
                val a = Math.toRadians(-90.0 + i * 36.0)
                val x = W * 0.5f + r * Math.cos(a).toFloat()
                val y = H * 0.54f + r * Math.sin(a).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            drawPath(p, ink, style = s)
        }
    }
}

internal fun clock(at: Long): String = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

/** 卡片时间：会议是“14:00–15:20”，其他是“18:00” */
internal fun cardTime(card: SkillCard): String = clock(card.at) + (card.endAt?.let { "–" + clock(it) } ?: "")

/** 列表里的卡片：图标 + 技能名 + 时间，标题，一句话摘要 */
@Composable
fun SkillCardRow(card: SkillCard, modifier: Modifier = Modifier) {
    val c = Rs.colors
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        SkillIcon(card.icon, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            // 合成一段文字：放不下时自然换行，不会把时间挤成竖排
            Text(androidx.compose.ui.text.buildAnnotatedString {
                withStyle(androidx.compose.ui.text.SpanStyle(color = skillInk(card.icon), fontWeight = FontWeight.Medium)) { append(card.skillName) }
                withStyle(androidx.compose.ui.text.SpanStyle(color = c.textTertiary)) { append(" · " + cardTime(card)) }
            }, style = RsType.caption)
            Text(card.title, style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 2)
            if (card.summary.isNotBlank()) Text(card.summary, style = RsType.subhead, color = c.textSecondary, maxLines = 2)
        }
    }
}

/** 卡片详情：按生成时的字段定义逐项显示，空项不显示 */
@Composable
fun SkillCardBody(card: SkillCard, onQuote: ((CardQuote) -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        card.fields.forEach { f -> card.values[f.key]?.takeIf { !it.isEmpty(f.type) }?.let { FieldBlock(f, it, card.icon, onQuote) } }
    }
}

private fun FieldValue.isEmpty(type: FieldType) = when (type) {
    FieldType.Text, FieldType.Paragraph -> text.isNullOrBlank()
    FieldType.Bullets, FieldType.Tags, FieldType.Checklist -> items.isEmpty()
    FieldType.Quotes -> quotes.isEmpty()
    FieldType.Number -> number == null
    FieldType.Rating -> rating == null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FieldBlock(field: SkillField, v: FieldValue, icon: String, onQuote: ((CardQuote) -> Unit)?) {
    val c = Rs.colors
    val ink = skillInk(icon)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(field.label, style = RsType.caption.copy(fontWeight = FontWeight.SemiBold), color = c.textTertiary)
        when (field.type) {
            FieldType.Text -> Text(v.text.orEmpty(), style = RsType.headline, color = c.text)
            FieldType.Paragraph -> Text(v.text.orEmpty(), style = RsType.body, color = c.text)
            FieldType.Bullets -> v.items.forEach { line ->
                Row {
                    Box(Modifier.padding(top = 8.dp).size(5.dp).clip(CircleShape).background(ink))
                    Spacer(Modifier.width(10.dp))
                    Text(line, style = RsType.body, color = c.text)
                }
            }
            FieldType.Checklist -> v.items.forEachIndexed { i, line ->
                val done = v.checked.getOrNull(i) == true
                Row {
                    Box(Modifier.padding(top = 2.dp).size(16.dp).clip(RoundedCornerShape(5.dp)).background(if (done) ink else c.separator), contentAlignment = Alignment.Center) {
                        if (done) Text("✓", style = RsType.caption, color = Color.White)
                        else Box(Modifier.size(12.dp).clip(RoundedCornerShape(4.dp)).background(c.card))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(line, style = RsType.body, color = if (done) c.textTertiary else c.text)
                }
            }
            FieldType.Tags -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                v.items.forEach { Text(it, style = RsType.subhead, color = ink, modifier = Modifier.clip(CircleShape).background(ink.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp)) }
            }
            FieldType.Quotes -> v.quotes.forEach { q ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.background)
                    .then(if (onQuote != null && q.lineId != null) Modifier.springClick { onQuote(q) } else Modifier).padding(12.dp)) {
                    Box(Modifier.width(3.dp).height(36.dp).clip(CircleShape).background(ink.copy(alpha = 0.6f)))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("${q.speaker} · ${clock(q.at)}", style = RsType.caption, color = c.textTertiary)
                        Text("“${q.text}”", style = RsType.subhead, color = c.text)
                    }
                }
            }
            FieldType.Number -> Row(verticalAlignment = Alignment.Bottom) {
                Text(v.number!!.let { if (it % 1.0 == 0.0) it.toLong().toString() else "%.1f".format(it) }, style = RsType.number, color = c.text)
                v.unit?.let { Text(" $it", style = RsType.subhead, color = c.textSecondary, modifier = Modifier.padding(bottom = 4.dp)) }
            }
            FieldType.Rating -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..5).forEach { i -> Box(Modifier.size(width = 22.dp, height = 6.dp).clip(CircleShape).background(if (i <= (v.rating ?: 0)) ink else c.separator)) }
            }
        }
    }
}
