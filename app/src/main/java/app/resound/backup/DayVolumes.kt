package app.resound.backup

import android.content.Context
import app.resound.data.CaptureDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 无本机基准。以目的端状态清单为准，所有临时明文只存在私有暂存目录。 */
internal object DayVolumes {
    private const val FORMAT = 37
    private const val WEEK = 7L * 86400000
    data class State(val manifest: JSONObject, val directory: File, val bytes: Long) {
        val encrypted get() = manifest.getBoolean("encrypted")
        val days get() = manifest.getJSONArray("days")
        fun summary() = BackupSummary(days.length(), bytes + entries(days).sumOf { it.getLong("size") } +
            entries(manifest.getJSONArray("features")).distinctBy { it.getString("path") }.sumOf { it.getLong("size") },
            manifest.getLong("at"), manifest.getLong("uploadedPayloadBytes") + bytes, true, encrypted)
    }
    private fun entries(array: JSONArray) = (0 until array.length()).map { array.getJSONObject(it) }
    private fun stateName(encrypted: Boolean) = "resound-state.zip" + if (encrypted) ".enc" else ""
    fun zip(root: File, target: File) {
        ZipOutputStream(target.outputStream().buffered()).use { output ->
            root.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(root).path }.forEach { file ->
                output.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath).apply { time = 0 })
                file.inputStream().use { it.copyTo(output) }; output.closeEntry()
            }
        }
    }
    fun unzip(source: File, target: File, allowed: (String) -> Boolean) {
        check(target.mkdirs())
        val seen = mutableSetOf<String>(); var total = 0L
        ZipInputStream(source.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                require(!entry.isDirectory && seen.add(entry.name) && allowed(entry.name))
                val file = File(target, entry.name)
                require(file.canonicalPath.startsWith(target.canonicalPath + File.separator))
                file.parentFile!!.mkdirs()
                file.outputStream().use { out ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        total += n; require(total <= 2L * 1024 * 1024 * 1024)
                        out.write(buffer, 0, n)
                    }
                }
            }
        }
    }
    private fun plaintext(file: File, work: File, stored: String?, supplied: String?): File =
        if (!BackupEncryption.isEncrypted(file)) file else File(work, "plain-${UUID.randomUUID()}").also {
            BackupEncryption.decrypt(file, it, stored, supplied)
        }
    fun readState(store: VolumeStore, work: File, stored: String?, supplied: String? = null): State? {
        val states = mutableListOf<State>(); var failure: Exception? = null
        for (encrypted in listOf(true, false)) {
            val file = File(work, "state-${UUID.randomUUID()}")
            if (!store.read(stateName(encrypted), file)) continue
            try {
                val dir = File(work, "state-content-${UUID.randomUUID()}")
                unzip(plaintext(file, work, stored, supplied), dir) { it in setOf("manifest.json", "rows.jsonl", "settings.json", "secrets.json") || BackupArchive.allowed(it) && (it.startsWith(BackupArchive.SKILLS) || it.startsWith(BackupArchive.PROMPTS)) }
                val manifest = JSONObject(File(dir, "manifest.json").readText())
                require(manifest.getInt("format") == FORMAT && manifest.getBoolean("encrypted") == encrypted)
                val declared = entries(manifest.getJSONArray("files"))
                require(declared.map { it.getString("path") }.toSet() + "manifest.json" == dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.toSet())
                declared.forEach { verifyFile(File(dir, it.getString("path")), it) }
                BackupSettings.validate(JSONObject(File(dir, "settings.json").readText()), JSONObject(File(dir, "secrets.json").readText()))
                states += State(manifest, dir, file.length())
            } catch (e: Exception) { failure = e }
        }
        // 任一存在的状态不可读时不悄悄回退到可能更旧、缺记录的状态。
        failure?.let { throw it }
        return states.maxByOrNull { it.manifest.getLong("at") }
    }
    private fun verifyFile(file: File, item: JSONObject) {
        require(file.isFile && file.length() == item.getLong("size") && BackupArchive.sha(file) == item.getString("sha256"))
    }
    private fun fileInfo(file: File, path: String) = JSONObject().put("path", path).put("size", file.length()).put("sha256", BackupArchive.sha(file))
    private fun fileAssets(context: Context, target: File) {
        for ((prefix, lock) in listOf(BackupArchive.SKILLS to app.resound.skills.SkillFiles.fileLock,
            BackupArchive.PROMPTS to app.resound.prompts.LocalPromptTemplates.fileLock)) synchronized(lock) {
            val root = File(context.applicationInfo.dataDir, prefix)
            root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath.removeSuffix(".bak") }
                .filter { BackupArchive.allowed(prefix + it) }.toSet().forEach { path ->
                    val file = File(target, prefix + path).apply { parentFile!!.mkdirs() }
                    android.util.AtomicFile(File(root, path)).openRead().use { input -> file.outputStream().use { input.copyTo(it) } }
                }
        }
    }
    fun sync(context: Context, database: CaptureDatabase, store: VolumeStore, work: File, passphrase: String?,
        at: Long = System.currentTimeMillis()): BackupSummary {
        work.mkdirs()
        val old = try { readState(store, work, passphrase) } catch (e: BackupFailure) {
            // 改口令后无法解开旧状态：重新生成全部本机日分卷；不依赖本机缓存。
            if (e.human in setOf("口令不对", "这份备份加了密，请输入口令")) null else throw e
        }
        old?.manifest?.optJSONArray("pendingDeletes")?.let { pending ->
            val objects = entries(old.days) + entries(old.manifest.getJSONArray("features"))
            val live = objects.map { it.getString("path") }.toSet() + stateName(old.encrypted)
            val recoveries = objects.map { previousPath(it.getString("path"), it.getString("sha256")) }.toSet()
            repeat(pending.length()) {
                val path = pending.getString(it); require(path !in live)
                // 同一个内容指纹可能仍被当前清单引用；其恢复副本不能在提交下一清单前删除。
                if (path !in recoveries) store.delete(path)
            }
        }
        val snapshot = File(work, "capture.db")
        BackupArchive.snapshot(database, snapshot)
        val (version, rows) = DayVolumeRows.write(snapshot, File(work, "rows"))
        val encrypted = passphrase != null
        val suffix = if (encrypted) ".enc" else ""
        val stateDir = File(work, "new-state").apply { mkdirs() }
        rows.getValue("state").copyTo(File(stateDir, "rows.jsonl"))
        BackupSettings.capture(context, stateDir)
        fileAssets(context, stateDir)
        val oldDays = old?.let { entries(it.days).associateBy { d -> d.getString("date") } }.orEmpty()
        val days = JSONArray(); val features = JSONArray()
        var uploaded = 0L
        val previous = mutableSetOf<String>()
        fun publish(path: String, source: File, prior: JSONObject? = null) {
            // 覆盖前保存按旧文件指纹寻址的副本。再次重试不能覆盖已有的旧清单副本。
            val hash = if (prior?.getString("path") == path) prior.getString("sha256") else {
                val before = File(work, "before-${UUID.randomUUID()}")
                if (store.read(path, before)) BackupArchive.sha(before) else null
            }
            if (hash != null && store.exists(path)) {
                val history = previousPath(path, hash)
                if (!store.exists(history)) store.copy(path, history)
                previous += history
            }
            store.put(path, source); uploaded += source.length()
        }
        rows.filterKeys { it != "state" }.forEach { (date, file) ->
            val fingerprint = BackupArchive.sha(file)
            val path = "days/resound-day-$date.zip$suffix"
            val prior = oldDays[date]
            if (prior != null && old!!.encrypted == encrypted && prior.getString("fingerprint") == fingerprint &&
                prior.getInt("schema") == version && store.exists(path)) {
                // 通常只发 HEAD；只有上次中断留下恢复副本时，才回读核对同名内容。
                if (!store.exists(previousPath(path, prior.getString("sha256")))) { days.put(prior); return@forEach }
                val existing = File(work, "check-$date")
                check(store.read(path, existing))
                if (BackupArchive.sha(existing) == prior.getString("sha256")) { days.put(prior); return@forEach }
            }
            val dir = File(work, "day-$date").apply { mkdirs() }; file.copyTo(File(dir, "rows.jsonl"))
            val zip = File(work, "$date.zip"); zip(dir, zip)
            val archive = BackupEncryption.prepare(zip, passphrase)
            publish(path, archive, prior)
            days.put(fileInfo(archive, path).put("date", date).put("fingerprint", fingerprint).put("schema", version))
        }
        val featureRoot = File(context.applicationInfo.dataDir, BackupArchive.FEATURES)
        val oldFeatures = old?.let { entries(it.manifest.getJSONArray("features")).associateBy { f -> f.getString("path") } }.orEmpty()
        val sent = mutableMapOf<String, JSONObject>()
        featureRoot.listFiles().orEmpty().filter { it.isFile && BackupArchive.allowed(BackupArchive.FEATURES + it.name) }.sortedBy { it.name }.forEach { file ->
            val hash = BackupArchive.sha(file); val path = "features/$hash.bin$suffix"
            val info = sent.getOrPut(path) {
                val prior = oldFeatures[path]
                val existing = File(work, "feature-existing-$hash")
                val reusable = if (prior != null && store.exists(path)) prior else if (store.read(path, existing)) {
                    val matches = runCatching { BackupArchive.sha(plaintext(existing, work, passphrase, null)) == hash }.getOrDefault(false)
                    if (matches) fileInfo(existing, path).put("fingerprint", hash) else null
                } else null
                reusable ?: run {
                    val copy = file.copyTo(File(work, "feature-$hash"), true)
                    val archive = BackupEncryption.prepare(copy, passphrase)
                    publish(path, archive, prior)
                    fileInfo(archive, path).put("fingerprint", hash)
                }
            }
            features.put(JSONObject(info.toString()).put("name", file.name))
        }
        val startedAt = old?.manifest?.getLong("startedAt") ?: at
        val currentPaths = entries(days).map { it.getString("path") }.toSet()
        val featurePaths = entries(features).map { it.getString("path") }.toSet()
        val removed = oldDays.values.filter { it.getString("path") !in currentPaths } +
            oldFeatures.values.filter { it.getString("path") !in featurePaths }
        val liveRecoveries = (entries(days) + entries(features)).map {
            previousPath(it.getString("path"), it.getString("sha256"))
        }.toSet()
        val cleanup = (removed.map { it.getString("path") } + previous +
            removed.map { previousPath(it.getString("path"), it.getString("sha256")) } +
            stateName(!encrypted) + if (at - startedAt >= WEEK)
                store.legacy().filter { it.startsWith("resound-") && BackupNames.metadata(it) != null } else emptyList()).toSet() - liveRecoveries
        val manifest = JSONObject().put("format", FORMAT).put("schema", version).put("at", at)
            .put("startedAt", startedAt).put("encrypted", encrypted)
            .put("includesSecrets", true).put("uploadedPayloadBytes", uploaded).put("days", days).put("features", features)
            .put("pendingDeletes", JSONArray(cleanup.sorted()))
        manifest.put("files", JSONArray(stateDir.walkTopDown().filter { it.isFile }.map { fileInfo(it, it.relativeTo(stateDir).invariantSeparatorsPath) }.toList()))
        File(stateDir, "manifest.json").writeText(DayVolumeRows.canonical(manifest))
        val stateZip = File(work, "state.zip"); zip(stateDir, stateZip)
        val stateFile = BackupEncryption.prepare(stateZip, passphrase)
        store.put(stateName(encrypted), stateFile) // 最后提交清单，之后才允许删除。
        // 清理意图随状态提交。断网/进程中断后，下次先重试，不能遗留隐私删除的日分卷。
        cleanup.forEach(store::delete)
        return State(manifest, stateDir, stateFile.length()).summary()
    }
    private fun fetch(store: VolumeStore, item: JSONObject, work: File): File {
        val path = item.getString("path"); require(volumePath(path) && '/' in path)
        val file = File(work, "download-${UUID.randomUUID()}")
        if (!store.read(path, file) || runCatching { verifyFile(file, item) }.isFailure) {
            require(store.read(previousPath(path, item.getString("sha256")), file)) { "分卷缺失或损坏" }
        }
        verifyFile(file, item)
        return file
    }
    fun restore(context: Context, store: VolumeStore, work: File, verified: File, stored: String?, supplied: String?) {
        val state = readState(store, work, stored, supplied) ?: throw BackupFailure("还没有按天分卷备份")
        val manifest = state.manifest
        val days = entries(state.days)
        require(days.map { it.getString("date") }.distinct().size == days.size)
        val rowFiles = days.map { item ->
            require(item.getInt("schema") <= manifest.getInt("schema"))
            val source = fetch(store, item, work)
            val dir = File(work, "day-${UUID.randomUUID()}")
            unzip(plaintext(source, work, stored, supplied), dir) { it == "rows.jsonl" }
            File(dir, "rows.jsonl").also { require(BackupArchive.sha(it) == item.getString("fingerprint")) }
        }
        check(verified.mkdirs())
        entries(manifest.getJSONArray("features")).forEach { item ->
            val name = item.getString("name"); require(BackupArchive.allowed(BackupArchive.FEATURES + name))
            val file = plaintext(fetch(store, item, work), work, stored, supplied)
            require(BackupArchive.sha(file) == item.getString("fingerprint"))
            file.copyTo(File(verified, BackupArchive.FEATURES + name).apply { parentFile!!.mkdirs() })
        }
        DayVolumeRows.rebuild(context, File(verified, BackupArchive.DATABASE), manifest.getInt("schema"), File(state.directory, "rows.jsonl"), rowFiles)
        state.directory.walkTopDown().filter { it.isFile && it.name !in setOf("manifest.json", "rows.jsonl") }.forEach {
            it.copyTo(File(verified, it.relativeTo(state.directory).invariantSeparatorsPath).apply { parentFile!!.mkdirs() })
        }
        File(verified, "day-volumes").writeText("37")
    }
    fun export(context: Context, database: CaptureDatabase, work: File, target: File, passphrase: String?) {
        val bundle = File(work, "bundle").apply { mkdirs() }
        sync(context, database, DirectoryVolumeStore(bundle), File(work, "generate"), passphrase)
        zip(bundle, target)
    }
    fun isBundle(file: File): Boolean = ZipInputStream(file.inputStream().buffered()).use { input ->
        var found = false
        while (true) { val entry = input.nextEntry ?: break; if (entry.name in setOf("resound-state.zip", "resound-state.zip.enc")) { found = true; break } }
        found
    }
}
