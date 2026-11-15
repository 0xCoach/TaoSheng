package app.resound.transcribe.local

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal class LocalResumeRequest(val hotwords: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LocalResumeRequest>
}
internal val LOCAL_INTERRUPTION_REASONS = setOf("local-inference-timeout", "local-unbound", "local-process-death", "local-cancelled")
