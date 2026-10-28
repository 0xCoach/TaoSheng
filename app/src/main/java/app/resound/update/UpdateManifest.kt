package app.resound.update

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** 所有发布地址只在此处维护；正式和预览文件独立回退。 */
internal object UpdateSources {
    const val MODELSCOPE = "https://modelscope.cn/models/OpenRecode/TaoSheng-releases/resolve/master/"
    const val GITHUB_LATEST = "https://github.com/0xCoach/TaoSheng/releases/latest/download/update.json"
    const val GITHUB_RELEASE = "https://github.com/0xCoach/TaoSheng/releases/download/"
    const val MODELSCOPE_PREVIEW = MODELSCOPE + "preview/"
    const val GITHUB_PREVIEW = GITHUB_RELEASE + "preview-channel/update-preview.json"
    val preview = listOf(
        UpdateSource("modelscope-preview", MODELSCOPE_PREVIEW + "update-preview.json") { _, file -> MODELSCOPE_PREVIEW + file },
        UpdateSource("github-preview", GITHUB_PREVIEW) { version, file -> "${GITHUB_RELEASE}v$version/$file" },
    )
    val official = listOf(
        UpdateSource("modelscope", MODELSCOPE + "update.json") { _, file -> MODELSCOPE + file },
        UpdateSource("github", GITHUB_LATEST) { version, file -> "${GITHUB_RELEASE}v$version/$file" },
    )
}

internal data class UpdateSource(val name: String, val manifestUrl: String, val artifactUrl: (String, String) -> String)
internal data class UpdateAsset(val file: String, val sha256: String, val size: Long) {
    fun json() = JSONObject().put("file", file).put("sha256", sha256).put("size", size)
}
internal data class UpdatePatch(val fromSha256: String, val asset: UpdateAsset)
internal data class UpdateManifest(
    val versionCode: Int, val versionName: String, val notes: String,
    val apk: UpdateAsset, val patches: List<UpdatePatch>,
    val previewSource: Boolean = false,
) {
    val preview: Boolean get() = "-preview." in versionName
    fun newerThan(installed: Long) = versionCode > installed
    fun patchFor(sha256: String?) = patches.firstOrNull { it.fromSha256.equals(sha256, ignoreCase = true) }
    fun info(patch: UpdatePatch?) = UpdateInfo(versionCode, versionName, notes, patch?.asset?.size ?: apk.size, patch != null, preview)
    fun json(): String = JSONObject().put("versionCode", versionCode).put("versionName", versionName)
        .put("notes", notes).put("apk", apk.json()).put("patches", JSONArray().apply {
            patches.forEach { put(it.asset.json().put("fromSha256", it.fromSha256)) }
        }).toString()

    companion object {
        fun parse(text: String): UpdateManifest {
            val root = JSONObject(text)
            val code = integer(root, "versionCode").also { require(it in 1..Int.MAX_VALUE) }.toInt()
            val version = string(root, "versionName").also { require(it.matches(Regex("[0-9]+(?:\\.[0-9]+)+(?:-preview\\.[0-9]+)?"))) }
            val notes = string(root, "notes")
            val apk = asset(root.getJSONObject("apk"))
            val items = root.getJSONArray("patches").also { require(it.length() <= 5) }
            val patches = (0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                UpdatePatch(hash(item, "fromSha256"), asset(item))
            }
            require(patches.map { it.fromSha256 }.distinct().size == patches.size)
            return UpdateManifest(code, version, notes, apk, patches)
        }
        private fun string(json: JSONObject, key: String) = (json.get(key) as? String) ?: error("invalid_$key")
        private fun integer(json: JSONObject, key: String): Long = when (val value = json.get(key)) {
            is Int -> value.toLong()
            is Long -> value
            else -> error("invalid_$key")
        }
        private fun hash(json: JSONObject, key: String) = string(json, key).also {
            require(it.length == 64 && it.all { c -> c in '0'..'9' || c.lowercaseChar() in 'a'..'f' })
        }.lowercase()
        private fun asset(json: JSONObject): UpdateAsset {
            val file = string(json, "file").also {
                require(it.length <= 180 && it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*")) && ".." !in it)
            }
            // 为差异包 + 整包及 20% 余量的加法留出空间，防止恶意数值溢出。
            val size = integer(json, "size").also { require(it in 1..Long.MAX_VALUE / 4) }
            return UpdateAsset(file, hash(json, "sha256"), size)
        }
    }
}

internal fun File.updateSha256(): String = inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().hex()
}
internal fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
internal class UpdateFailure(val human: String, val reason: String) : java.io.IOException(reason)
internal const val UPDATE_NETWORK_ERROR = "连不上更新服务器，稍后再试"
internal const val UPDATE_INTEGRITY_ERROR = "下载的安装包不完整，稍后再试"
