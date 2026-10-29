package app.resound.update

import android.content.Context
import android.util.Xml
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.resound.backup.BackupArchive
import app.resound.backup.BackupRepository
import app.resound.backup.BackupScheduler
import app.resound.data.CaptureDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream

internal class PreUpdateBackup(
    private val context: Context,
    private val database: CaptureDatabase,
    private val backup: BackupRepository,
    private val log: (String) -> Unit,
) {
    /** [progress]：给更新页看的步骤和整体进度。本机快照占前 30%，其余按要做的备份平分 */
    suspend fun run(progress: (String, Float) -> Unit = { _, _ -> }) {
        val start = System.nanoTime()
        progress("正在保存本机数据", 0.05f)
        try { snapshot(); log("backup_private ms=${elapsed(start)} result=ok") }
        catch (e: Exception) { log("backup_private ms=${elapsed(start)} failure=${reason(e)}"); throw e }
        val local = backup.local.value.folder != null
        val cloud = backup.state.value.account != null && backup.state.value.hasPassword
        val total = listOf(local, cloud).count { it }
        if (total == 0) { progress("备份完成", 1f); return }
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        fun waiting() = listOfNotNull("本机文件夹".takeIf { local }, "坚果云".takeIf { cloud }).joinToString("和")
        progress("正在备份到${waiting()}（最多 3 分钟）", 0.3f)
        // 两种现有备份均先触发，再一起等；总共最多三分钟，不叠加为六分钟。
        val completed = withTimeoutOrNull(3 * 60 * 1000L) {
            coroutineScope {
                val jobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()
                fun finished(name: String) {
                    val n = done.incrementAndGet()
                    progress(if (n < total) "${name}已备份，还在备份另一处" else "备份完成", 0.3f + 0.7f * n / total)
                }
                if (local) jobs += async { optional(true); finished("本机文件夹") }
                if (cloud) jobs += async { optional(false); finished("坚果云") }
                jobs.awaitAll()
            }
            true
        }
        if (completed == null) log("backup_optional ms=${elapsed(start)} failure=timeout")
    }

    internal fun snapshot() {
        val stage = File(context.filesDir, "pre-update-staging")
        val target = File(context.filesDir, "pre-update")
        val previous = File(context.filesDir, "pre-update-previous")
        // 兼容卡 051 的单份目录，以及迁移中被杀后留下的旧目录。
        val legacy = File(context.filesDir, "pre-update-legacy")
        if (File(target, "databases").exists()) {
            check(!legacy.exists())
            check(target.renameTo(legacy))
        }
        check(target.isDirectory || target.mkdirs())
        listOf(previous, legacy).filter { it.exists() }.forEach { old ->
            val latest = target.listFiles().orEmpty().maxOfOrNull { it.name.toLongOrNull() ?: 0 } ?: 0
            val saved = File(target, "%019d".format(maxOf(old.lastModified(), latest + 1)))
            check(old.renameTo(saved))
        }
        check(stage.deleteRecursively()); check(stage.mkdirs())
        try {
            database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use {
                check(it.moveToFirst() && it.getInt(0) == 0) { "checkpoint_busy" }
            }
            val db = File(stage, "databases/capture.db").apply { check(parentFile!!.mkdirs()) }
            // 复用已有的事务内主库 + WAL 复制及副本检查点，防止检查点之后的新写入造成不一致。
            BackupArchive.snapshot(database, db)
            check(db.length() > 0)
            val prefs = File(context.applicationInfo.dataDir, "shared_prefs")
            val output = File(stage, "shared_prefs").apply { check(mkdirs()) }
            if (prefs.exists()) {
                val files = checkNotNull(prefs.listFiles())
                files.filter { it.name.endsWith(".xml") || it.name.endsWith(".xml.bak") }
                    .map { it.name.removeSuffix(".bak").removeSuffix(".xml") }.distinct().forEach { name ->
                        // 从内存快照写出原生 SharedPreferences XML，包含尚未落盘的 apply，且避开复制时的原子换名竞态。
                        writePreferences(context.getSharedPreferences(name, Context.MODE_PRIVATE).all, File(output, "$name.xml"))
                    }
            }
            File(stage, "snapshot.txt").writeText("涛声更新前备份\nat=${System.currentTimeMillis()}\ndatabaseVersion=${CaptureDatabase.VERSION}\n")
            val latest = target.listFiles().orEmpty().filter { it.isDirectory }.maxOfOrNull { it.name.toLongOrNull() ?: 0 } ?: 0
            val published = File(target, "%019d".format(maxOf(System.currentTimeMillis(), latest + 1)))
            check(stage.renameTo(published)) { "snapshot_publish_failed" }
            target.listFiles().orEmpty().filter { it.isDirectory }.sortedByDescending { it.name }
                .drop(3).forEach { check(it.deleteRecursively()) }
        } finally { stage.deleteRecursively() }
    }

    private suspend fun optional(local: Boolean) {
        val start = System.nanoTime()
        try {
            val manager = WorkManager.getInstance(context)
            val name = if (local) BackupScheduler.LOCAL_IMMEDIATE else BackupScheduler.IMMEDIATE
            val before = manager.getWorkInfosForUniqueWorkFlow(name).first()
            val known = before.map { it.id }.toSet()
            val unfinished = before.filter { !it.state.isFinished }.map { it.id }.toSet()
            val oldAt = if (local) backup.local.value.lastAt else backup.state.value.lastBackupAt
            if (local) backup.backupLocalNow() else backup.backupNow()
            // KEEP 可能复用已有任务；只等待本次新任务或原本尚未结束的任务，不能把旧 SUCCEEDED 当本次成功。
            val results = manager.getWorkInfosForUniqueWorkFlow(name).first { list ->
                list.any { (it.id !in known || it.id in unfinished) &&
                    (it.state.isFinished || it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0) }
            }
            val result = results.first { it.id !in known || it.id in unfinished }
            val error = if (local) backup.local.value.lastError else backup.state.value.lastError
            val at = if (local) backup.local.value.lastAt else backup.state.value.lastBackupAt
            val ok = result.state == WorkInfo.State.SUCCEEDED && error == null && at != null && at != oldAt
            log("backup_optional local=$local ms=${elapsed(start)} result=${if (ok) "ok" else "failed"}")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { log("backup_optional local=$local ms=${elapsed(start)} failure=${reason(e)}") }
    }

    private fun writePreferences(values: Map<String, *>, target: File) {
        FileOutputStream(target).use { output ->
            val xml = Xml.newSerializer().apply { setOutput(output, "UTF-8"); startDocument("UTF-8", true); startTag(null, "map") }
            for ((name, value) in values) {
                val tag = when (value) {
                    is Boolean -> "boolean"; is Int -> "int"; is Long -> "long"; is Float -> "float"
                    is String -> "string"; is Set<*> -> "set"; else -> error("unsupported_preference")
                }
                xml.startTag(null, tag).attribute(null, "name", name)
                when (value) {
                    is String -> xml.text(value)
                    is Set<*> -> value.forEach { xml.startTag(null, "string").text(it as String).endTag(null, "string") }
                    else -> xml.attribute(null, "value", value.toString())
                }
                xml.endTag(null, tag)
            }
            xml.endTag(null, "map"); xml.endDocument(); xml.flush(); output.fd.sync()
        }
    }
}
