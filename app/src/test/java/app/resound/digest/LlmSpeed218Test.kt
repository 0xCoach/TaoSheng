package app.resound.digest

import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
class LlmSpeed218Test {
    @Test fun explicitCheckAppendsSpeedFailureWithoutChangingOtherItemsOrBudget() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        listOf("llm-batch-budget", "llm-stream-support", "llm-providers", "digest").forEach {
            context.getSharedPreferences(it, 0).edit().clear().commit()
        }
        var speedRequests = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val raw = okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            val body = JSONObject(raw)
            if (body.getJSONArray("messages").getJSONObject(0).getString("content") == LlmSpeedCheck.SYSTEM) speedRequests++
            val streaming = body.optBoolean("stream")
            val response = if (streaming) "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"思考\",\"content\":\"好\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                else "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", if (streaming) "text/event-stream" else "application/json")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = LlmClient(http, streamSupport = AndroidJsonStreamSupport(context))
        val values = mutableMapOf<String, String>()
        val keys = object : ProviderKeys {
            override fun has(id: String) = id in values
            override fun read(id: String) = values[id].orEmpty()
            override fun write(id: String, key: String) { values[id] = key }
            override fun remove(id: String) { values.remove(id) }
        }
        val settings = AndroidDigestSettings(context, client, AndroidLlmProviders(context, client, keys))
        settings.saveCustomAll("https://synthetic.invalid/v1", "synthetic", "fake-key")
        settings.setPreset("custom")
        repeat(4) { settings.batchBudget.failed(settings.preset, true) }
        val before = settings.batchBudget.current(settings.preset)
        val items = settings.check().getOrThrow()
        assertEquals(6, items.size); assertEquals(true, items.first().ok)
        assertEquals(true, items.first { it.feature == ModelCheck.JSON }.ok)
        assertEquals(1, speedRequests)
        assertEquals(LlmSpeedCheck.FEATURE, items.last().feature); assertEquals(false, items.last().ok)
        assertTrue(items.last().detail.contains("会思考")); assertTrue(items.last().detail.contains("不可解析"))
        assertEquals(before, settings.batchBudget.current(settings.preset))
        settings.jsonStreamSupport.rememberNonStreaming(settings.preset)
        assertTrue(AndroidJsonStreamSupport(context).nonStreaming(settings.preset))
        assertFalse(AndroidJsonStreamSupport(context).nonStreaming(settings.preset.copy(model = "new")))
    }
}
