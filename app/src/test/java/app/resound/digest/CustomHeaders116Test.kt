package app.resound.digest

import android.app.Application
import android.content.Context
import app.resound.backup.BackupSettings
import app.resound.diagnostics.CrashRecords
import app.resound.transcribe.*
import app.resound.usage.BalanceSource
import app.resound.usage.LlmBalanceChecker
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class CustomHeaders116Test {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val values = mutableMapOf<Pair<String, String>, String>()
    private fun prefs(name: String) = context.getSharedPreferences(name, 0)
    // 只替代 Keystore 加解密；真实执行设置接口、请求发送与备份序列化/恢复。
    private fun store(name: String = "digest-secrets") = ServiceHeaders(
        { values[name to it].orEmpty() },
        { key, value -> values[name to key] = value; check(prefs(name).edit().putString(key, "ciphertext").commit()) },
        { key -> values.remove(name to key); check(prefs(name).edit().remove(key).commit()) },
    )
    private val keys = object : ProviderKeys {
        override fun has(id: String) = false
        override fun read(id: String) = ""
        override fun write(id: String, key: String) {}
        override fun remove(id: String) {}
    }
    private fun providers(client: LlmClient, headers: ServiceHeaders) =
        AndroidLlmProviders(context, client, keys, headerStore = headers)
    private fun jsonReply() = MockResponse().setBody("""{"choices":[{"message":{"content":"{}"}}]}""")
    @Before fun reset() {
        (BackupSettings.prefs + BackupSettings.stores.keys).forEach { prefs(it).edit().clear().commit() }
        values.clear(); ShadowLog.clear()
    }

    @Test fun validationRejectsInvalidInputWithoutLeakingValuesAndKeepsPreviousHeaders() {
        val headers = store()
        val good = listOf(HeaderEntry("X-Token", "private-value"), HeaderEntry("authorization", "Basic abc"))
        headers.save("a", good)
        val invalid = listOf(HeaderEntry("bad name", "private-value"), HeaderEntry("X-Token", "private-value\r\nX: bad"),
            HeaderEntry("X-Token", "private-value\u0000"), HeaderEntry("X-Token", "中文")) +
            listOf("hOsT", "Content-Length", "Content-Type", "Transfer-Encoding", "Connection").map { HeaderEntry(it, "private-value") }
        invalid.forEach {
            val error = runCatching { headers.save("a", listOf(it)) }.exceptionOrNull()!!
            assertTrue(error is IllegalArgumentException)
            assertFalse(error.message.orEmpty().contains("private-value"))
        }
        assertEquals(good, headers.headers("a")); assertTrue(headers.headers("b").isEmpty())
        assertFalse(good.toString().contains("private-value"))
    }

    @Test fun normalAndStreamingRequestsUseServiceHeadersAndReplaceBearer() = runBlocking {
        MockWebServer().use { server ->
            val headers = store()
            headers.save("a", listOf(HeaderEntry("X-Token", "private-value"), HeaderEntry("aUtHoRiZaTiOn", "Basic custom")))
            val client = LlmClient(requestHeaders = headers::forPreset)
            val preset = LlmPreset("a", "服务", server.url("/v1").toString(), "model")
            server.enqueue(jsonReply())
            client.chatJson(preset, "automatic-key", "JSON", "{}")
            val first = server.takeRequest()
            assertEquals("/v1/chat/completions", first.path)
            assertEquals("private-value", first.getHeader("X-Token"))
            assertEquals(listOf("Basic custom"), first.headers.values("Authorization"))
            server.enqueue(MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"好\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
            client.streamChat(preset, "", JSONArray(), JSONArray(), {}, { _, _ -> })
            assertEquals("Basic custom", server.takeRequest().getHeader("Authorization"))
            server.enqueue(jsonReply())
            client.chatJson(preset.copy(id = "b"), "other-key", "JSON", "{}")
            val other = server.takeRequest()
            assertNull(other.getHeader("X-Token")); assertEquals("Bearer other-key", other.getHeader("Authorization"))
        }
    }

    @Test fun settingsAndFailoverAcceptHeaderOnlyCredentialsAndKeepEachProvidersHeaders() = runBlocking {
        MockWebServer().use { server ->
            val headers = store()
            // 使用公网 HTTPS 配置验证认证门槛；仅在测试传输层转到本地假服务。
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            val client = LlmClient(http, headers::forPreset)
            val providers = providers(client, headers)
            val primary = providers.saveNow(null, null, "主", "https://example.invalid/primary/v1", "model", null, "", "")
            val backup = providers.saveNow(null, null, "备", "https://example.invalid/backup/v1", "model", null, "", "")
            providers.activate(primary)
            val settings = AndroidDigestSettings(context, client, providers)
            assertFalse(settings.hasKey.value)
            settings.saveHeaders(primary, listOf(HeaderEntry("Authorization", "Basic primary")))
            settings.saveHeaders(backup, listOf(HeaderEntry("X-Api-Key", "backup-secret")))
            assertTrue(settings.hasKey.value)
            assertEquals("Basic primary", settings.headers(primary).single().value)
            server.enqueue(MockResponse().setResponseCode(503)); server.enqueue(jsonReply())
            val p = providers.credentials(primary).first
            settings.failover.chat("测试", p, "", "JSON", "{}", { a, b, c, d -> client.chatJson(a, b, c, d) })
            assertEquals("Basic primary", server.takeRequest().getHeader("Authorization"))
            val second = server.takeRequest()
            assertEquals("/backup/v1/chat/completions", second.path)
            assertEquals("backup-secret", second.getHeader("X-Api-Key")); assertNull(second.getHeader("Authorization"))
            val unreadable = ServiceHeaders({ error("安全区暂时不可读") }, { _, _ -> }, {})
            val unavailable = AndroidLlmProviders(context, client, keys, headerStore = unreadable)
            assertFalse(unavailable.hasHeaderAuth(primary))
        }
    }

    @Test fun transcriptionUsesHeadersInAutoAndFullAddressModes() = runBlocking {
        MockWebServer().use { server ->
            val settings = AndroidTranscribeSettings(context, store("transcribe-header-secrets"))
            settings.setSource(TranscribeSource.Custom)
            settings.saveHeaders("Custom", listOf(HeaderEntry("Authorization", "Basic asr"), HeaderEntry("X-Token", "asr-secret")))
            settings.saveHeaders("Doubao", listOf(HeaderEntry("X-Api-Key", "doubao-secret")))
            val audio = File(context.cacheDir, "headers116.ogg").apply { writeBytes(byteArrayOf(1, 2)) }
            try {
                listOf(CustomEndpointMode.Auto to "/v1/audio/transcriptions", CustomEndpointMode.Full to "/speech_to_text").forEach { (mode, expected) ->
                    settings.saveCustom(server.url(if (mode == CustomEndpointMode.Auto) "/" else expected).toString(), "asr", mode)
                    server.enqueue(MockResponse().setBody("""{"text":"你好"}"""))
                    CustomAsrProvider(settings).recognize(AsrAudio(audio, "ogg", 1000), "old-key") {}
                    val request = server.takeRequest()
                    assertEquals(expected, request.path); assertEquals("Basic asr", request.getHeader("Authorization"))
                    assertEquals("asr-secret", request.getHeader("X-Token")); assertNull(request.getHeader("X-Api-Key"))
                }
                assertEquals("doubao-secret", settings.headers("Doubao").single().value)
            } finally { audio.delete() }
        }
    }

    @Test fun doubaoWebsocketUsesOverridesAndDropsReflectedHeaderValueFromLogId() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (bytes[1].toInt() == 0x22) webSocket.send(DoubaoProtocol.frame(9, 2,
                        """{"audio_info":{"duration":200},"result":{"utterances":[{"text":"测试","end_time":200}]}}""".toByteArray(), true).toByteString())
                }
            }).addHeader("X-Tt-Logid", "prefix-private-value-suffix"))
            val file = File(context.cacheDir, "headers116.pcm").apply { writeBytes(ByteArray(6400)) }
            try {
                val provider = DoubaoAsrProvider(AsrAudioReader(context.cacheDir),
                    DoubaoConfig(server.url("/asr").toString().replace("http://", "ws://"), "resource", 3000),
                    headers = { listOf(HeaderEntry("X-Api-Key", "private-value"), HeaderEntry("X-Speaker", "on")) })
                val result = provider.recognize(AsrAudio(file, "pcm", 200), "old-key") {}
                assertNull(result.logId)
                val request = server.takeRequest()
                assertEquals("private-value", request.getHeader("X-Api-Key")); assertEquals("on", request.getHeader("X-Speaker"))
            } finally { file.delete() }
        }
    }

    @Test fun modelsAndBalanceRequestsCarryHeaders() = runBlocking {
        MockWebServer().use { server ->
            val headers = listOf(HeaderEntry("Authorization", "Basic model"), HeaderEntry("Accept", "application/custom"))
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"model"}]}"""))
            assertEquals(listOf("model"), fetchRemoteModels(server.url("/v1").toString(), "old", headers = headers).ids)
            assertEquals("Basic model", server.takeRequest().getHeader("Authorization"))
            val checker = LlmBalanceChecker(prefs("llm-balance"), providers(LlmClient(), store()), { _, _ -> })
            server.enqueue(MockResponse().setBody("""{"balance":10}"""))
            checker.fetch(BalanceSource(server.url("/balance").toString(), "old", "balance", headers))
            val request = server.takeRequest()
            assertEquals("Basic model", request.getHeader("Authorization")); assertEquals("application/custom", request.getHeader("Accept"))
        }
    }

    @Test fun opencodeUsesDocumentedAddressStableSessionAndUserOverride() = runBlocking {
        val headers = store()
        val templates = AndroidLlmProviders.parseTemplates(context.assets.open("llm-providers.json").bufferedReader().use { it.readText() })
        val template = templates.first { it.id == "opencode-go" }
        assertEquals("deepseek-v4.1-flash", template.models.first())
        val p = LlmPreset("saved-go", template.name, template.baseUrl, template.models.first())
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"choices":[{"message":{"content":"{}"}}]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        LlmClient(http, headers::forPreset).chatJson(p, "key", "JSON", "{}")
        LlmClient(http, store()::forPreset).chatJson(p, "key", "JSON", "{}")
        assertEquals("https://opencode.ai/inference/go/openai/v1/chat/completions", requests[0].url.toString())
        val session = requests[0].header("x-opencode-session")!!
        assertEquals(session, requests[1].header("x-opencode-session")); assertTrue(headers.headers(p.id).isEmpty())
        headers.save(p.id, listOf(HeaderEntry("X-OpenCode-Session", "user-session")))
        LlmClient(http, headers::forPreset).chatJson(p, "key", "JSON", "{}")
        assertEquals(listOf("user-session"), requests[2].headers.values("x-opencode-session"))
        assertEquals("https://opencode.ai/inference/go/v1", ServiceHeaders.modelsEndpoint(p.baseUrl))
    }

    @Test fun backupRestorePreservesBothServicesAndAutomaticSession() {
        val llm = store(); val asr = store("transcribe-header-secrets")
        llm.save("a", listOf(HeaderEntry("X-Key", "llm-secret"))); asr.save("Custom", listOf(HeaderEntry("X-Key", "asr-secret")))
        val p = LlmPreset("a", "Go", "https://opencode.ai/inference/go/openai/v1", "deepseek-v4.1-flash")
        val session = llm.forPreset(p).first().value
        val root = File(context.cacheDir, "backup116").apply { mkdirs() }
        try {
            BackupSettings.capture(context, root) { name, key -> values.getValue(name to key) }
            assertFalse(File(root, "settings.json").readText().contains("llm-secret"))
            values.clear(); BackupSettings.stores.keys.forEach { prefs(it).edit().clear().commit() }
            BackupSettings.apply(context, root) { name, key, value ->
                values[name to key] = value; prefs(name).edit().putString(key, "restored-ciphertext").commit()
            }
            assertEquals("llm-secret", store().headers("a").single().value)
            assertEquals("asr-secret", store("transcribe-header-secrets").headers("Custom").single().value)
            assertEquals(session, store().forPreset(p).first().value)
        } finally { root.deleteRecursively() }
    }

    @Test fun responseAndValidationErrorsDoNotLeakHeaderValuesIntoLogsOrCrashReports() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("private-value"))
            val client = LlmClient(requestHeaders = { listOf(HeaderEntry("X-Key", "private-value")) })
            val error = runCatching { client.chatJson(LlmPreset("a", "服务", server.url("/v1").toString(), "model"), "", "JSON", "{}") }.exceptionOrNull()!!
            assertFalse(error.toString().contains("private-value"))
            val crashes = CrashRecords(context)
            crashes.record(Thread.currentThread(), error)
            assertFalse(crashes.dump().contains("private-value"))
            assertTrue(ShadowLog.getLogs().none { it.msg.contains("private-value") || it.throwable?.toString()?.contains("private-value") == true })
        }
    }
}
