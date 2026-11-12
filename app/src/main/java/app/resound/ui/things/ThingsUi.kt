package app.resound.ui.things

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.resound.AppGraph
import app.resound.things.RecordStatus
import app.resound.things.Thing
import app.resound.things.ThingAction
import app.resound.things.ThingDraft
import app.resound.things.ThingDrafts
import app.resound.things.ThingEdit
import app.resound.things.ThingOrigin
import app.resound.things.ThingRecord
import app.resound.things.ThingRepository
import app.resound.ui.items.AddHost
import app.resound.ui.items.AddWords
import app.resound.ui.items.Choice
import app.resound.ui.items.Empty
import app.resound.ui.items.Label
import app.resound.ui.preview.FakeThings
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.today.SmallPill
import app.resound.ui.verify.ActionButton
import app.resound.ui.verify.ActionKind
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.SheetInput
import app.resound.ui.verify.formatClock
import app.resound.ui.verify.formatStamp
import app.resound.ui.verify.springClick
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/*
 * 卡 079 寻物：记忆页「寻物」选项卡、东西详情、确认卡（首页待确认和寻物页共用）、编辑与手动添加。
 */

/** 寻物后端没装配时用假数据 */
fun thingRepo(): ThingRepository = if (AppGraph.isThingsReady) AppGraph.things else FakeThings

@Composable
internal fun thingColor(): Color = if (Rs.colors.isDark) Color(0xFF7DBBDA) else Color(0xFF3A8FB7)

private const val DAY = 86_400_000L

/** 3 天内的待确认放首页，更早的只在寻物页收起 */
fun ThingRecord.recent(now: Long = System.currentTimeMillis()) = now - at < 3 * DAY

private fun daysSince(at: Long): Long {
    val zone = ZoneId.systemDefault()
    return ChronoUnit.DAYS.between(Instant.ofEpochMilli(at).atZone(zone).toLocalDate(), LocalDate.now(zone))
}

/** 卡片主行：这样东西现在怎么样 */
fun Thing.statusLine(): String = when (latest.action) {
    ThingAction.Placed -> latest.place.orEmpty()
    ThingAction.Lent -> "借给 ${latest.who.orEmpty()} · " + daysSince(latest.at).let { if (it <= 0) "今天借的" else "已 $it 天" }
    ThingAction.TakenAway -> lastPlace?.let { "带走了 · 最后在 $it" } ?: "带走了"
    ThingAction.Returned -> latest.place?.let { "已还回 · 放在 $it" } ?: "已还回"
}

/** 一条记录的说法 */
fun ThingRecord.actionLine(): String = when (action) {
    ThingAction.Placed -> "放在 ${place.orEmpty()}"
    ThingAction.Lent -> "借给 ${who.orEmpty()}" + (place?.let { " · 放在 $it" } ?: "")
    ThingAction.TakenAway -> "带走了"
    ThingAction.Returned -> "还回来了" + (place?.let { " · 放在 $it" } ?: "")
}

/** 确认卡上的一句：钥匙 → 鞋柜抽屉 */
fun ThingRecord.cardLine(): String = when (action) {
    ThingAction.Placed -> "$name → ${place.orEmpty()}"
    ThingAction.Lent -> "$name 借给 ${who.orEmpty()}"
    ThingAction.TakenAway -> "$name 带走了"
    ThingAction.Returned -> "$name 还回来了"
}

@Composable
private fun statusTag(action: ThingAction): Pair<String, Color> {
    val c = Rs.colors
    return when (action) {
        ThingAction.Placed -> "放着" to thingColor()
        ThingAction.Lent -> "借出" to c.warning
        ThingAction.TakenAway -> "带走了" to c.textSecondary
        ThingAction.Returned -> "已还回" to c.good
    }
}

/** 小标签：与 CategoryChip 同款 */
@Composable
internal fun ThingTag(text: String, color: Color) {
    Box(Modifier.clip(CircleShape).background(color.copy(alpha = 0.13f)).padding(horizontal = 9.dp, vertical = 3.dp)) {
        Text(text, style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = color, maxLines = 1)
    }
}

