package app.resound.ui.preview

import app.resound.things.RecordStatus
import app.resound.things.SameAsQuestion
import app.resound.things.Thing
import app.resound.things.ThingAction
import app.resound.things.ThingDraft
import app.resound.things.ThingDrafts
import app.resound.things.ThingEdit
import app.resound.things.ThingOrigin
import app.resound.things.ThingOwner
import app.resound.things.ThingRecord
import app.resound.things.ThingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** 寻物后端接上前，界面开发用的假数据；改动只在内存里生效 */
object FakeThings : ThingRepository {
    private const val DAY = 86_400_000L
    private val now = System.currentTimeMillis()
    private var nextId = 100L

    private data class Row(val id: Long, val name: String, val aliases: List<String>, val owner: ThingOwner)

    private val rows = MutableStateFlow(listOf(
        Row(1, "钥匙", listOf("家门钥匙"), ThingOwner.Mine),
        Row(2, "充电宝", emptyList(), ThingOwner.Mine),
        Row(3, "护照", emptyList(), ThingOwner.Mine),
        Row(4, "水杯", emptyList(), ThingOwner.Family),
    ))

    private fun rec(id: Long, thingId: Long?, name: String, action: ThingAction, place: String?, who: String?, at: Long,
                    text: String?, status: RecordStatus = RecordStatus.Accepted, owner: ThingOwner = ThingOwner.Mine, sameAs: SameAsQuestion? = null) =
        ThingRecord(id, thingId, name, action, place, who, owner, at, null, text, status, ThingOrigin.Live, sameAs = sameAs)

    private val records = MutableStateFlow(listOf(
        rec(1, 1, "钥匙", ThingAction.Placed, "玄关托盘", null, now - 2 * DAY, "钥匙先放托盘上了"),
        rec(2, 1, "钥匙", ThingAction.Placed, "鞋柜第二个抽屉", null, now - 3_600_000L, "钥匙我搁鞋柜第二个抽屉了啊"),
        rec(3, 2, "充电宝", ThingAction.Lent, null, "老王", now - 9 * DAY, "充电宝你先拿去用吧，老王"),
        rec(4, 3, "护照", ThingAction.Placed, "书柜第二层", null, now - 40 * DAY, "护照我塞书柜第二层那个文件袋里了"),
        rec(5, 4, "水杯", ThingAction.Placed, "餐桌", null, now - 2 * DAY, "你杯子在餐桌上", owner = ThingOwner.Family),
        rec(6, 4, "水杯", ThingAction.TakenAway, null, null, now - DAY, "杯子我带走了啊", owner = ThingOwner.Family),
        rec(7, null, "雨伞", ThingAction.Placed, "门后挂钩", null, now - 1_200_000L, "伞我挂门后了", RecordStatus.Pending),
        rec(8, null, "车钥匙", ThingAction.Placed, "包里", null, now - 600_000L, "车钥匙在我包里", RecordStatus.Pending,
            sameAs = SameAsQuestion(1, "钥匙")),
    ))

    private val auto = MutableStateFlow(true)
    override val autoDetect: StateFlow<Boolean> = auto
    override fun setAutoDetect(on: Boolean) { auto.value = on }

    private val all: Flow<List<Thing>> = combine(rows, records) { rs, recs ->
        rs.mapNotNull { r ->
            val mine = recs.filter { it.thingId == r.id && it.status == RecordStatus.Accepted }.sortedByDescending { it.at }
            val latest = mine.firstOrNull() ?: return@mapNotNull null
            val placed = mine.firstOrNull { it.action == ThingAction.Placed || (it.action != ThingAction.TakenAway && it.place != null) }
            Thing(r.id, r.name, r.aliases, r.owner, latest, placed?.place, placed?.at, mine.size,
                recs.any { it.status == RecordStatus.Pending && (it.thingId == r.id || it.sameAs?.thingId == r.id) })
        }.sortedByDescending { it.latest.at }
    }

    override fun things(): Flow<List<Thing>> = all

    override fun search(query: String): Flow<List<Thing>> {
        val q = query.trim()
        if (q.isEmpty()) return all
        return all.map { list ->
            list.filter { t -> (listOf(t.name, t.lastPlace, t.latest.place, t.latest.who) + t.aliases).any { it?.contains(q) == true } }
        }
    }

