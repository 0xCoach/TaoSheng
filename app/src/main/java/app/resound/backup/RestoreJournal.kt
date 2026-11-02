package app.resound.backup

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 启动前的本机回滚副本。ready 落盘前不改正式数据，回滚用同文件系统 rename，不再复制大库。 */
internal class RestoreJournal(private val context: Context) {
    private val data = File(context.applicationInfo.dataDir)
    private val root = File(context.noBackupFilesDir, "restore-journal")
    private val manifest = AtomicFile(File(root, "ready.json"))
    val ready get() = manifest.baseFile.isFile || File(manifest.baseFile.path + ".bak").isFile
    private val paths = listOf(BackupArchive.DATABASE, BackupArchive.DATABASE + "-wal",
        BackupArchive.DATABASE + "-shm", BackupArchive.DATABASE + "-journal",
        BackupArchive.FEATURES.trimEnd('/'), BackupArchive.PROMPTS.trimEnd('/'), BackupArchive.SKILLS.trimEnd('/'), "shared_prefs")

    fun prepare() {
        check(root.deleteRecursively()); check(root.mkdirs())
        val rows = JSONArray()
        for (path in paths) {
            val source = File(data, path)
            rows.put(JSONObject().put("path", path).put("exists", source.exists()))
            if (source.exists()) check(source.copyRecursively(File(root, "original/$path")))
        }
        val output = manifest.startWrite()
        try { output.write(rows.toString().toByteArray()); manifest.finishWrite(output) }
        catch (e: Exception) { manifest.failWrite(output); throw e }
    }

    fun rollback() {
        if (!ready) { clear(); return }
        val currentPrefs = File(data, "shared_prefs").listFiles().orEmpty()
            .filter { it.extension == "xml" }.map { it.nameWithoutExtension }.toSet()
        val rows = JSONArray(manifest.openRead().bufferedReader().use { it.readText() })
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val path = row.getString("path")
            require(path in paths)
            val saved = File(root, "original/$path")
            val target = File(data, path)
            if (!row.getBoolean("exists")) check(target.deleteRecursively())
            else if (saved.exists()) {
                check(target.deleteRecursively())
                target.parentFile!!.mkdirs()
                check(saved.renameTo(target))
            } else check(target.exists()) // 上一次回滚在 rename 后中断，已还原的根目录不重复删除。
        }
        // 同进程失败恢复时还要纠正 SharedPreferences 的内存缓存。
        val original = File(data, "shared_prefs").listFiles().orEmpty().filter { it.extension == "xml" }
        val names = original.map { it.nameWithoutExtension }.toSet()
        (currentPrefs - names).forEach { check(context.getSharedPreferences(it, 0).edit().clear().commit()) }
        original.forEach { BackupRestore.restorePreferences(context, it, replace = true) }
    }

    fun clear() { check(root.deleteRecursively()) }
}
