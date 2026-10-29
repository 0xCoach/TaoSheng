package app.resound.ui.today

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.resound.digest.MemoryEvent
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.springClick

/** 只有“媒体”（电视、视频、外放）显示成灰色、不算进节奏；“旁人”已取消，旧数据当普通的事 */
internal const val MEDIA_SCENE = "媒体"
internal fun MemoryEvent.isMedia() = scene == MEDIA_SCENE

/** 时间线筛选：平时浏览和多选时都能用；以后环境音的标签也接在这一排后面 */
internal sealed interface TimelineFilter {
    val label: String
    fun matches(e: MemoryEvent): Boolean
    data object All : TimelineFilter { override val label = "全部"; override fun matches(e: MemoryEvent) = true }
    data object Starred : TimelineFilter { override val label = "重点"; override fun matches(e: MemoryEvent) = e.starred }
    data object HasItems : TimelineFilter { override val label = "有待办"; override fun matches(e: MemoryEvent) = e.items.any { it.category in setOf("待办", "约定", "备忘") } }
    data object Meeting : TimelineFilter { override val label = "会议"; override fun matches(e: MemoryEvent) = e.scene == "会议" && !e.isMedia() }
    data object Media : TimelineFilter { override val label = "媒体"; override fun matches(e: MemoryEvent) = e.isMedia() }
    /** 整理时提到的人（不是声纹认出的人），所以叫“提到” */
    data class Person(val name: String) : TimelineFilter { override val label = name; override fun matches(e: MemoryEvent) = !e.isMedia() && name in e.people }
}

/** 当天可用的筛选：数量为 0 的不出现（全部除外）；人名按出现次数取前 5 */
internal fun timelineFilters(events: List<MemoryEvent>): List<Pair<TimelineFilter, Int>> {
    val fixed = listOf(TimelineFilter.All, TimelineFilter.Starred, TimelineFilter.HasItems, TimelineFilter.Meeting, TimelineFilter.Media)
        .map { it to events.count(it::matches) }.filter { (f, n) -> f == TimelineFilter.All || n > 0 }
    val people = events.filter { !it.isMedia() }.flatMap { it.people.distinct() }.filter { it.isNotBlank() && it != "我" }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(5)
        .map { TimelineFilter.Person(it.key) to it.value }
    return fixed + people
}

/** 一排可横向滑动的筛选胶囊，带数量；媒体前有灰点，人名前有小圆头像点 */
@Composable
internal fun FilterChips(options: List<Pair<TimelineFilter, Int>>, selected: TimelineFilter, modifier: Modifier = Modifier, onSelect: (TimelineFilter) -> Unit) {
    if (options.size <= 1) return
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (f, n) -> Chip(f, n, f == selected) { onSelect(if (f == selected) TimelineFilter.All else f) } }
    }
}

@Composable
private fun Chip(f: TimelineFilter, count: Int, on: Boolean, onClick: () -> Unit) {
    val c = Rs.colors
    val bg by animateColorAsState(if (on) c.accent else c.card, spring(stiffness = Spring.StiffnessMediumLow), label = "chip")
    Row(Modifier.clip(CircleShape).background(bg).springClick(onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        val dot = when (f) { TimelineFilter.Media -> c.textTertiary; is TimelineFilter.Person -> c.accent.copy(alpha = 0.6f); else -> null }
        if (dot != null) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (on) Color.White.copy(alpha = 0.8f) else dot))
            Spacer(Modifier.width(6.dp))
        }
        Text(f.label, style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = if (on) Color.White else c.textSecondary, maxLines = 1)
        if (f != TimelineFilter.All) {
            Spacer(Modifier.width(4.dp))
            Text("$count", style = RsType.caption, color = if (on) Color.White.copy(alpha = 0.85f) else c.textTertiary)
        }
    }
}