    override fun thing(id: Long): Flow<Thing?> = all.map { list -> list.firstOrNull { it.id == id } }

    override fun history(thingId: Long): Flow<List<ThingRecord>> =
        records.map { list -> list.filter { it.thingId == thingId && it.status == RecordStatus.Accepted }.sortedByDescending { it.at } }

    override fun pending(): Flow<List<ThingRecord>> =
        records.map { list -> list.filter { it.status == RecordStatus.Pending }.sortedByDescending { it.at } }

    private fun thingFor(name: String, owner: ThingOwner): Long =
        rows.value.firstOrNull { it.name == name || name in it.aliases }?.id
            ?: (nextId++).also { rows.value = rows.value + Row(it, name, emptyList(), owner) }

    override fun accept(recordId: Long, edit: ThingEdit?, asNew: Boolean) {
        records.value = records.value.map { r ->
            if (r.id != recordId) return@map r
            val e = edit ?: ThingEdit(r.name, r.action, r.place, r.who, r.owner)
            val target = when {
                r.sameAs != null && !asNew && edit == null -> r.sameAs.thingId
                r.sameAs != null && asNew -> (nextId++).also { rows.value = rows.value + Row(it, e.name, emptyList(), e.owner) }
                r.thingId != null && edit == null -> r.thingId
                else -> thingFor(e.name, e.owner)
            }
            r.copy(thingId = target, name = e.name, action = e.action, place = e.place, who = e.who, owner = e.owner,
                status = RecordStatus.Accepted, sameAs = null)
        }
    }

    override fun ignore(recordId: Long) {
        records.value = records.value.map { if (it.id == recordId) it.copy(status = RecordStatus.Ignored) else it }
    }

    override fun setAll(ids: List<Long>, status: RecordStatus) {
        ids.forEach { if (status == RecordStatus.Accepted) accept(it) else ignore(it) }
    }

    override suspend fun draft(text: String): ThingDrafts {
        kotlinx.coroutines.delay(600)
        return ThingDrafts(listOf(ThingDraft(text.take(12), ThingAction.Placed, null, null)), parsed = false)
    }

    override fun add(drafts: List<ThingDraft>, origin: ThingOrigin, sourceText: String) {
        val added = drafts.map { d ->
            val target = d.thingId ?: thingFor(d.name, d.owner)
            ThingRecord(nextId++, target, d.name, d.action, d.place, d.who, d.owner, System.currentTimeMillis(), null, sourceText,
                RecordStatus.Accepted, origin)
        }
        records.value = records.value + added
    }

    override fun markReturned(thingId: Long, place: String?) {
        val r = rows.value.firstOrNull { it.id == thingId } ?: return
        records.value = records.value + ThingRecord(nextId++, thingId, r.name, ThingAction.Returned, place, null, r.owner,
            System.currentTimeMillis(), null, null, RecordStatus.Accepted, ThingOrigin.Typed)
    }

    override fun rename(thingId: Long, name: String) {
        rows.value = rows.value.map { if (it.id == thingId) it.copy(name = name, aliases = (it.aliases + it.name).distinct()) else it }
    }

    override fun setOwner(thingId: Long, owner: ThingOwner) {
        rows.value = rows.value.map { if (it.id == thingId) it.copy(owner = owner) else it }
    }

    override fun editRecord(recordId: Long, edit: ThingEdit) {
        records.value = records.value.map {
            if (it.id == recordId) it.copy(name = edit.name, action = edit.action, place = edit.place, who = edit.who, owner = edit.owner) else it
        }
    }

    override fun deleteRecord(recordId: Long) {
        val thingId = records.value.firstOrNull { it.id == recordId }?.thingId
        records.value = records.value.filter { it.id != recordId }
        if (thingId != null && records.value.none { it.thingId == thingId && it.status == RecordStatus.Accepted }) deleteThing(thingId)
    }

    override fun deleteThing(thingId: Long) {
        rows.value = rows.value.filter { it.id != thingId }
        records.value = records.value.filter { it.thingId != thingId }
    }
}