/** 点一下变成“确认…”，3 秒内再点才执行 */
@Composable
private fun rememberArmed(): androidx.compose.runtime.MutableState<Boolean> {
    val armed = remember { mutableStateOf(false) }
    LaunchedEffect(armed.value) { if (armed.value) { delay(3000); armed.value = false } }
    return armed
}

// ---------- 寻物选项卡 ----------

/** 寻物页的看法：全部（一样东西一张卡）/ 按地方（同一个地方的归在一起）/ 只看带走了的 / 只看借出去的 */
enum class ThingFilter(val title: String, val empty: String) {
    All("全部", ""), ByPlace("按地方", ""), Taken("带走了", "没有带走的东西"), Lent("借出去的", "没有借出去的东西"),
}

/** 按地方看时的分组：当前放着的按位置；带走了、没记地方的各成一组放最后；借出去的不在这里 */
private fun placeGroups(list: List<Thing>): List<Pair<String, List<Thing>>> {
    val placed = list.filter { it.latest.action != ThingAction.Lent && it.latest.action != ThingAction.TakenAway && !it.latest.place.isNullOrBlank() }
    val byPlace = placed.groupBy { it.latest.place!!.trim() }.toList().sortedByDescending { (_, l) -> l.maxOf { it.latest.at } }
    val taken = list.filter { it.latest.action == ThingAction.TakenAway }
    val unknown = list.filter { it.latest.action == ThingAction.Returned && it.latest.place.isNullOrBlank() }
    return byPlace + listOfNotNull(taken.takeIf { it.isNotEmpty() }?.let { "带走了" to it }, unknown.takeIf { it.isNotEmpty() }?.let { "没记地方" to it })
}

/** 搜索词防抖 300 毫秒后查；空串查全部 */
@Composable
fun rememberThingList(things: ThingRepository, query: String): State<List<Thing>?> {
    var settled by remember { mutableStateOf(query) }
    LaunchedEffect(query) { if (query.isBlank()) settled = "" else { delay(300); settled = query.trim() } }
    return remember(things, settled) { if (settled.isEmpty()) things.things() else things.search(settled) }.collectAsStateWithLifecycle(initialValue = null)
}

