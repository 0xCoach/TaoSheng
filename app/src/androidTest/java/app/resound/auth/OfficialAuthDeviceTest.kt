package app.resound.auth

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

/** 真机使用真实 Android Keystore 和持久化，所有 HTTP 都由合成回执拦截。 */
@RunWith(AndroidJUnit4::class)
class OfficialAuthDeviceTest {
    private val base: Context = ApplicationProvider.getApplicationContext()
    private val prefix = "official-audit-${UUID.randomUUID()}-"
    private val names = mutableSetOf<String>()
    private val context = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
            val isolated = prefix + name
            names += isolated
            return base.getSharedPreferences(isolated, mode)
        }
    }
    private val store = AuthStore(context)
    private fun login(account: String = "audit") {
        store.saveLogin(account, "audit", "audit-access", "audit-refresh",
            LicensePayload(1, account, "audit", store.deviceId, listOf("RECORD_AUDIO", "AI_ANALYSIS"), null, 1, 9999999999))
    }
    private fun gate(reply: (String) -> Pair<Int, String>): AuthGate {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val (code, body) = reply(chain.request().url.encodedPath)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("audit").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return AuthGate(store, AuthApi({ "https://audit.invalid" }, client))
    }
    @After fun clean() { names.forEach(base::deleteSharedPreferences) }

    @Test fun quotaDenialDoesNotLogOutOrRefresh() = runBlocking {
        login()
        val paths = mutableListOf<String>()
        val auth = gate { path -> paths += path; 402 to """{"error":"QUOTA_EXHAUSTED"}""" }
        val result = auth.officialGenerate("audit-request", "digest", "synthetic", "synthetic")
        assertEquals(402, (result as AuthApi.Result.Denied).code)
        assertEquals(listOf("/ai/official/generate"), paths)
        assertEquals("audit", store.sessionAccountId)
        assertNull(auth.loginRequested.value)
    }

    @Test fun expiredAccessWithOfflineRefreshKeepsSession() = runBlocking {
        login()
        val auth = gate { path ->
            if (path == "/auth/refresh") throw IOException("synthetic offline")
            401 to """{"error":"unauthorized"}"""
        }
        assertEquals(AuthGate.RefreshOutcome.Offline, auth.checkStatus())
        assertTrue(auth.officialStatus() is AuthApi.Result.Network)
        assertEquals("audit", store.sessionAccountId)
        assertNull(auth.loginRequested.value)
    }

    @Test fun revokedSessionWithBoundDataReturnsToLogin() = runBlocking {
        login()
        store.bindCurrent()
        val auth = gate { 401 to """{"error":"unauthorized"}""" }
        assertEquals(AuthGate.RefreshOutcome.SessionGone, auth.checkStatus())
        assertNull(store.sessionAccountId)
        assertFalse(auth.ownershipIssue())
        assertNotNull(auth.loginRequested.value)
        assertEquals("audit", store.boundAccountId)
    }

    @Test fun officialUsesSessionWithoutLocalKeyAndUpdatesBalance() = runBlocking {
        login()
        val auth = gate { path -> when (path) {
            "/ai/official/status" -> 200 to """{"enabled":true,"configured":true,"consented":true,"plan":"free","remaining":100}"""
            "/ai/official/generate" -> 200 to """{"content":"{}","points":3,"balance":{"remaining":97}}"""
            else -> error("Unexpected request $path")
        } }
        val ai = OfficialAi(context, auth, store)
        ai.refreshStatus()
        assertTrue(ai.ready())
        assertTrue(ai.generate("review", "synthetic", "synthetic") is OfficialAi.GenOutcome.Ok)
        assertEquals(97L, ai.status.value?.remaining)
        login("other")
        assertFalse(ai.ready())
        assertFalse(ai.localConsented())
        ai.clearStatus()
        assertNull(ai.status.value)
    }
}
