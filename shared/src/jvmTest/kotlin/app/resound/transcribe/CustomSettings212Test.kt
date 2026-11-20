package app.resound.transcribe

import org.junit.Assert.*
import org.junit.Test

class CustomSettings212Test {
    private val values = mutableMapOf<String, String>()
    private val keys = mutableSetOf<CustomAsrApi>()
    private val settings = CustomAsrSettings({ k,d -> values[k] ?: d }, { k,d -> values[k]?.toBoolean() ?: d },
        { k,v -> values[k] = v }, { k,v -> values[k] = v.toString() },
        { api,e,m,mode -> val prefix = if (api == CustomAsrApi.Qwen) "qwen" else ""
            values[if (prefix.isEmpty()) "endpoint" else "qwenEndpoint"] = e
            values[if (prefix.isEmpty()) "model" else "qwenModel"] = m
            values[if (prefix.isEmpty()) "endpointMode" else "qwenEndpointMode"] = mode.name },
        { _,_ -> }, keys::contains, {})
    @Test fun configurationsRemainIndependentAndKeyStateReflectsCredentials() {
        settings.save("https://open.invalid", "open", CustomEndpointMode.Auto)
        assertEquals(CustomKeyState.None, settings.keyState.value)
        keys += CustomAsrApi.OpenAi; settings.refresh(); assertEquals(CustomKeyState.Saved, settings.keyState.value)
        settings.tested(settings.testTicket(), CustomCapability.TextOnly)
        assertEquals(CustomKeyState.Verified, settings.keyState.value)
        settings.setApi(CustomAsrApi.Qwen)
        assertEquals(CustomAsrSettings.QWEN_ENDPOINT, settings.endpoint); assertEquals(CustomAsrSettings.QWEN_MODEL, settings.model)
        assertEquals(CustomEndpointMode.Full, settings.endpointMode.value); assertTrue(settings.customPrompt.value)
        assertEquals(CustomKeyState.None, settings.keyState.value); assertNull(settings.capability.value)
        settings.save("https://qwen.invalid/full", "qwen", CustomEndpointMode.Auto); settings.setPrompt(false)
        settings.setApi(CustomAsrApi.OpenAi)
        assertEquals("open", settings.model); assertEquals("https://open.invalid", settings.endpoint)
        assertNull(settings.capability.value)
        assertEquals(CustomKeyState.Saved, settings.keyState.value)
        settings.setApi(CustomAsrApi.Qwen); assertEquals("qwen", settings.model); assertFalse(settings.customPrompt.value)
    }
    @Test fun changedConfigAndStaleTestsCannotRestoreVerification() {
        settings.save("https://open.invalid", "open", CustomEndpointMode.Auto)
        val ticket = settings.testTicket()
        settings.tested(ticket, CustomCapability.TimedText)
        settings.save("https://open.invalid", "new-model", CustomEndpointMode.Auto)
        settings.tested(ticket, CustomCapability.TimedText); assertNull(settings.capability.value)
        settings.tested(settings.testTicket(), CustomCapability.TimedText)
        settings.setSpeakerFormat(CustomSpeakerFormat.Diarized); assertNull(settings.capability.value)
        settings.tested(settings.testTicket(), CustomCapability.Speakers)
        settings.invalidateAll(); assertNull(settings.capability.value)
    }
}
