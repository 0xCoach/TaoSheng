package app.resound.backup

import java.io.File

/** 所有实现只允许访问备份根目录；put 必须先完整写入临时文件，再发布。 */
internal interface VolumeStore {
    fun read(path: String, target: File): Boolean
    fun exists(path: String): Boolean
    fun put(path: String, source: File)
    fun delete(path: String)
    fun copy(from: String, to: String)
    fun legacy(): List<String>
    fun files(directory: String): List<VolumeFile> = emptyList()
}

internal data class VolumeFile(val path: String, val bytes: Long, val modifiedAt: Long)

internal fun volumePath(path: String): Boolean = historyPath(path) || objectPath(path) || legacyVolumePath(path)
internal fun objectPath(path: String) = path.matches(Regex("objects/[a-f0-9]{64}[.]bin"))
internal fun historyPath(path: String) = path.matches(Regex("history/resound-point-[0-9]{13,16}-[0-9]{1,16}-[a-f0-9]{12}-[ep][.]json"))
internal fun legacyVolumePath(path: String): Boolean = path in setOf("resound-state.zip", "resound-state.zip.enc") ||
    path.matches(Regex("days/resound-day-\\d{4}-\\d{2}-\\d{2}[.]zip([.]enc)?")) ||
    path.matches(Regex("features/[a-f0-9]{64}[.]bin([.]enc)?")) ||
    path.matches(Regex("features/resound-features-\\d{4}-\\d{2}-\\d{2}[.]zip([.]enc)?")) ||
    path.matches(Regex("(days|features)/[.]previous-[a-f0-9]{64}[.](zip|bin)([.]enc)?"))

internal fun previousPath(path: String, hash: String): String {
    require(hash.matches(Regex("[a-f0-9]{64}")))
    return path.substringBefore('/') + "/.previous-$hash." + path.substringAfterLast('/').substringAfter('.')
}

internal class DirectoryVolumeStore(private val root: File) : VolumeStore {
    private fun file(path: String): File {
        require(volumePath(path) || ('/' !in path && BackupNames.metadata(path) != null ))
        return File(root, path)
    }
    override fun read(path: String, target: File): Boolean = file(path).takeIf { it.isFile }?.let { it.copyTo(target, true); true } ?: false
    override fun exists(path: String) = file(path).isFile
    override fun put(path: String, source: File) {
        val target = file(path).apply { parentFile!!.mkdirs() }
        val part = File(target.path + ".part")
        try { source.copyTo(part, true); check(part.renameTo(target)) } finally { part.delete() }
    }
    override fun delete(path: String) { val target = file(path); check(!target.exists() || target.delete()) }
    override fun copy(from: String, to: String) = put(to, file(from))
    override fun legacy() = root.listFiles().orEmpty().filter { it.isFile && BackupNames.metadata(it.name) != null }.map { it.name }
    override fun files(directory: String): List<VolumeFile> {
        require(directory in setOf("history", "objects"))
        return File(root,directory).listFiles().orEmpty().filter { it.isFile && volumePath("$directory/${it.name}") }
            .map { VolumeFile("$directory/${it.name}",it.length(),it.lastModified()) }
    }
}