fun LazyListScope.thingsTab(
    list: List<Thing>?,
    query: String,
    onQuery: (String) -> Unit,
    filter: ThingFilter,
    onFilter: (ThingFilter) -> Unit,
    pendingCount: Int,
    onOpenPending: () -> Unit,
    onOpen: (Thing) -> Unit,
    onReturned: (Thing) -> Unit,
) {
    item(key = "thingSearch") { SearchBox(query, onQuery) }
    item(key = "thingTiming") {
        Text("物品记录随时间线整理更新；急用时可点“现在整理”，也可以手动记下。",
            style = RsType.caption, color = Rs.colors.textTertiary, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
    }
    item(key = "thingFilter") {
        val c = Rs.colors
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ThingFilter.entries.forEach { f ->
                val on = f == filter
                Text(f.title, style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = if (on) c.accent else c.textSecondary,
                    modifier = Modifier.springClick { onFilter(f) }.clip(CircleShape).background(if (on) c.accentSoft else Color.Transparent)
                        .padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
    if (pendingCount > 0) item(key = "thingPending") {
        val c = Rs.colors
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.accentSoft).springClick(onOpenPending)
            .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("有 $pendingCount 条位置等你确认", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.accent, modifier = Modifier.weight(1f))
            Text("›", style = RsType.headline, color = c.accent)
        }
    }
    val shown = list.orEmpty().filter {
        when (filter) {
            ThingFilter.Taken -> it.latest.action == ThingAction.TakenAway
            ThingFilter.Lent -> it.latest.action == ThingAction.Lent
            else -> true
        }
    }
    if (list != null && shown.isEmpty()) item(key = "thingEmpty") {
        Empty(when {
            query.isNotBlank() -> "没找到“${query.trim()}”。去问问里问一句，会从原话里帮你找。"
            filter.empty.isNotEmpty() -> filter.empty
            else -> "说过“钥匙放抽屉了”这类话，就会记在这里。也可以点右下角，自己记一下。"
        })
    }
    if (filter == ThingFilter.ByPlace) {
        val groups = placeGroups(shown)
        if (list != null && shown.isNotEmpty() && groups.isEmpty()) item(key = "thingPlaceEmpty") { Empty("现在没有放着的东西，借出去的在“借出去的”里") }
        items(groups, key = { "place${it.first}" }) { (place, group) ->
            PlaceGroup(place, group, Modifier.animateItem(), onOpen)
        }
    } else items(shown, key = { "thing${it.id}" }) { t ->
        ThingCard(t, Modifier.animateItem(), onOpen = { onOpen(t) }, onReturned = { onReturned(t) })
    }
}

/** 一个地方一张卡：标题是地方，下面列放在这里的东西 */
@Composable
private fun PlaceGroup(place: String, group: List<Thing>, modifier: Modifier, onOpen: (Thing) -> Unit) {
    val c = Rs.colors
    RsCard(modifier, padding = PaddingValues(vertical = 6.dp)) {
        Row(Modifier.padding(start = 18.dp, end = 16.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(place, style = RsType.headline, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text("${group.size} 样", style = RsType.caption, color = c.textTertiary)
        }
        group.sortedByDescending { it.latest.at }.forEachIndexed { i, t ->
            Row(Modifier.fillMaxWidth().springClick { onOpen(t) }.padding(start = 18.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(6.dp).height(6.dp).clip(CircleShape).background(thingColor()))
                Spacer(Modifier.width(10.dp))
                Text(t.name, style = RsType.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                // 带走了的那组：写最后在哪，比时间更有用
                val meta = if (t.latest.action == ThingAction.TakenAway) t.lastPlace?.let { "最后在 $it" } ?: formatStamp(t.latest.at) else formatStamp(t.latest.at)
                Text(meta, style = RsType.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
            }
            if (i != group.lastIndex) Box(Modifier.padding(start = 34.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
        }
    }
}

@Composable
private fun SearchBox(query: String, onQuery: (String) -> Unit) {
    val c = Rs.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card).padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(query, { onQuery(it.take(40).replace("\n", "")) }, singleLine = true,
            textStyle = RsType.body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
            modifier = Modifier.weight(1f).padding(vertical = 14.dp),
            decorationBox = { inner -> Box { if (query.isEmpty()) Text("找东西，或搜地方：书柜、抽屉…", style = RsType.body, color = c.textTertiary); inner() } })
        AnimatedVisibility(query.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
            Text("×", style = RsType.headline, color = c.textTertiary,
                modifier = Modifier.clip(CircleShape).springClick { onQuery("") }.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun ThingCard(t: Thing, modifier: Modifier, onOpen: () -> Unit, onReturned: () -> Unit) {
    val c = Rs.colors
    RsCard(modifier.springClick(onOpen), padding = PaddingValues(start = 18.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.name, style = RsType.headline, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            val (label, color) = statusTag(t.latest.action)
            ThingTag(label, color)
        }
        Spacer(Modifier.height(6.dp))
        Text(t.statusLine(), style = RsType.body, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatStamp(t.latest.at) + if (t.latest.origin == ThingOrigin.Live) " 说的" else " 记的",
                style = RsType.caption, color = c.textTertiary, maxLines = 1, modifier = Modifier.weight(1f))
            if (t.hasPending) {
                Text("有新位置待确认", style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = thingColor(), maxLines = 1)
            }
            if (t.latest.action == ThingAction.Lent) {
                Spacer(Modifier.width(8.dp))
                SmallPill("已还", filled = false, onClick = onReturned)
            }
        }
    }
}

// ---------- 东西详情 ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThingSheet(thingId: Long, things: ThingRepository, onDismiss: () -> Unit) {
    val c = Rs.colors
    val context = LocalContext.current
    val thing by remember(thingId) { things.thing(thingId) }.collectAsStateWithLifecycle(initialValue = null)
    val history by remember(thingId) { things.history(thingId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var seen by remember { mutableStateOf(false) }
    // 删掉了（或被并走了）就自己关上；第一次发出值之前不算
    LaunchedEffect(thing) { if (thing != null) seen = true else if (seen) onDismiss() }
    val t = thing ?: return
    var renaming by remember { mutableStateOf(false) }
    var newName by remember(t.name) { mutableStateOf(t.name) }
    var editing by remember { mutableStateOf<ThingRecord?>(null) }
    var adding by remember { mutableStateOf(false) }
    val deleteArmed = rememberArmed()

    RsSheet(t.name, t.statusLine(), onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("名字")
            Spacer(Modifier.weight(1f))
            if (!renaming) Text("改名", style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.accent,
                modifier = Modifier.clip(CircleShape).springClick { renaming = true; newName = t.name }.padding(horizontal = 8.dp, vertical = 4.dp))
        }
        if (renaming) {
            SheetInput(newName, "如：车钥匙") { newName = it.take(20) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallPill("取消", filled = false) { renaming = false }
                SmallPill("保存", filled = true) {
                    if (newName.isNotBlank()) { things.rename(t.id, newName.trim()); renaming = false }
                }
            }
        } else if (t.aliases.isNotEmpty()) {
            Text("也叫：" + t.aliases.joinToString("、"), style = RsType.caption, color = c.textTertiary)
        }

        Label("经过")
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.fill).animateContentSize()) {
            history.forEachIndexed { i, r ->
                HistoryRow(r, onEdit = { editing = r }, onDelete = { things.deleteRecord(r.id) })
                if (i != history.lastIndex) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
            }
        }

        Spacer(Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (t.latest.action == ThingAction.Lent) ActionButton("已还回来了", ActionKind.Primary) {
                things.markReturned(t.id)
                Toast.makeText(context, "已记为还回来了", Toast.LENGTH_SHORT).show()
            }
            ActionButton("记个新位置", ActionKind.Secondary) { adding = true }
            ActionButton(if (deleteArmed.value) "再点一次删除" else "删除这样东西", ActionKind.Danger) {
                if (deleteArmed.value) { things.deleteThing(t.id); onDismiss() } else deleteArmed.value = true
            }
        }
        Text("删除只删这里的记录，原话还在。", style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(start = 4.dp))
    }

    editing?.let { r ->
        ThingEditSheet("改一下", ThingEdit(r.name, r.action, r.place, r.who, r.owner), onDismiss = { editing = null }) { edit ->
            things.editRecord(r.id, edit); editing = null
        }
    }
    if (adding) {
        ThingEditSheet("记个新位置", ThingEdit(t.name, ThingAction.Placed, null, null, t.owner), onDismiss = { adding = false }) { e ->
            things.add(listOf(ThingDraft(e.name, e.action, e.place, e.who, e.owner, thingId = t.id)), ThingOrigin.Typed, "手动记的")
            adding = false
        }
    }
}

@Composable
private fun HistoryRow(r: ThingRecord, onEdit: () -> Unit, onDelete: () -> Unit) {
    val c = Rs.colors
    val armed = rememberArmed()
    Row(Modifier.fillMaxWidth().springClick(onEdit).padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(r.actionLine(), style = RsType.body, color = c.text)
            Spacer(Modifier.height(3.dp))
            Text(formatStamp(r.at), style = RsType.caption, color = c.textTertiary)
            r.sourceText?.takeIf { it.isNotBlank() && it != "手动记的" }?.let {
                Text("“$it”", style = RsType.caption, color = c.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(if (armed.value) "确认删除" else "删", style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.critical,
            modifier = Modifier.clip(CircleShape).springClick { if (armed.value) onDelete() else armed.value = true }
                .padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

// ---------- 编辑字段（确认、编辑、添加共用） ----------

internal class ThingEditState(e: ThingEdit) {
    var name by mutableStateOf(e.name)
    var action by mutableStateOf(e.action)
    var place by mutableStateOf(e.place.orEmpty())
    var who by mutableStateOf(e.who.orEmpty())
    var owner by mutableStateOf(e.owner)

    val valid: Boolean get() = name.isNotBlank() && when (action) {
        ThingAction.Placed -> place.isNotBlank()
        ThingAction.Lent -> who.isNotBlank()
        else -> true
    }

    fun toEdit() = ThingEdit(
        name.trim(), action,
        place.trim().takeIf { it.isNotEmpty() && action != ThingAction.TakenAway },
        who.trim().takeIf { it.isNotEmpty() && action == ThingAction.Lent },
        owner,
    )
}

/** 常用的排前面：放在、带走了，借和还少 */
private val ACTIONS = listOf(ThingAction.Placed, ThingAction.TakenAway, ThingAction.Lent, ThingAction.Returned)

/** 填的时候上面实时显示“身份证 → 包里”，一眼看出会记成什么 */
@Composable
private fun ThingPreview(s: ThingEditState) {
    val c = Rs.colors
    val color = thingColor()
    val name = s.name.trim().ifEmpty { "东西" }
    val text = androidx.compose.ui.text.buildAnnotatedString {
        append(name)
        val tail = when (s.action) {
            ThingAction.Placed -> s.place.trim().ifEmpty { "放在哪" }.let { " → $it" }
            ThingAction.TakenAway -> " · 带走了"
            ThingAction.Lent -> " · 借给 " + s.who.trim().ifEmpty { "谁" }
            ThingAction.Returned -> " · 还回来了" + (s.place.trim().takeIf { it.isNotEmpty() }?.let { "，放在 $it" } ?: "")
        }
        pushStyle(androidx.compose.ui.text.SpanStyle(color = color, fontWeight = FontWeight.SemiBold)); append(tail); pop()
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(color.copy(alpha = 0.10f)).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(8.dp).height(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Text(text, style = RsType.headline, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 输入框：外面是卡片色就用页面底色，外面是页面底色就用卡片色，始终分得开 */
@Composable
private fun ThingInput(value: String, hint: String, onCard: Boolean, onChange: (String) -> Unit) {
    val c = Rs.colors
    BasicTextField(value, { onChange(it.replace("\n", "")) }, singleLine = true, textStyle = RsType.body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (onCard) c.fill else c.card)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, style = RsType.body, color = c.textTertiary); inner() } })
}

@Composable
private fun FieldLabel(text: String) = Text(text, style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = Rs.colors.textSecondary,
    modifier = Modifier.padding(start = 2.dp, top = 6.dp))

/** onCard=true：放在面板（卡片色底）里；false：放在页面底色的小卡里 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ThingEditFields(s: ThingEditState, onCard: Boolean = true) {
    Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ThingPreview(s)
        FieldLabel("东西")
        ThingInput(s.name, "如：车钥匙", onCard) { s.name = it.take(20) }
        FieldLabel("现在")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ACTIONS.forEach { a -> Choice(a.title, a == s.action) { s.action = a } }
        }
        AnimatedVisibility(s.action == ThingAction.Lent, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel("借给谁")
                ThingInput(s.who, "如：老王", onCard) { s.who = it.take(20) }
            }
        }
        AnimatedVisibility(s.action != ThingAction.TakenAway, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel(when (s.action) {
                    ThingAction.Lent -> "放在哪（可不填）"
                    ThingAction.Returned -> "还回来放哪了（可不填）"
                    else -> "放在哪"
                })
                ThingInput(s.place, "如：鞋柜第二个抽屉", onCard) { s.place = it.take(40) }
            }
        }
    }
}

/** 依据的那句话：浅底小卡，写明什么时候说的 */
@Composable
private fun SaidQuote(text: String, caption: String) {
    val c = Rs.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.fill).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(caption, style = RsType.caption, color = c.textTertiary)
        Spacer(Modifier.height(3.dp))
        Text("“$text”", style = RsType.body, color = c.textSecondary, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun ThingEditSheet(title: String, initial: ThingEdit, onDismiss: () -> Unit, onSave: (ThingEdit) -> Unit) {
    val state = remember { ThingEditState(initial) }
    RsSheet(title, onDismiss = onDismiss) {
        ThingEditFields(state)
        Spacer(Modifier.height(6.dp))
        ActionButton("保存", ActionKind.Primary, enabled = state.valid) { onSave(state.toEdit()) }
    }
}

// ---------- 确认 ----------

/** 首页待确认里的寻物卡：和待办的确认卡同尺寸同底色 */
@Composable
fun ThingPendingCard(r: ThingRecord, things: ThingRepository, modifier: Modifier, onOpen: () -> Unit) {
    val c = Rs.colors
    RsCard(modifier.springClick(onOpen), padding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp), color = c.accentSoft) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ThingTag("寻物", thingColor())
            Spacer(Modifier.weight(1f))
            Text(if (daysSince(r.at) == 0L) formatClock(r.at) else formatStamp(r.at), style = RsType.caption, color = c.textTertiary, maxLines = 1, softWrap = false)
        }
        Spacer(Modifier.height(8.dp))
        val text = r.sameAs?.let { r.cardLine() + "\n是之前的「${it.thingName}」吗？" } ?: r.cardLine()
        Text(text, style = RsType.subhead.copy(lineHeight = 19.sp), color = c.text, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (r.sameAs != null) {
                SmallPill("新的一样", filled = false, modifier = Modifier.weight(1f)) { things.accept(r.id, asNew = true) }
                SmallPill("是", filled = true, modifier = Modifier.weight(1f)) { things.accept(r.id) }
            } else {
                SmallPill("忽略", filled = false, modifier = Modifier.weight(1f)) { things.ignore(r.id) }
                SmallPill("记下", filled = true, modifier = Modifier.weight(1f)) { things.accept(r.id) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThingConfirmSheet(r: ThingRecord, things: ThingRepository, onDismiss: () -> Unit) {
    val initial = remember(r.id) { ThingEdit(r.name, r.action, r.place, r.who, r.owner) }
    val state = remember(r.id) { ThingEditState(initial) }
    var asNew by remember(r.id) { mutableStateOf(false) }
    RsSheet("确认一下", onDismiss = onDismiss) {
        r.sourceText?.takeIf { it.isNotBlank() }?.let { SaidQuote(it, formatStamp(r.at) + " 说的") }
        r.sameAs?.let { q ->
            FieldLabel("是之前的「${q.thingName}」吗？")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("是，更新位置", !asNew) { asNew = false }
                Choice("不是，新的一样", asNew) { asNew = true }
            }
        }
        ThingEditFields(state)
        Spacer(Modifier.height(6.dp))
        ActionButton("记下", ActionKind.Primary, enabled = state.valid) {
            val edit = state.toEdit()
            if (edit == initial) things.accept(r.id, asNew = asNew) else things.accept(r.id, edit, asNew)
            onDismiss()
        }
        ActionButton("不对，忽略", ActionKind.Secondary) { things.ignore(r.id); onDismiss() }
    }
}

/** 寻物页的全部待确认：3 天内的在上，更早的收起 */
@Composable
fun ThingPendingSheet(things: ThingRepository, onDismiss: () -> Unit) {
    val c = Rs.colors
    val pending by remember { things.pending() }.collectAsStateWithLifecycle(initialValue = null)
    var olderOpen by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<ThingRecord?>(null) }
    val ignoreArmed = rememberArmed()
    val list = pending
    LaunchedEffect(list) { if (list != null && list.isEmpty()) onDismiss() }
    val (recent, older) = list.orEmpty().partition { it.recent() }
    RsSheet("待确认", onDismiss = onDismiss, fixedHeight = true) {
        if (recent.isNotEmpty()) PendingGroup(recent, things) { opened = it }
        if (older.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).springClick { olderOpen = !olderOpen }.padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("更早的 ${older.size} 条", style = RsType.subhead, color = c.textSecondary, modifier = Modifier.weight(1f))
                Text(if (olderOpen) "收起" else "›", style = RsType.subhead, color = c.textTertiary)
            }
            AnimatedVisibility(olderOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                PendingGroup(older, things) { opened = it }
            }
        }
        val ids = list.orEmpty().map { it.id }
        if (ids.size > 1) {
            Spacer(Modifier.height(4.dp))
            ActionButton("全部记下", ActionKind.Primary) { things.setAll(ids, RecordStatus.Accepted) }
            ActionButton(if (ignoreArmed.value) "再点一次全部忽略" else "全部忽略", ActionKind.Secondary) {
                if (ignoreArmed.value) things.setAll(ids, RecordStatus.Ignored) else ignoreArmed.value = true
            }
        }
    }
    opened?.let { r -> ThingConfirmSheet(r, things) { opened = null } }
}

@Composable
private fun PendingGroup(list: List<ThingRecord>, things: ThingRepository, onOpen: (ThingRecord) -> Unit) {
    val c = Rs.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.fill).animateContentSize()) {
        list.forEachIndexed { i, r ->
            Row(Modifier.fillMaxWidth().springClick { onOpen(r) }.padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.cardLine(), style = RsType.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    r.sameAs?.let { Text("是之前的「${it.thingName}」吗？", style = RsType.caption, color = thingColor(), maxLines = 1) }
                    Text(formatStamp(r.at) + (r.sourceText?.takeIf { it.isNotBlank() }?.let { " · “$it”" } ?: ""),
                        style = RsType.caption, color = c.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                SmallPill("忽略", filled = false) { things.ignore(r.id) }
                Spacer(Modifier.width(6.dp))
                SmallPill("记下", filled = true) { things.accept(r.id) }
            }
            if (i != list.lastIndex) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
        }
    }
}

// ---------- 手动添加 ----------

/** 寻物选项卡右下角：点按打字、按住说话，拆成几条确认后入库 */
@Composable
fun BoxScope.AddThingHost(things: ThingRepository, bottomPadding: Dp) {
    val context = LocalContext.current
    var result by remember { mutableStateOf<Triple<ThingDrafts, ThingOrigin, String>?>(null) }
    AddHost(bottomPadding, AddWords("记录", "addThingHintShown", "记一下东西放哪", "像平时说话一样写，几样东西写在一起也行。",
        "例如：护照放书柜第二层了，充电宝借给老王了", "生成")) { text, voice ->
        val drafts = runCatching { things.draft(text) }.getOrNull()
        if (drafts == null || drafts.items.isEmpty()) Toast.makeText(context, "没能生成，请再试一次", Toast.LENGTH_SHORT).show()
        else result = Triple(drafts, if (voice) ThingOrigin.Voice else ThingOrigin.Typed, text)
    }
    result?.let { (drafts, origin, source) ->
        ThingDraftSheet(drafts, source, things, onDismiss = { result = null }) { confirmed ->
            things.add(confirmed, origin, source)
            result = null
            Toast.makeText(context, if (confirmed.size > 1) "已记下 ${confirmed.size} 样" else "已记下", Toast.LENGTH_SHORT).show()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThingDraftSheet(drafts: ThingDrafts, source: String, things: ThingRepository, onDismiss: () -> Unit, onConfirm: (List<ThingDraft>) -> Unit) {
    val c = Rs.colors
    // 每条记着模型认出的是哪样已有的东西；用户选“新的一样”或改了名字就不再归过去
    class Card(val draft: ThingDraft) {
        val state = ThingEditState(ThingEdit(draft.name, draft.action, draft.place, draft.who, draft.owner))
        var asNew by mutableStateOf(false)
    }
    val cards = remember(drafts) { mutableStateListOf(*drafts.items.map(::Card).toTypedArray()) }
    val many = cards.size > 1
    RsSheet(if (many) "记下 ${cards.size} 样" else "确认一下",
        if (drafts.parsed) null else "没能自动拆开，已按原话填好，改一下再记下。",
        onDismiss = onDismiss) {
        SaidQuote(source, "你说的")
        cards.forEachIndexed { i, card ->
            val known = card.draft.thingId?.let { id -> remember(id) { things.thing(id) }.collectAsStateWithLifecycle(initialValue = null).value }
            val body: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (many) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("第 ${i + 1} 样", style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.textSecondary, modifier = Modifier.weight(1f))
                        Text("删掉", style = RsType.caption, color = c.critical,
                            modifier = Modifier.clip(CircleShape).springClick { cards.removeAt(i) }.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                    ThingEditFields(card.state, onCard = !many)
                    if (known != null && card.state.name.trim() == card.draft.name) {
                        FieldLabel("记到已有的「${known.name}」？")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Choice("是同一样", !card.asNew) { card.asNew = false }
                            Choice("新的一样", card.asNew) { card.asNew = true }
                        }
                    }
                }
            }
            if (many) Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.fill).padding(14.dp)) { body() } else body()
        }
        Spacer(Modifier.height(6.dp))
        val valid = cards.isNotEmpty() && cards.all { it.state.valid }
        ActionButton(if (many) "记下 ${cards.size} 样" else "记下", ActionKind.Primary, enabled = valid) {
            onConfirm(cards.map { card ->
                val e = card.state.toEdit()
                ThingDraft(e.name, e.action, e.place, e.who, e.owner,
                    thingId = card.draft.thingId.takeIf { !card.asNew && e.name == card.draft.name })
            })
        }
    }
}
