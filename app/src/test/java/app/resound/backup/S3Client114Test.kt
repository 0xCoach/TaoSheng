package app.resound.backup

import android.app.Application
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class S3Client114Test {
    private lateinit var server: MockWebServer
    private lateinit var s3: S3Client
    @Before fun setup() {
        server = MockWebServer(); server.start()
        s3 = S3Client(S3Settings("custom", server.url("/").toString(), "us-east-1", "test-bucket", "前缀 +/", true), "test-access", "test-secret", pageSize = 1)
    }
    @After fun close() { server.shutdown() }
    @Test fun awsOfficialGetObjectSignature() {
        // https://docs.aws.amazon.com/AmazonS3/latest/developerguide/sig-v4-header-based-auth.html
        val request = Request.Builder().url("https://examplebucket.s3.amazonaws.com/test.txt").header("Range", "bytes=0-9").get().build()
        val result = S3Signing.sign(request, "us-east-1", "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            S3Signing.hash(byteArrayOf()), Instant.parse("2013-05-24T00:00:00Z"))
        assertEquals("f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41", result.header("Authorization")!!.substringAfter("Signature="))
    }
    @Test fun pathsEncodeEachSegmentAndVirtualHostIncludesBucket() {
        assertEquals("/test-bucket/%E5%89%8D%E7%BC%80%20%2B/Resound-Memory/a%20%2B%25.txt", s3.url("Resound-Memory/a +%.txt").encodedPath)
        val host = S3Client(S3Settings("aws", "https://s3.us-east-1.amazonaws.com", "us-east-1", "examplebucket", "", false), "a", "b")
        assertEquals("https://examplebucket.s3.us-east-1.amazonaws.com/a%2Bb", host.url("a+b").toString())
    }
    @Test fun paginationDecodesKeysAndSignsOpaqueContinuationToken() {
        fun page(key: String, more: Boolean) = MockResponse().setBody("""<ListBucketResult><EncodingType>url</EncodingType><Contents><Key>${S3Signing.encode("前缀 +/Resound-Backup/$key")}</Key><Size>7</Size><LastModified>2026-10-01T00:00:00Z</LastModified></Contents><IsTruncated>$more</IsTruncated>${if (more) "<NextContinuationToken>a+/=</NextContinuationToken>" else ""}</ListBucketResult>""")
        server.enqueue(page("resound-2026-10-01-0310.zip", true)); server.enqueue(page("resound-2026-10-02-0310.zip", false))
        assertEquals(2, s3.backups().size)
        val first = server.takeRequest(); val second = server.takeRequest()
        assertEquals("1", first.requestUrl!!.queryParameter("max-keys"))
        assertEquals("a+/=", second.requestUrl!!.queryParameter("continuation-token"))
        assertNotEquals(first.getHeader("Authorization"), second.getHeader("Authorization"))
    }
    @Test fun brokenPaginationAndAccessDeniedNeverBecomeEmptyHistory() {
        server.enqueue(MockResponse().setBody("<ListBucketResult><IsTruncated>true</IsTruncated></ListBucketResult>"))
        assertTrue(runCatching { s3.backups() }.exceptionOrNull() is BackupFailure)
        server.enqueue(MockResponse().setResponseCode(403).setBody("<Error><Code>AccessDenied</Code><Message>private-secret</Message></Error>"))
        val error = runCatching { s3.volumes().exists("resound-state.zip") }.exceptionOrNull()!!
        assertTrue(error.message!!.contains("无访问权限")); assertFalse(error.message!!.contains("private-secret"))
    }
    @Test fun timeAndCommonErrorsHaveSafeHumanMessages() {
        val codes = mapOf("RequestTimeTooSkewed" to "手机时间不准", "SignatureDoesNotMatch" to "密钥不正确", "NoSuchBucket" to "桶不存在", "AuthorizationHeaderMalformed" to "区域或服务地址不对")
        codes.forEach { (code, text) ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("<Error><Code>$code</Code><Message>secret</Message></Error>"))
            val failure = runCatching { s3.putText("probe", "data") }.exceptionOrNull()!!
            assertTrue(failure.message.orEmpty().contains(text)); assertFalse(failure.message.orEmpty().contains("secret"))
        }
        assertTrue(runCatching { S3Client.checkSize(5L * 1024 * 1024 * 1024 + 1) }.exceptionOrNull()!!.message!!.contains("5 GB"))
    }
    @Test fun volumePutIsSingleAtomicRequestAndMissingBucketIsAnError() {
        val file = File.createTempFile("s3-test", ".bin").apply { writeText("content") }
        try {
            server.enqueue(MockResponse()); s3.volumes().put("resound-state.zip", file)
            val put = server.takeRequest(); assertEquals("PUT", put.method); assertEquals("content", put.body.readUtf8())
            assertFalse(put.path!!.contains(".part")); assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(404)); server.enqueue(MockResponse().setResponseCode(404))
            assertTrue(runCatching { s3.volumes().exists("resound-state.zip") }.exceptionOrNull() is BackupFailure)
        } finally { file.delete() }
    }
}
