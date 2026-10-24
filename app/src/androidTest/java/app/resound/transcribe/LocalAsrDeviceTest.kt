package app.resound.transcribe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.resound.AppGraph
import app.resound.transcribe.local.*
import app.resound.voiceprint.CampPlusEmbedding
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalAsrDeviceTest {
    @Test fun customSettingsPersistTogether() {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val scoped=object:android.content.ContextWrapper(target) {
            override fun getSharedPreferences(name:String,mode:Int)=target.getSharedPreferences("integration-$name",mode)
        }
        try {
            val asr=AndroidTranscribeSettings(scoped)
            asr.setSource(TranscribeSource.Custom)
            asr.saveCustomAll("https://example.com/v1","voice-test","synthetic-test-key")
            val loaded=AndroidTranscribeSettings(scoped)
            assertEquals("https://example.com/v1",loaded.customEndpoint);assertEquals("voice-test",loaded.customModel)
            assertEquals("synthetic-test-key",loaded.readKey());assertTrue(loaded.ready.value)
            loaded.saveCustomAll("https://example.com/v1","voice-test-2","")
            assertEquals("synthetic-test-key",loaded.readKey())
            val llm=app.resound.digest.AndroidDigestSettings(scoped,app.resound.digest.LlmClient())
            assertEquals(listOf("deepseek","custom"),llm.presets.map{it.id})
            llm.setPreset("custom");llm.saveCustomAll("https://example.com","chat-test","synthetic-chat-key")
            val loadedLlm=app.resound.digest.AndroidDigestSettings(scoped,app.resound.digest.LlmClient())
            assertEquals("chat-test",loadedLlm.preset.model);assertEquals("https://example.com",loadedLlm.preset.baseUrl)
            assertEquals("synthetic-chat-key",loadedLlm.readKey())
        } finally {
            for(name in listOf("transcribe","local-asr","digest","digest-secrets"))target.getSharedPreferences("integration-$name",0).edit().clear().commit()
        }
    }
    @Test fun cpuNpuFallbackAndExistingVoiceprint()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val settings=AppGraph.transcribeSettings as AndroidTranscribeSettings
        val source=settings.source.value;val backend=settings.backend.value;val changed=settings.onChanged
        settings.onChanged={}
        val audio=File(context.cacheDir,"asr-device-test.ogg")
        context.assets.open("asr-test.ogg").use{input->audio.outputStream().use{input.copyTo(it)}}
        try {
            app.resound.audio.SileroVad(context).use { vad ->
                var frames=0
                vad.accept(ShortArray(512){1000}) { _,_,_->frames++ }
                assertEquals(1,frames)
            }
            for(mode in listOf(LocalBackend.Cpu,LocalBackend.Npu)) {
                LocalModels.enqueue(context,mode)
                withTimeout(240_000) { while(!LocalModels.ready(context,LocalModels.spec(mode))) delay(1000) }
            }
            val provider=LocalAsrProvider(context,settings)
            settings.setSource(TranscribeSource.Local)
            for(mode in listOf(LocalBackend.Cpu,LocalBackend.Npu)){
                settings.setBackend(mode)
                var uploads=0
                val result=provider.recognize(AsrAudio(audio,"ogg",3007),"local-model"){uploads++}
                assertTrue(result.sentences.isNotEmpty());assertEquals(0,uploads)
                assertTrue(settings.localStatus.value.contains(if(mode==LocalBackend.Npu) "NPU · 检查通过" else "CPU"))
            }
            settings.npuFailed=true
            provider.recognize(AsrAudio(audio,"ogg",3007),"local-model"){fail("local must not upload")}
            assertTrue(settings.localStatus.value.contains("回退 CPU"))
            val pcm=AsrAudioReader(context.cacheDir).readPcm(audio)
            CampPlusEmbedding(context).use{val embedding=it.compute(pcm);assertEquals(192,embedding.size);assertTrue(embedding.all{v->v.isFinite()})}
        } finally{settings.setBackend(backend);settings.setSource(source);settings.onChanged=changed;audio.delete()}
    }
}
