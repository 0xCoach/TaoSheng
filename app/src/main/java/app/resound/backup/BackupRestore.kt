package app.resound.backup

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.AtomicFile
import java.io.File

/** 恢复在下次进程启动、任何 Room/设置仓库初始化之前提交。
 * pending 保留到全部替换完成，崩溃后重放；失败则阻止打开混合版本数据。
 */
internal object BackupRestore {
    private fun pending(context: Context) = File(context.noBackupFilesDir, "backup-restore")
    fun restore(
        context: Context, client: WebDavClient, name: String, database: app.resound.data.CaptureDatabase,
        storedPassphrase: String? = null, suppliedPassphrase: String? = null,
        closeDatabase: () -> Unit, restartProcess: () -> Unit,
    ) {
        if (client.metadata(name) == null) throw BackupFailure("备份文件名无效")
        val download = File(context.cacheDir, "resound-restore-${java.util.UUID.randomUUID()}.download")
        try {
            client.download(name, download)
            restoreFile(context, download, database, storedPassphrase, suppliedPassphrase, closeDatabase, restartProcess,
                fetchBase = client::download, expectedName = name)
        } finally { download.delete() }
    }
    fun restoreFile(
        context: Context, source: File, database: app.resound.data.CaptureDatabase,
        storedPassphrase: String? = null, suppliedPassphrase: String? = null,
        closeDatabase: () -> Unit, restartProcess: () -> Unit,
        fetchBase: ((String, File) -> Unit)? = null, expectedName: String? = null,
    ) {
        if (DayVolumes.isBundle(source)) {
            val bundle = File(context.cacheDir, "resound-bundle-${java.util.UUID.randomUUID()}")
            try {
                DayVolumes.unzip(source, bundle, ::volumePath)
                restoreVolumes(context, DirectoryVolumeStore(bundle), database, storedPassphrase, suppliedPassphrase, closeDatabase, restartProcess)
            } finally { bundle.deleteRecursively() }
            return
        }
        if (pending(context).exists()) throw BackupFailure("已有待恢复数据，请重启 App")
        val id = java.util.UUID.randomUUID()
        val workspace = File(context.cacheDir, "resound-restore-$id").apply { check(mkdirs()) }
        val verified = File(context.noBackupFilesDir, "resound-restore-$id")
        try {
            IncrementalBackup.verify(source, verified, workspace, database.openHelper.writableDatabase.version,
                storedPassphrase, suppliedPassphrase, fetchBase, expectedName)
            // 先在隔离副本上走同一套 Room 迁移和 schema 校验，失败不替换本机数据。
            try {
                val migrated = app.resound.data.CaptureDatabase.open(context, File(verified, BackupArchive.DATABASE).absolutePath)
                try {
                    migrated.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                        check(cursor.moveToFirst() && cursor.getInt(0) == 0)
                    }
                } finally { migrated.close() }
                listOf("-wal", "-shm", "-journal").forEach { File(File(verified, BackupArchive.DATABASE).path + it).delete() }
            } catch (_: Exception) { throw BackupFailure("备份数据库迁移或校验失败，未恢复数据") }
            snapshot(context, database)
            stage(context, verified)
            closeDatabase()
            restartProcess()
        } finally { workspace.deleteRecursively(); verified.deleteRecursively() }
    }
    fun restoreVolumes(context: Context, store: VolumeStore, database: app.resound.data.CaptureDatabase,
        stored: String?, supplied: String?, closeDatabase: () -> Unit, restartProcess: () -> Unit, checkpoint: String? = null) {
        if (pending(context).exists()) throw BackupFailure("已有待恢复数据，请重启 App")
        val id = java.util.UUID.randomUUID()
        val work = File(context.cacheDir, "resound-restore-$id").apply { check(mkdirs()) }
        val verified = File(context.noBackupFilesDir, "resound-restore-$id")
        try {
            val source = if (checkpoint == null) HistoryVolumes.latest(store, work) else HistoryVolumes.selected(store, checkpoint, work)
            DayVolumes.restore(context, source, work, verified, stored, supplied)
            snapshot(context, database)
            stage(context, verified)
            closeDatabase()
            restartProcess()
        } finally { work.deleteRecursively(); verified.deleteRecursively() }
    }
    private fun snapshot(context: Context, database: app.resound.data.CaptureDatabase) {
        val directory = File(context.noBackupFilesDir, "before-restore").apply { check(isDirectory || mkdirs()) }
        val id = java.util.UUID.randomUUID()
        val stage = File(directory, ".$id-content").apply { check(mkdirs()) }
        val part = File(directory, ".$id.part")
        try {
            DayVolumes.export(context, database, stage, part, null)
            check(part.renameTo(File(directory, "$id.zip")))
        } finally { stage.deleteRecursively(); part.delete() }
    }
    fun clearSnapshots(context: Context) {
        // 上传成功后才调用；清理失败不把成功上传误报成失败。
        File(context.noBackupFilesDir, "before-restore").deleteRecursively()
    }
    fun stage(context: Context, verified: File) {
        val target = pending(context)
        check(!target.exists()) { "已有待恢复数据" }
        check(verified.renameTo(target))
    }
    fun applyPending(context: Context) {
        val pending = pending(context)
        if (!pending.exists()) return
        val data = File(context.applicationInfo.dataDir)
        val db = File(data, BackupArchive.DATABASE)
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            val file = File(db.path + suffix); check(!file.exists() || file.delete())
        }
        // 缓存可重建；旧缓存不能混入恢复后的数据。
        check(File(data, BackupArchive.FEATURES).deleteRecursively())
        // 旧备份没有自定义模板时，也必须清掉本机的覆盖文本。
        check(File(data, BackupArchive.PROMPTS).deleteRecursively())
        check(File(data, BackupArchive.SKILLS).deleteRecursively())
        val volumes = File(pending, "day-volumes").isFile
        if (volumes) BackupSettings.apply(context, pending)
        pending.walkTopDown().filter { it.isFile }.forEach { source ->
            val path = source.relativeTo(pending).invariantSeparatorsPath
            if (path == "manifest.json" || volumes && path in setOf("day-volumes", "settings.json", "secrets.json")) return@forEach
            require(BackupArchive.allowed(path))
            if (path.startsWith("shared_prefs/")) {
                restorePreferences(context, source)
                return@forEach
            }
            val target = File(data, path).apply { parentFile!!.mkdirs() }
            val atomic = AtomicFile(target)
            val stream = atomic.startWrite()
            try { source.inputStream().use { it.copyTo(stream) }; atomic.finishWrite(stream) }
            catch (e: Exception) { atomic.failWrite(stream); throw e }
        }
        // 队列是运行时数据，不能继续发送恢复前的消息；密钥与未出现的设置项全部保留。
        check(context.getSharedPreferences("push-retries", Context.MODE_PRIVATE).edit().clear().commit())
        // 原子移走提交标记；清理中断不会再覆盖此后新增的数据。
        val done = File(context.noBackupFilesDir, "backup-restore-done")
        check(done.deleteRecursively())
        check(pending.renameTo(done))
        done.deleteRecursively()
    }
    private fun restorePreferences(context: Context, source: File) {
        val parser = android.util.Xml.newPullParser()
        val edit = context.getSharedPreferences(source.nameWithoutExtension, Context.MODE_PRIVATE).edit()
        source.inputStream().use { input ->
            parser.setInput(input, "UTF-8")
            while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) continue
                val name = parser.getAttributeValue(null, "name") ?: continue
                if (name in setOf("encryptedKey", "customEncryptedKey")) continue
                val value = parser.getAttributeValue(null, "value")
                when (parser.name) {
                    "string" -> edit.putString(name, parser.nextText())
                    "boolean" -> edit.putBoolean(name, value.toBooleanStrict())
                    "int" -> edit.putInt(name, value.toInt())
                    "long" -> edit.putLong(name, value.toLong())
                    "float" -> edit.putFloat(name, value.toFloat())
                    "set" -> {
                        val entries = mutableSetOf<String>()
                        while (!(parser.next() == org.xmlpull.v1.XmlPullParser.END_TAG && parser.name == "set")) {
                            if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "string") entries += parser.nextText()
                        }
                        edit.putStringSet(name, entries)
                    }
                }
            }
        }
        check(edit.commit())
    }

    fun restart(context: Context) {
        val launch = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        val intent = Intent.makeRestartActivityTask(launch.component)
        val pending = PendingIntent.getActivity(context, 1010, intent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, System.currentTimeMillis() + 1500, pending)
        Process.killProcess(Process.myPid())
    }
}
