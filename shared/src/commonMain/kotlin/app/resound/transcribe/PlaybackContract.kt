package app.resound.transcribe

import kotlinx.coroutines.flow.StateFlow

enum class PlaybackMode { Digest, Transcribe, Skip }
enum class PlaybackOverride { Default, Digest, Transcribe, Skip, NotPlayback }

@kotlinx.serialization.Serializable
data class PlaybackPreferences(
    val mode: PlaybackMode = PlaybackMode.Digest,
    val apps: Map<String, PlaybackOverride> = emptyMap(),
) {
    fun rule(pkg: String?): PlaybackOverride = apps[pkg].takeUnless { it == PlaybackOverride.Default }
        ?: PlaybackOverride.valueOf(mode.name)
}

interface PlaybackSettings {
    val preferences: StateFlow<PlaybackPreferences>
    fun setDefault(mode: PlaybackMode)
    fun setApp(pkg: String, rule: PlaybackOverride)
}

data class SentencePlayback(val lineId: Long, val playing: Boolean, val appName: String? = null, val title: String? = null)

fun interface PlaybackSentences {
    suspend fun forSegment(segmentId: Long): List<SentencePlayback>
}
