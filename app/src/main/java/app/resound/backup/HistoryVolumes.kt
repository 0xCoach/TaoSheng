package app.resound.backup

import android.content.Context
import app.resound.data.CaptureDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 不可变数据对象 + 最后发布的恢复点。旧恢复点不依赖“最新状态”文件。 */
internal object HistoryVolumes {
    private const val GRACE = 24 * 60 * 60 * 1000L
    private data class Ref(val logical: String, val path: String, val bytes: Long, val hash: String)
    private data class Point(val name: String, val at: Long, val bytes: Long, val uploaded: Long,
        val days: Int, val encrypted: Boolean, val refs: List<Ref>) {
        fun summary() = BackupSummary(days,bytes,at,uploaded,true,encrypted)
    }
    private fun metadata(path: String): RemoteBackup? {
        if (!historyPath(path)) return null
        val parts = path.substringAfter("resound-point-").removeSuffix(".json").split('-')
        return RemoteBackup(path,parts[0].toLong(),parts[1].toLong(),parts[3]=="e",full=false)
    }
    fun list(store: VolumeStore): List<RemoteBackup> = store.files("history").mapNotNull { metadata(it.path) }
        .distinctBy { it.name }.sortedWith(compareByDescending<RemoteBackup> { it.at }.thenByDescending { it.name })
    private fun load(store: VolumeStore, name: String, work: File): Point {
        val meta = requireNotNull(metadata(name))
        val file = File(work,"point-${UUID.randomUUID()}")
        if (!store.read(name,file)) throw BackupFailure("这个历史恢复点已不存在，请刷新列表")
        require(file.length() in 1..8_000_000)
        val obj = JSONObject(file.readText())
        require(obj.getInt("format")==39 && obj.getLong("at")==meta.at && obj.getLong("bytes")==meta.bytes && obj.getBoolean("encrypted")==meta.encrypted)
        val array = obj.getJSONArray("refs"); require(array.length() in 1..100_000)
        val refs = (0 until array.length()).map { i -> array.getJSONObject(i).let {
            Ref(it.getString("logical"),it.getString("path"),it.getLong("bytes"),it.getString("sha256"))
        } }
        require(refs.map { it.logical }.distinct().size==refs.size)
        refs.forEach { require(legacyVolumePath(it.logical) && objectPath(it.path) && it.path=="objects/${it.hash}.bin" && it.bytes in 1..2L*1024*1024*1024) }
        require(refs.any { it.logical==stateName(meta.encrypted) })
        require(refs.distinctBy { it.path }.sumOf { it.bytes }==meta.bytes)
        val uploaded = obj.getLong("uploaded"); val days = obj.getInt("days")
        require(uploaded>=0 && days>=0)
        return Point(name,meta.at,meta.bytes,uploaded+file.length(),days,meta.encrypted,refs)
    }
    private fun stateName(encrypted: Boolean) = "resound-state.zip" + if(encrypted) ".enc" else ""

