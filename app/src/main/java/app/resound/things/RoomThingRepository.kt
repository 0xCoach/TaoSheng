package app.resound.things

import androidx.room.withTransaction
import app.resound.data.CaptureDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONArray

internal fun aliases(json: String): List<String> = runCatching { JSONArray(json).let { a ->
    (0 until a.length()).map { a.getString(it) }
} }.getOrDefault(emptyList())
internal fun ThingRecordRow.model(names: Map<Long, String>) = ThingRecord(id, thingId, name,
    ThingAction.valueOf(action), place, who, ThingOwner.valueOf(owner), at, sourceLineId, sourceText,
    RecordStatus.valueOf(status), ThingOrigin.valueOf(origin), explicit,
    sameAsId?.let { id -> names[id]?.let { SameAsQuestion(id, it) } })

internal fun things(rows: List<ThingRow>, records: List<ThingRecordRow>): List<Thing> {
    val names = rows.associate { it.id to it.name }
    return rows.mapNotNull { t ->
        val history = records.filter { it.thingId == t.id && it.status == "Accepted" }.sortedWith(compareByDescending<ThingRecordRow> { it.at }.thenByDescending { it.id })
        val latest = history.firstOrNull() ?: return@mapNotNull null
        val lastPlace = history.firstOrNull { it.action == "Placed" && it.place != null }
        Thing(t.id, t.name, aliases(t.aliasesJson), ThingOwner.valueOf(t.owner), latest.model(names), lastPlace?.place,
            lastPlace?.at, history.size, records.any { it.status == "Pending" && (it.thingId == t.id || it.sameAsId == t.id) })
    }.sortedWith(compareByDescending<Thing> { it.latest.at }.thenByDescending { it.latest.id })
}

