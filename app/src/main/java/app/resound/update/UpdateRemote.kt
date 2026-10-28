package app.resound.update

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class UpdateRemote(
    private val sources: List<UpdateSource> = UpdateSources.official,
    private val log: (String) -> Unit = {},
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).build(),
    private val previewSources: List<UpdateSource> = UpdateSources.preview,
) {
    private val checks = client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
    fun check(previewEnabled: Boolean = false): UpdateManifest {
        val official = runCatching { checkChannel(sources, false) }.getOrNull()
        val preview = if (previewEnabled) runCatching { checkChannel(previewSources, true) }.getOrNull() else null
        return listOfNotNull(official, preview).maxByOrNull { it.versionCode }
            ?: throw UpdateFailure(UPDATE_NETWORK_ERROR, "sources_exhausted")
    }

    private fun checkChannel(order: List<UpdateSource>, preview: Boolean): UpdateManifest {
        for (source in order) {
            val start = System.nanoTime()
            try {
                val call = checks.newCall(Request.Builder().url(source.manifestUrl).build())
                val manifest = call.execute().use { response ->
                    if (!response.isSuccessful) throw UpdateTransportError("http_${response.code}")
                    val body = response.body ?: throw UpdateTransportError("empty_body")
                    val bytes = body.byteStream().use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (output.size() <= MAX_MANIFEST) {
                            val count = input.read(buffer, 0, minOf(buffer.size, MAX_MANIFEST + 1 - output.size()))
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    require(bytes.size <= MAX_MANIFEST)
                    UpdateManifest.parse(bytes.toString(Charsets.UTF_8)).also { require(preview || !it.preview) }.copy(previewSource = preview)
                }
                log("check source=${source.name} code=${manifest.versionCode} ms=${elapsed(start)} result=ok")
                return manifest
            } catch (e: Exception) {
                log("check source=${source.name} ms=${elapsed(start)} failure=${reason(e)}")
            }
        }
        throw UpdateFailure(UPDATE_NETWORK_ERROR, "sources_exhausted")
    }

    fun download(asset: UpdateAsset, version: String, target: File, incremental: Boolean, previewSource: Boolean = "-preview." in version, progress: (Long) -> Unit) {
        val downloadSources = if (previewSource) previewSources else sources
        if (target.length() > asset.size) check(target.delete())
        repeat(2) { integrityAttempt ->
            try {
                if (target.length() != asset.size) transfer(asset, version, target, incremental,
                    if (integrityAttempt == 0) downloadSources else downloadSources.reversed(), progress)
                if (target.length() == asset.size && target.updateSha256() == asset.sha256) {
                    progress(asset.size)
                    log("verify incremental=$incremental bytes=${target.length()} attempt=$integrityAttempt result=ok")
                    return
                }
            } catch (e: UpdateFailure) {
                if (e.reason != "size_mismatch") throw e
            }
            check(!target.exists() || target.delete())
            log("verify incremental=$incremental attempt=$integrityAttempt failure=sha256_or_size")
        }
        throw UpdateFailure(UPDATE_INTEGRITY_ERROR, "sha256_mismatch")
    }

    private fun transfer(asset: UpdateAsset, version: String, target: File, incremental: Boolean,
        order: List<UpdateSource>, progress: (Long) -> Unit) {
        for (source in order) {
            // 中途断线先用 Range 接续一次；来源仍失败就换下一个，同一摘要的片段可以跨来源接续。
            repeat(2) {
                val start = System.nanoTime()
                val offset = target.length()
                try {
                    val request = Request.Builder().url(source.artifactUrl(version, asset.file)).header("Accept-Encoding", "identity")
                    if (offset > 0) request.header("Range", "bytes=$offset-")
                    client.newCall(request.build()).execute().use { response ->
                        if (response.code != 200 && response.code != 206) throw UpdateTransportError("http_${response.code}")
                        val append = response.code == 206
                        if (append) {
                            val range = response.header("Content-Range")?.let { RANGE.matchEntire(it) }
                            val values = range?.groupValues?.drop(1)?.map { it.toLongOrNull() }
                            if (values != listOf(offset, asset.size - 1, asset.size)) throw UpdateTransportError("invalid_range")
                        }
                        var done = if (append) offset else 0L // 服务器忽略 Range 时必须覆盖，不能追加。
                        val body = response.body ?: throw UpdateTransportError("empty_body")
                        if (body.contentLength() >= 0 && body.contentLength() != asset.size - done)
                            throw UpdateFailure(UPDATE_INTEGRITY_ERROR, "size_mismatch")
                        FileOutputStream(target, append).use { output ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                progress(done)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (count > asset.size - done) throw UpdateFailure(UPDATE_INTEGRITY_ERROR, "size_mismatch")
                                    output.write(buffer, 0, count); done += count; progress(done)
                                }
                            }
                            output.fd.sync()
                        }
                        if (done != asset.size) throw UpdateTransportError("truncated")
                    }
                    log("download source=${source.name} incremental=$incremental offset=$offset bytes=${target.length()} ms=${elapsed(start)} result=ok")
                    return
                } catch (e: Exception) {
                    log("download source=${source.name} incremental=$incremental offset=$offset bytes=${target.length()} ms=${elapsed(start)} failure=${reason(e)}")
                    if (e is UpdateFailure) throw e
                }
            }
        }
        throw UpdateFailure(UPDATE_NETWORK_ERROR, "download_sources_exhausted")
    }
    companion object {
        private const val MAX_MANIFEST = 256 * 1024
        private val RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
    }
}

internal fun elapsed(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000
/** 不记录响应正文、URL、账号或异常原文。 */
internal fun reason(e: Exception) = when (e) {
    is UpdateFailure -> e.reason
    is UpdateTransportError -> e.detail
    else -> e.javaClass.simpleName
}
private class UpdateTransportError(val detail: String) : IOException(detail)