    /** 复用现有分卷生成/恢复逻辑，把逻辑文件名映射到不可变对象；所有写入先在私有临时目录。 */
    private class View(private val store: VolumeStore, refs: List<Ref>, private val work: File,
        private val legacyFallback: Boolean = false) : VolumeStore {
        val entries = refs.associateBy { it.logical }.toMutableMap()
        val staged = mutableMapOf<String,File>()
        private val deleted = mutableSetOf<String>()
        override fun read(path: String, target: File): Boolean {
            require(legacyVolumePath(path))
            if(path in deleted)return false
            val ref = entries[path] ?: return legacyFallback && store.read(path,target)
            val local = staged[ref.path]
            if(local!=null)local.copyTo(target,true)
            else if(!store.read(ref.path,target))throw BackupFailure("历史备份数据缺失，未恢复任何内容")
            if(target.length()!=ref.bytes || BackupArchive.sha(target)!=ref.hash)throw BackupFailure("历史备份数据校验失败，未恢复任何内容")
            if(path.endsWith(".enc") && !BackupEncryption.isEncrypted(target))throw BackupFailure("加密备份格式不正确")
            return true
        }
        override fun exists(path: String): Boolean {
            if(path in deleted)return false
            val ref = entries[path] ?: return legacyFallback && store.exists(path)
            return ref.path in staged || store.exists(ref.path)
        }
        override fun put(path: String, source: File) {
            require(legacyVolumePath(path))
            val hash = BackupArchive.sha(source); val objectName = "objects/$hash.bin"
            val file = File(work,hash)
            if(!file.exists())source.copyTo(file)
            staged[objectName]=file
            entries[path]=Ref(path,objectName,file.length(),hash);deleted.remove(path)
        }
        fun freeze(path: String): Ref {
            entries[path]?.let { return it }
            val file = File(work,"freeze-${UUID.randomUUID()}")
            check(read(path,file));put(path,file);file.delete()
            return entries.getValue(path)
        }
        override fun delete(path: String) { entries.remove(path);deleted.add(path) }
        override fun copy(from: String, to: String) { entries[to]=freeze(from).copy(logical=to);deleted.remove(to) }
        override fun legacy() = emptyList<String>()
    }
    fun latest(store: VolumeStore, work: File): VolumeStore {
        val point = list(store).firstOrNull() ?: return store
        return selected(store,point.name,work)
    }
    fun selected(store: VolumeStore, name: String, work: File): VolumeStore = View(store,load(store,name,work).refs,work)
    fun summary(store: VolumeStore, work: File, passphrase: String?): BackupSummary? {
        val point = list(store).firstOrNull()
        return if(point!=null)load(store,point.name,work).summary() else DayVolumes.readState(store,work,passphrase)?.summary()
    }
    fun sync(context: Context, database: CaptureDatabase, store: VolumeStore, work: File,
        passphrase: String?, keep: Int = 7, now: Long = System.currentTimeMillis()): BackupSummary {
        require(keep in setOf(1,3,7,30));work.mkdirs()
        val previous = list(store)
        val head = previous.firstOrNull()?.let { load(store,it.name,work) }
        val time = maxOf(now,(head?.at ?: 0L)+1)
        val view = View(store,head?.refs.orEmpty(),File(work,"objects").apply { mkdirs() },head==null)
        val summary = DayVolumes.sync(context,database,view,File(work,"generate"),passphrase,time)
        val state = requireNotNull(DayVolumes.readState(view,work,passphrase))
        val names = mutableListOf(stateName(summary.encrypted))
        for(key in listOf("days","features")) {
            val array = state.manifest.getJSONArray(key)
            repeat(array.length()) { names += array.getJSONObject(it).getString("path") }
        }
        val refs = names.distinct().map(view::freeze)
        var uploaded = 0L
        for(ref in refs.distinctBy { it.path }) {
            val source = view.staged[ref.path]
            if(source!=null && !store.exists(ref.path)) { store.put(ref.path,source);uploaded+=source.length() }
        }
        val bytes = refs.distinctBy { it.path }.sumOf { it.bytes }
        val id = UUID.randomUUID().toString().replace("-","").take(12)
        val name = "history/resound-point-$time-$bytes-$id-${if(summary.encrypted) "e" else "p"}.json"
        val obj = JSONObject().put("format",39).put("at",time).put("bytes",bytes).put("uploaded",uploaded)
            .put("days",summary.days).put("encrypted",summary.encrypted).put("refs",JSONArray().apply {
                refs.forEach { put(JSONObject().put("logical",it.logical).put("path",it.path).put("bytes",it.bytes).put("sha256",it.hash)) }
            })
        val file = File(work,"point.json").apply { writeText(obj.toString()) }
        // 唯一名称最后发布；失败时旧恢复点和其引用的数据始终不变。
        store.put(name,file)
        val current = RemoteBackup(name,time,bytes,summary.encrypted,false)
        try { cleanup(store,(listOf(current)+previous).distinctBy { it.name },keep,work,now) }
        catch(e: Exception) { throw BackupFailure("备份已保存，但旧备份清理未完成，下次备份时重试",true) }
        return BackupSummary(summary.days,bytes,time,uploaded+file.length(),true,summary.encrypted)
    }
    private fun cleanup(store: VolumeStore, known: List<RemoteBackup>, keep: Int, work: File, now: Long) {
        val points = (known+list(store)).distinctBy { it.name }.sortedByDescending { it.at }
        val retained = points.take(keep)
        // 先读完保留点，避免清单损坏时误清理它引用的数据。
        val live = retained.flatMap { load(store,it.name,work).refs }.map { it.path }.toSet()
        points.drop(keep).forEach { store.delete(it.name) }
        // 给未完成上传和中断重试留一天宽限；下次备份自动清掉不再被引用的旧对象。
        store.files("objects").filter { it.path !in live && objectPath(it.path) && it.modifiedAt>0 && it.modifiedAt<=now-GRACE }
            .forEach { store.delete(it.path) }
        // 用户明确要求删除旧格式；只清理命名规则认可的 ZIP，其他文件不动。
        store.legacy().filter { BackupNames.metadata(it)!=null }.forEach(store::delete)
    }
}
