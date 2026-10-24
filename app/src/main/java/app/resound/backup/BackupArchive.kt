package app.resound.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import app.resound.data.CaptureDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal object BackupArchive {
    // 新增设置文件必须先审查再加入；默认不包含任何未知文件或密钥容器。
    val safePrefs = setOf("capture.xml", "transcribe.xml", "digest.xml", "diary.xml", "push.xml", "inputs.xml")
    const val DATABASE = "databases/capture.db"
    const val FEATURES = "databases/capture.db.voiceprint-features/"
    const val SKILLS = "files/skills/"
    const val PROMPTS = "files/prompts/"
    fun allowed(path: String): Boolean = path == DATABASE ||
        (path.startsWith(SKILLS) && path.removePrefix(SKILLS).let {
            it == "overrides.json" || it.matches(Regex("prompts/[a-z0-9_]+[.]md")) ||
                it.matches(Regex("mine/my_[a-z0-9_]+/(skill[.]json|prompt[.]md|default[.]md)"))
        }) ||
        (path.startsWith(PROMPTS) && path.removePrefix(PROMPTS) in app.resound.prompts.LocalPromptTemplates.ids.map { "$it.md" }) ||
        (path.startsWith(FEATURES) && path.removePrefix(FEATURES).matches(Regex("[a-f0-9]{64}"))) ||
        (path.startsWith("shared_prefs/") && path.removePrefix("shared_prefs/") in safePrefs)
    fun sha(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun snapshot(database: CaptureDatabase, destination: File) {
        check(!destination.exists())
        val source = database.openHelper.writableDatabase
        // IMMEDIATE 事务阻止其他写入；复制主库和 WAL，再由 SQLite 在隔离副本上合并。
        // 不做 VACUUM，保留页号、空闲页、page_size 和 auto_vacuum，避免重排放大增量。
        source.beginTransactionNonExclusive()
        try {
            val path = requireNotNull(source.path)
            File(path).copyTo(destination)
            File("$path-wal").takeIf { it.isFile }?.copyTo(File(destination.path + "-wal"))
        } finally { source.endTransaction() }
        try {
            SQLiteDatabase.openDatabase(destination.path, null, SQLiteDatabase.OPEN_READWRITE).use { copy ->
                copy.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use {
                    check(it.moveToFirst() && it.getInt(0) == 0)
                }
                copy.rawQuery("PRAGMA journal_mode=DELETE", null).use {
                    check(it.moveToFirst() && it.getString(0).equals("delete", ignoreCase = true))
                }
            }
        } catch (e: Exception) { destination.delete(); throw e }
        finally { listOf("-wal", "-shm", "-journal").forEach { File(destination.path + it).delete() } }
    }
    internal fun logicalSnapshot(path: String, destination: File) {
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { source ->
            source.beginTransactionNonExclusive()
            try {
                SQLiteDatabase.openOrCreateDatabase(destination, null).use { target ->
                    target.beginTransaction()
                    try {
                        target.execSQL("DROP TABLE IF EXISTS android_metadata")
                        val schema = mutableListOf<Triple<String, String, String>>()
                        source.rawQuery("SELECT type,name,sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' ORDER BY type='table' DESC", null).use {
                            while (it.moveToNext()) schema += Triple(it.getString(0), it.getString(1), it.getString(2))
                        }
                        // FTS 创建时会自动建立影子表；复制虚表内容（含 rowid），让 SQLite 重建索引。
                        val virtual = schema.filter { it.first == "table" && it.third.startsWith("CREATE VIRTUAL TABLE", true) }
                            .map { it.second }.toSet()
                        val shadows = virtual.flatMap { name ->
                            listOf("content", "segments", "segdir", "docsize", "stat").map { "${name}_$it" }
                        }.toSet()
                        schema.filter { it.first == "table" && it.second !in shadows }.forEach { (_, name, sql) ->
                            target.execSQL(sql)
                            copyTable(source, target, name, name in virtual)
                        }
                        schema.filter { it.first != "table" }.forEach { target.execSQL(it.third) }
                        source.rawQuery("SELECT name FROM sqlite_master WHERE name='sqlite_sequence'", null).use {
                            if (it.moveToFirst()) { target.execSQL("DELETE FROM sqlite_sequence"); copyTable(source, target, "sqlite_sequence") }
                        }
                        target.version = source.version
                        target.setTransactionSuccessful()
                    } finally { target.endTransaction() }
                }
            } finally { source.endTransaction() }
        }
    }
    private fun copyTable(source: SQLiteDatabase, target: SQLiteDatabase, name: String, includeRowId: Boolean = false) {
        val quoted = "\"${name.replace("\"", "\"\"")}\""
        source.rawQuery("SELECT ${if (includeRowId) "rowid," else ""}* FROM $quoted", null).use { rows ->
            val columns = if (includeRowId) rows.columnNames.joinToString(",", "(", ")") { "\"$it\"" } else ""
            target.compileStatement("INSERT INTO $quoted$columns VALUES (${List(rows.columnCount) { "?" }.joinToString()})").use { insert ->
                while (rows.moveToNext()) {
                    insert.clearBindings()
                    repeat(rows.columnCount) { i -> when (rows.getType(i)) {
                        android.database.Cursor.FIELD_TYPE_NULL -> insert.bindNull(i + 1)
                        android.database.Cursor.FIELD_TYPE_INTEGER -> insert.bindLong(i + 1, rows.getLong(i))
                        android.database.Cursor.FIELD_TYPE_FLOAT -> insert.bindDouble(i + 1, rows.getDouble(i))
                        android.database.Cursor.FIELD_TYPE_BLOB -> insert.bindBlob(i + 1, rows.getBlob(i))
                        else -> insert.bindString(i + 1, rows.getString(i))
                    } }
                    insert.executeInsert()
                }
            }
        }
    }
    fun create(context: Context, database: CaptureDatabase, staging: File, zip: File, at: Long) {
        val db = File(staging, DATABASE).apply { parentFile!!.mkdirs() }
        snapshot(database, db)
        val data = File(context.applicationInfo.dataDir)
        File(data, FEATURES).listFiles()?.filter { allowed(FEATURES + it.name) && it.isFile }?.forEach {
            it.copyTo(File(staging, FEATURES + it.name).apply { parentFile!!.mkdirs() })
        }
        synchronized(app.resound.prompts.LocalPromptTemplates.fileLock) {
            app.resound.prompts.LocalPromptTemplates.ids.forEach { id ->
                val source = android.util.AtomicFile(File(context.filesDir, "prompts/$id.md"))
                if (source.baseFile.exists() || File(source.baseFile.path + ".bak").exists()) {
                    source.openRead().use { input ->
                        File(staging, PROMPTS + "$id.md").apply { parentFile!!.mkdirs() }.outputStream().use { input.copyTo(it) }
                    }
                }
            }
        }
        synchronized(app.resound.skills.SkillFiles.fileLock) {
            val root = File(data, SKILLS)
            // AtomicFile 的 .bak 通过 openRead 恢复，.new 不当成有效文件备份。
            val paths = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath.removeSuffix(".bak") }
                .filter { allowed(SKILLS + it) }.toSet()
            paths.forEach { path ->
                android.util.AtomicFile(File(root, path)).openRead().use { input ->
                    File(staging, SKILLS + path).apply { parentFile!!.mkdirs() }.outputStream().use { input.copyTo(it) }
                }
            }
        }
        safePrefs.forEach { name ->
            // commit 空编辑，等待该文件此前的 apply 落盘。
            check(context.getSharedPreferences(name.removeSuffix(".xml"), Context.MODE_PRIVATE).edit().commit())
            if (name == "transcribe.xml") {
                writeHotwords(context, File(staging, "shared_prefs/$name"))
                return@forEach
            }
            File(data, "shared_prefs/$name").takeIf { it.isFile }?.copyTo(
                File(staging, "shared_prefs/$name").apply { parentFile!!.mkdirs() })
        }
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        pack(staging, zip, at, version, database.openHelper.writableDatabase.version)
    }
    internal fun writeHotwords(context: Context, target: File) {
        // 转写设置中含设备绑定的加密密钥；备份仅带热词。
        val words = context.getSharedPreferences("transcribe", Context.MODE_PRIVATE).getString("hotwords", "[]")!!
        target.apply { parentFile!!.mkdirs() }.outputStream().use { output ->
            val xml = android.util.Xml.newSerializer()
            xml.setOutput(output, "UTF-8"); xml.startDocument("UTF-8", true)
            xml.startTag(null, "map").startTag(null, "string").attribute(null, "name", "hotwords")
                .text(words).endTag(null, "string").endTag(null, "map"); xml.endDocument()
        }
    }

    fun pack(staging: File, zip: File, at: Long, appVersion: String, databaseVersion: Int) {
        val entries = JSONArray()
        ZipOutputStream(zip.outputStream().buffered()).use { output ->
            staging.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
                val path = file.relativeTo(staging).invariantSeparatorsPath
                require(allowed(path))
                entries.put(JSONObject().put("path", path).put("size", file.length()).put("sha256", sha(file)))
                output.putNextEntry(ZipEntry(path)); file.inputStream().use { it.copyTo(output) }; output.closeEntry()
            }
            val manifest = JSONObject().put("format", 1).put("createdAt", at).put("appVersion", appVersion)
                .put("databaseVersion", databaseVersion).put("files", entries)
            output.putNextEntry(ZipEntry("manifest.json")); output.write(manifest.toString().toByteArray()); output.closeEntry()
        }
    }
    /** 只解压白名单路径；全部验证通过才允许交给恢复器。 */
    fun verify(zip: File, staging: File, expectedVersion: Int) {
        extract(zip, staging)
        verifyContent(staging, expectedVersion)
    }
    internal fun extract(zip: File, staging: File, incremental: Boolean = false) {
        require(staging.mkdirs())
        try {
            val names = mutableSetOf<String>()
            var total = 0L
            ZipInputStream(zip.inputStream().buffered()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val name = entry.name
                    require(!entry.isDirectory && (name == "manifest.json" || allowed(name) || (incremental && name == "database.pages")) && names.add(name))
                    val file = File(staging, name).apply { parentFile!!.mkdirs() }
                    file.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        var size = 0L
                        while (true) {
                            val count = input.read(buffer); if (count < 0) break
                            total += count; size += count
                            require(total <= 2L * 1024 * 1024 * 1024 && (name != "manifest.json" || size <= 4 * 1024 * 1024))
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("ResoundBackup", "校验失败: ${e.javaClass.simpleName}: ${e.message} @ ${e.stackTrace.firstOrNull { it.className.startsWith("app.resound") }}")
            staging.deleteRecursively()
            throw (e as? BackupFailure ?: BackupFailure("备份校验失败，未恢复数据"))
        }
    }
    internal fun verifyContent(staging: File, expectedVersion: Int) {
        try {
            val names = staging.walkTopDown().filter { it.isFile }.map { it.relativeTo(staging).invariantSeparatorsPath }.toSet()
            val manifest = JSONObject(File(staging, "manifest.json").readText())
            val sourceVersion = manifest.getInt("databaseVersion")
            require(manifest.getInt("format") == 1)
            if (sourceVersion > expectedVersion) throw BackupFailure("备份数据库版本比 App 新，请先升级 App")
            require(sourceVersion in 1..expectedVersion)
            val files = manifest.getJSONArray("files")
            val declared = mutableSetOf<String>()
            repeat(files.length()) { i ->
                val item = files.getJSONObject(i); val path = item.getString("path")
                require(allowed(path) && declared.add(path))
                val file = File(staging, path)
                require(file.isFile && file.length() == item.getLong("size") && sha(file) == item.getString("sha256"))
            }
            require(DATABASE in declared && declared + "manifest.json" == names)
            // 校验的是暂存副本，可写打开：安卓的 FTS4 完整性检查要写临时数据，只读打开会报“attempt to write a readonly database”
            SQLiteDatabase.openDatabase(File(staging, DATABASE).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                require(db.version == sourceVersion)
                db.rawQuery("PRAGMA integrity_check", null).use {
                    val rows = buildList { while (it.moveToNext()) add(it.getString(0)) }
                    if (rows != listOf("ok")) android.util.Log.w("ResoundBackup", "完整性检查: ${rows.take(5)}")
                    require(rows == listOf("ok"))
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("ResoundBackup", "校验失败: ${e.javaClass.simpleName}: ${e.message} @ ${e.stackTrace.firstOrNull { it.className.startsWith("app.resound") }}")
            staging.deleteRecursively()
            throw (e as? BackupFailure ?: BackupFailure("备份校验失败，未恢复数据"))
        }
    }
}
