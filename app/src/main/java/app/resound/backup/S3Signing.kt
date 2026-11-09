package app.resound.backup

import okhttp3.Request
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** AWS SigV4，S3 路径不做归一化、不二次编码；查询参数按编码后的名称和值排序。 */
internal object S3Signing {
    private val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US).withZone(ZoneOffset.UTC)
    fun encode(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val n = byte.toInt() and 255
            if (n in 65..90 || n in 97..122 || n in 48..57 || n in listOf(45, 46, 95, 126)) append(n.toChar())
            else { append('%'); append("0123456789ABCDEF"[n / 16]); append("0123456789ABCDEF"[n % 16]) }
        }
    }
    fun hash(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun hmac(key: ByteArray, value: String) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(value.toByteArray(Charsets.UTF_8))
    }
    fun sign(request: Request, region: String, access: String, secret: String, payloadHash: String, at: Instant): Request {
        val url = request.url
        val host = (if (':' in url.host) "[${url.host}]" else url.host) + if (url.port == (if (url.isHttps) 443 else 80)) "" else ":${url.port}"
        val date = timestamp.format(at)
        val headers = sortedMapOf("host" to host, "x-amz-content-sha256" to payloadHash, "x-amz-date" to date)
        // 包括 Range、条件写等调用者指定的头；签名和实际发送使用同一份值。
        request.headers.names().forEach { name ->
            if (name.lowercase(Locale.US) !in setOf("authorization", "host", "x-amz-content-sha256", "x-amz-date"))
                headers[name.lowercase(Locale.US)] = request.headers.values(name).joinToString(",") { it.trim().replace(Regex("\\s+"), " ") }
        }
        val query = (0 until url.querySize).map { encode(url.queryParameterName(it)) to encode(url.queryParameterValue(it).orEmpty()) }
            .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second }).joinToString("&") { "${it.first}=${it.second}" }
        val path = url.pathSegments.joinToString("/", "/") { encode(it) }
        val signedHeaders = headers.keys.joinToString(";")
        val canonical = listOf(request.method, path, query, headers.entries.joinToString("") { "${it.key}:${it.value}\n" }, signedHeaders, payloadHash).joinToString("\n")
        val scope = "${date.take(8)}/$region/s3/aws4_request"
        val toSign = "AWS4-HMAC-SHA256\n$date\n$scope\n${hash(canonical.toByteArray(Charsets.UTF_8))}"
        var key = hmac(("AWS4$secret").toByteArray(Charsets.UTF_8), date.take(8))
        listOf(region, "s3", "aws4_request").forEach { key = hmac(key, it) }
        return request.newBuilder().apply { headers.forEach { (name, value) -> header(name, value) } }
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=$access/$scope, SignedHeaders=$signedHeaders, Signature=${hex(hmac(key, toSign))}").build()
    }
}
