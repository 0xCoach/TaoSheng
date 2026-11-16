package app.resound.audio

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

/** 沿用 inputs 备份分类；订阅随采集会话结束注销，备份恢复也会实时生效。 */
class BluetoothYieldSettings(context: Context) {
    private val prefs = context.getSharedPreferences("inputs", Context.MODE_PRIVATE)
    data class Options(val enabled: Boolean = true, val continueOnPhone: Boolean = true)
    fun read() = Options(prefs.getBoolean("bluetoothPlaybackYield", true),
        prefs.getBoolean("bluetoothPlaybackContinueOnPhone", true))
    fun setEnabled(value: Boolean) { check(prefs.edit().putBoolean("bluetoothPlaybackYield", value).commit()) }
    fun setContinueOnPhone(value: Boolean) { check(prefs.edit().putBoolean("bluetoothPlaybackContinueOnPhone", value).commit()) }
    val changes: Flow<Options> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(read()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(read())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal fun playbackYieldSnapshots(
    snapshots: Flow<InputSnapshot>, options: Flow<BluetoothYieldSettings.Options>, playing: Flow<Boolean>,
): Flow<InputSnapshot> {
    val eligible = combine(snapshots, options) { snapshot, setting ->
        BluetoothPlaybackYield.eligible(snapshot.selected().device.kind, setting.enabled)
    }.distinctUntilChanged()
    val held = eligible.flatMapLatest { enabled ->
        if (enabled) BluetoothPlaybackYield.held(playing) else flowOf(false)
    }
    return combine(snapshots, options, held) { snapshot, setting, holding ->
        val yielding = holding && BluetoothPlaybackYield.eligible(snapshot.selected().device.kind, setting.enabled)
        snapshot.copy(playbackYield = yielding, pauseForPlayback = yielding && !setting.continueOnPhone)
    }
}
