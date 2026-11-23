package app.resound.digest

import android.content.Context
import app.resound.topic.core.TopicRequestContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 卡 226：会自己切小的调用（整理、专题）遇到"一个字都没输出就超时"也换备用模型；在输出但太慢仍交给调用方切小。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = android.app.Application::class)
class LlmFailoverSilent226Test {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private class Keys : ProviderKeys {
        val data = mutableMapOf<String, String>()
        override fun has(id: String) = id in data
        override fun read(id: String) = data[id].orEmpty()
        override fun write(id: String, key: String) { data[id] = key }
        override fun remove(id: String) { data.remove(id) }
    }
    private lateinit var providers: AndroidLlmProviders
    private lateinit var failover: LlmFailover
    private lateinit var primary: LlmPreset
    private lateinit var backup: LlmPreset
    private val reply = LlmReply("{}", 20, 3)
    private var time = 1000L
    private val seen = mutableListOf<String>()

    @Before fun setup() {
        listOf("digest", "digest-state", "llm-providers", "digest-secrets").forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        providers = AndroidLlmProviders(context, LlmClient(), Keys())
        primary = providers.credentials(providers.saveNow(null, null, "主", "https://fixture.invalid/v1", "primary", "fixture", "", "")).first
        backup = providers.credentials(providers.saveNow(null, null, "备", "https://fixture.invalid/v1", "backup", "fixture", "", "")).first
        failover = LlmFailover(context, providers) { time }
    }
    private fun call(silent: Boolean, type: LlmError = LlmError.TooSlow): suspend (LlmPreset, String, String, String) -> LlmReply = { p, _, _, _ ->
        seen += p.model
        if (p.model == primary.model) throw LlmException(type, silent = silent) else reply
    }
    private suspend fun chat(c: suspend (LlmPreset, String, String, String) -> LlmReply) =
        failover.chat("专题整理", primary, "fixture", "s", "u", c) {}

    @Test fun silentTimeoutInTopicSwitchesToBackupAndCoolsPrimary() = runBlocking {
        withContext(TopicRequestContext(11, "提取要点", 1, 13)) { chat(call(silent = true)) }
        assertEquals(listOf(primary.model, backup.model), seen)
        assertEquals("模型长时间没有响应", failover.latestSwitch.value!!.reason)
        // 主模型已冷却：下一块直接用备用模型，不再先等主模型超时。
        seen.clear()
        withContext(TopicRequestContext(11, "提取要点", 2, 13)) { chat(call(silent = true)) }
        assertEquals(listOf(backup.model), seen)
    }

    @Test fun silentTimeoutInSplittingDigestAlsoSwitches() = runBlocking {
        withContext(LlmSplitsOnSlow) { chat(call(silent = true)) }
        assertEquals(listOf(primary.model, backup.model), seen)
    }

    @Test fun slowButProducingStillLetsCallerSplit() = runBlocking {
        for (ctx in listOf(LlmSplitsOnSlow, TopicRequestContext(11, "提取要点", 1, 13))) {
            failover.clear(primary.id); seen.clear()
            val e = runCatching { withContext(ctx) { chat(call(silent = false)) } }.exceptionOrNull() as LlmException
            assertEquals(LlmError.TooSlow, e.type); assertEquals(listOf(primary.model), seen)
        }
    }

    @Test fun truncatedNeverCountsAsSilent() = runBlocking {
        val e = runCatching { withContext(LlmSplitsOnSlow) { chat(call(silent = false, type = LlmError.Truncated)) } }.exceptionOrNull() as LlmException
        assertEquals(LlmError.Truncated, e.type); assertEquals(listOf(primary.model), seen)
    }

    @Test fun silentFlagSurvivesWhenEveryModelHangs() = runBlocking {
        val e = runCatching { withContext(LlmSplitsOnSlow) { chat { p, _, _, _ -> seen += p.model; throw LlmException(LlmError.TooSlow, silent = true) } } }
            .exceptionOrNull() as LlmException
        assertEquals(LlmError.TooSlow, e.type); assertTrue(e.silent); assertEquals(listOf(primary.model, backup.model), seen)
    }
}