internal class RoomThingRepository(private val db: CaptureDatabase, scope: CoroutineScope,
    private val parseDraft: suspend (String) -> ThingDrafts,
    private val now: () -> Long = System::currentTimeMillis,
    private val problem: (String) -> Unit = {},
) : ThingRepository {
    internal val dao = db.things()
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    init { scope.launch { for (command in commands) try { command() }
        catch (e: CancellationException) { throw e } catch (_: Exception) { problem("寻物保存失败，请重试") } } }
    private fun change(block: suspend () -> Unit) { check(commands.trySend { db.withTransaction { block() } }.isSuccess) }
    internal suspend fun flush() { val done = CompletableDeferred<Unit>(); commands.send { done.complete(Unit) }; done.await() }
    override val autoDetect = dao.observeState().map { it?.enabled ?: true }.stateIn(scope, SharingStarted.Eagerly, true)
    override fun setAutoDetect(on: Boolean) = change {
        val state = dao.state() ?: ThingWatchState()
        if (state.enabled != on) {
            // 关着时的所有句子都跳过；即便期间没有一次转写回调，重新打开也不补旧句。
            dao.state(state.copy(enabled = on, watermark = maxOf(state.watermark, dao.maxLineId()),
                revision = state.revision + 1, failures = 0, failedEnd = null))
        }
    }
    override fun things(): Flow<List<Thing>> = combine(dao.observeThings(), dao.observeRecords(), ::things)
    override fun search(query: String): Flow<List<Thing>> {
        if (query.isBlank()) return things()
        val pattern = "%" + query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        return combine(things(), dao.matching(pattern)) { values, ids -> values.filter { it.id in ids } }
    }
    override fun thing(id: Long) = things().map { it.firstOrNull { t -> t.id == id } }
    override fun history(thingId: Long) = combine(dao.observeThings(), dao.observeRecords()) { ts, rs ->
        val names = ts.associate { it.id to it.name }
        rs.filter { it.thingId == thingId && it.status == "Accepted" }.map { it.model(names) }
    }
    override fun pending() = combine(dao.observeThings(), dao.observeRecords()) { ts, rs ->
        val names = ts.associate { it.id to it.name }; rs.filter { it.status == "Pending" }.map { it.model(names) }
    }
    private fun clean(edit: ThingEdit): ThingEdit {
        val result = edit.copy(name = edit.name.trim(), place = edit.place?.trim()?.takeIf { it.isNotEmpty() }, who = edit.who?.trim()?.takeIf { it.isNotEmpty() })
        require(result.name.isNotBlank())
        require(result.action != ThingAction.Placed || result.place != null) { "请补充放在哪里" }
        require(result.action != ThingAction.Lent || result.who != null) { "请补充借给谁" }
        return result
    }
    private fun ThingRecordRow.edited(edit: ThingEdit) = copy(name = edit.name, action = edit.action.name,
        place = edit.place, who = edit.who, owner = edit.owner.name)
    private suspend fun findName(name: String) = dao.allThings().firstOrNull { it.name.equals(name, true) || aliases(it.aliasesJson).any { a -> a.equals(name, true) } }
    private suspend fun attach(row: ThingRecordRow, target: ThingRow?): Long {
        val names = (target?.let { aliases(it.aliasesJson) + it.name }.orEmpty() + aliases(row.aliasesJson) + row.name).distinct()
        if (target == null) return dao.insert(ThingRow(name = row.name, aliasesJson = JSONArray(names.filter { it != row.name }).toString(),
            owner = row.owner, createdAt = now(), updatedAt = now()))
        dao.update(target.copy(aliasesJson = JSONArray(names.filter { it != target.name }).toString(), owner = row.owner, updatedAt = now()))
        return target.id
    }
    private suspend fun acceptRow(recordId: Long, edit: ThingEdit?, asNew: Boolean) {
        val old = dao.record(recordId)?.takeIf { it.status == "Pending" } ?: return
        val row = if (edit == null) old.edited(clean(ThingEdit(old.name, ThingAction.valueOf(old.action), old.place, old.who, ThingOwner.valueOf(old.owner)))) else old.edited(edit)
        val target = when {
            row.name != old.name -> findName(row.name)
            old.sameAsId != null -> if (asNew) null else dao.thing(old.sameAsId)
            old.thingId != null -> dao.thing(old.thingId)
            else -> null
        }
        val id = attach(row, target)
        dao.updateRecord(row.copy(thingId = id, sameAsId = null, status = "Accepted", acceptedAt = now()))
    }
    override fun accept(recordId: Long, edit: ThingEdit?, asNew: Boolean) {
        val checked = edit?.let(::clean); change { acceptRow(recordId, checked, asNew) }
    }
    override fun ignore(recordId: Long) = setAll(listOf(recordId), RecordStatus.Ignored)
    override fun setAll(ids: List<Long>, status: RecordStatus) {
        require(status != RecordStatus.Pending)
        change { ids.distinct().forEach { id ->
            if (status == RecordStatus.Accepted) acceptRow(id, null, false)
            else dao.record(id)?.takeIf { it.status == "Pending" }?.let { dao.updateRecord(it.copy(status = "Ignored")) }
        } }
    }
    override suspend fun draft(text: String): ThingDrafts {
        val fallback = ThingDrafts(listOf(ThingDraft(text.trim().take(12), ThingAction.Placed, null, null)), false)
        return try { withTimeoutOrNull(20_000) { parseDraft(text).takeIf { it.items.isNotEmpty() } ?: fallback } ?: fallback }
        catch (e: CancellationException) { throw e } catch (_: Exception) { fallback }
    }
    override fun add(drafts: List<ThingDraft>, origin: ThingOrigin, sourceText: String) {
        require(origin != ThingOrigin.Live && drafts.isNotEmpty())
        val checked = drafts.map { it to clean(ThingEdit(it.name, it.action, it.place, it.who, it.owner)) }
        val at = now()
        change { checked.forEach { (d, e) ->
            val row = ThingRecordRow(name = e.name, action = e.action.name, place = e.place, who = e.who, owner = e.owner.name,
                at = at, sourceLineId = null, sourceText = sourceText, status = "Accepted", origin = origin.name, createdAt = at, acceptedAt = at)
            val target = d.thingId?.let { dao.thing(it) } ?: findName(e.name)
            dao.insertRecord(row.copy(thingId = attach(row, target)))
        } }
    }
    override fun markReturned(thingId: Long, place: String?) = change {
        val thing = dao.thing(thingId) ?: return@change
        val latest = dao.allRecords().firstOrNull { it.thingId == thingId && it.status == "Accepted" }
        if (latest?.action != "Lent") return@change
        dao.insertRecord(ThingRecordRow(thingId = thingId, name = thing.name, action = "Returned", place = place?.trim()?.takeIf { it.isNotEmpty() }, who = null,
            owner = thing.owner, at = now(), sourceLineId = null, sourceText = null, status = "Accepted", origin = "Typed", createdAt = now(), acceptedAt = now()))
        dao.update(thing.copy(updatedAt = now()))
    }
    override fun rename(thingId: Long, name: String) {
        require(name.isNotBlank()); change { dao.thing(thingId)?.let { t -> dao.update(t.copy(name = name.trim(),
            aliasesJson = JSONArray((aliases(t.aliasesJson) + t.name).distinct().filter { it != name.trim() }).toString(), updatedAt = now())) } }
    }
    override fun setOwner(thingId: Long, owner: ThingOwner) = change { dao.thing(thingId)?.let { dao.update(it.copy(owner = owner.name, updatedAt = now())) } }
    override fun editRecord(recordId: Long, edit: ThingEdit) {
        val checked = clean(edit)
        change {
            val old = dao.record(recordId)?.takeIf { it.status == "Accepted" } ?: return@change
            val row = old.edited(checked)
            val target = if (old.name != row.name) findName(row.name) else old.thingId?.let { dao.thing(it) }
            dao.updateRecord(row.copy(thingId = attach(row, target)))
            old.thingId?.let { removeEmpty(it) }
        }
    }
    private suspend fun removeEmpty(id: Long) {
        if (dao.allRecords().none { it.thingId == id && it.status == "Accepted" }) removeThing(id)
    }
    private suspend fun removeThing(id: Long) {
        dao.deleteRecords(id); dao.clearSameAs(id)
        dao.thing(id)?.let { dao.update(it.copy(deleted = true, updatedAt = now())) }
    }
    override fun deleteRecord(recordId: Long) = change {
        val old = dao.record(recordId) ?: return@change
        dao.deleteRecord(recordId); old.thingId?.let { removeEmpty(it) }
    }
    override fun deleteThing(thingId: Long) = change { removeThing(thingId) }

    /** 调用者持有 Room 事务。通知只对实际新增/更新且显式请求的记录发出。 */
    internal suspend fun propose(proposal: ThingProposal): ThingRecordRow? {
        val row = proposal.row
        val ts = dao.allThings(); val rs = dao.allRecords()
        val known = things(ts, rs)
        val target = row.thingId?.let { id -> known.firstOrNull { it.id == id } }
        if (row.action == "Placed" && known.any { t ->
                (t.id == target?.id || t.name == row.name) && t.latest.action == ThingAction.Placed && t.latest.place == row.place
            }) return null
        val duplicate = rs.firstOrNull { it.status == "Pending" && it.name == row.name && it.action == row.action &&
            kotlin.math.abs(row.at - it.at) <= 2 * 60 * 60_000L }
        val validIds = ts.map { it.id }.toSet()
        // 延迟到达的旧原话不反向覆盖已有较新的待确认事实。
        if (duplicate != null && duplicate.at > row.at) return null
        val fresh = row.copy(id = duplicate?.id ?: 0, thingId = row.thingId?.takeIf { it in validIds },
            sameAsId = row.sameAsId?.takeIf { it in validIds }, createdAt = duplicate?.createdAt ?: now())
        return if (duplicate == null) fresh.copy(id = dao.insertRecord(fresh)) else fresh.also { dao.updateRecord(it) }
    }
}
