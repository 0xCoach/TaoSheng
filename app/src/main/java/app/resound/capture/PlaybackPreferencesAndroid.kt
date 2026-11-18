package app.resound.capture

import android.content.SharedPreferences
import app.resound.transcribe.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

fun playbackSettings(prefs: SharedPreferences): PlaybackSettingsStore {
    val key = "playbackMode"
    val prefix = "playbackApp."
    val stored = prefs.getString(key, null)
    val mode = stored?.let(PlaybackMode::valueOf) ?: migratePlayback(
        prefs.all["skipPlayback"] as? Boolean, prefs.all["includePlaybackDigest"] as? Boolean,
    ).also { check(prefs.edit().putString(key, it.name).commit()) }
    val apps = prefs.all.entries.filter { it.key.startsWith(prefix) }.associate {
        it.key.removePrefix(prefix) to PlaybackOverride.valueOf(it.value as String)
    }
    return PlaybackSettingsStore(PlaybackPreferences(mode, apps)) { next ->
        val edit = prefs.edit().putString(key, next.mode.name)
        prefs.all.keys.filter { it.startsWith(prefix) && it.removePrefix(prefix) !in next.apps }.forEach(edit::remove)
        next.apps.forEach { (pkg, rule) -> edit.putString(prefix + pkg, rule.name) }
        check(edit.commit())
    }
}

/** 供现有两个 Compose 控件过渡，所有写入均进入统一设置。 */
class LegacyPlaybackControls(private val settings: PlaybackSettings, private val scope: CoroutineScope) {
    private fun selected(mode: PlaybackMode) = settings.preferences.map { it.mode == mode }
        .stateIn(scope, SharingStarted.Eagerly, settings.preferences.value.mode == mode)
    val enabled = selected(PlaybackMode.Skip)
    val includePlaybackDigest = selected(PlaybackMode.Digest)
    fun setEnabled(value: Boolean) = settings.setDefault(if (value) PlaybackMode.Skip else PlaybackMode.Transcribe)
    fun setIncludePlaybackDigest(value: Boolean) = settings.setDefault(if (value) PlaybackMode.Digest else PlaybackMode.Transcribe)
}
