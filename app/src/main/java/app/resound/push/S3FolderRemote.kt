package app.resound.push

import app.resound.backup.*
import okhttp3.OkHttpClient
import java.io.File

/** 仅 linkedCredentials 生成 S3 配置；持久化通道仍只存借用标记。 */
internal fun linkedS3(values: Map<String, String>, client: OkHttpClient = OkHttpClient()): S3Client? =
    if (values["cloudKind"] != "S3") null else S3Client(s3FromJson(values.getValue("s3")), values.getValue("account"), values.getValue("password"), client)

internal interface FolderStorage {
    fun directories(path: String = "")
    fun put(path: String, content: String, state: PushSyncState)
    fun delete(path: String)
}

internal class S3FolderRemote(private val s3: S3Client, private val folder: String) : FolderStorage {
    private fun key(path: String) = "$folder/$path"
    override fun directories(path: String) {} // S3 无实体目录。
    override fun put(path: String, content: String, state: PushSyncState) = s3.putText(key(path), content)
    override fun delete(path: String) = s3.deleteObject(key(path))
    fun audio(path: String, file: File) = s3.putObject(key(path), file)
    fun text(path: String, text: String) = s3.putText(key(path), text)
    fun send(format: PushFormat, message: PushMessage) {
        val daily = message.memory
        val date = daily?.getString("date") ?: java.time.Instant.ofEpochMilli(message.at).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
        val path = key(if (daily == null) "推送记录/$date.md" else "$date.md")
        if (daily != null) { s3.putText(path, daily.getString("markdown")); return }
        val old = s3.readText(path)
        if (old != null && old.second == null) throw PushFailure("S3 未返回 ETag，无法安全追加推送记录")
        val prefix = old?.first.orEmpty().trimEnd()
        val content = (if (prefix.isEmpty()) "" else "$prefix\n\n") +
            "## ${app.resound.OutputTime().moment(message.at)} ${message.title}\n\n${message.rendered(if (format == PushFormat.Text) format else PushFormat.Markdown)}\n"
        s3.putText(path, content, if (old == null) mapOf("If-None-Match" to "*") else mapOf("If-Match" to old.second!!))
    }
}

/** 不包含密钥；切换桶或前缀后必须重新推送，不能复用旧目的地的成功指纹。 */
internal fun folderIdentity(values: Map<String, String>): String = sha256(canonical(org.json.JSONObject(
    values.filterKeys { it in setOf("account", "url", "folder", "cloudKind", "s3") })))
