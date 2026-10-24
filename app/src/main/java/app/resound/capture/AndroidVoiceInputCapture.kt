package app.resound.capture

import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import app.resound.data.CaptureDatabase
import app.resound.transcribe.VoiceInputMetadata
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class AndroidVoiceInputCapture(private val context: Context, db: CaptureDatabase, scope: CoroutineScope) : VoiceInputCapture {
    private val prefs = context.getSharedPreferences("voice-input", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(VoiceInputSettings(prefs.getBoolean("enabled", false),
        prefs.getBoolean("context", true), prefs.getBoolean("replies", true), prefs.getStringSet("excluded", emptySet())!!.toSet()))
    override val settings = mutable.asStateFlow()
    override fun update(settings: VoiceInputSettings) {
        check(prefs.edit().putBoolean("enabled", settings.enabled).putBoolean("context", settings.context)
            .putBoolean("replies", settings.replies).putStringSet("excluded", settings.excludedPackages).commit())
        mutable.value = settings.copy(excludedPackages = settings.excludedPackages.toSet())
    }
    private val component = ComponentName(context, VoiceInputService::class.java)
    private fun enabled() = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?.split(':')?.any { ComponentName.unflattenFromString(it) == component } == true
    private val service = MutableStateFlow(enabled())
    override val serviceEnabled = service.asStateFlow()
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { service.value = enabled() }
    }
    private val rows = db.voiceInput().observed().shareIn(scope, SharingStarted.Eagerly, 1)
    private val clock = flow { while (true) { emit(LocalDate.now()); delay(60000) } }
    override val todayCount = combine(rows, clock) { lines, today ->
        lines.count { Instant.ofEpochMilli(it.startAt).atZone(ZoneId.systemDefault()).toLocalDate() == today }
    }.stateIn(scope, SharingStarted.Eagerly, 0)
    override val seenApps = rows.map { lines -> lines.mapNotNull { VoiceInputMetadata.read(it.voiceInputJson) }
        .distinctBy { it.packageName }.map { InputApp(it.packageName, it.app) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    private val changes = Channel<InputChange>(Channel.UNLIMITED)
    internal fun accept(change: InputChange) { changes.trySend(change) }
    init {
        context.contentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer)
        scope.launch {
            val store = VoiceInputStore(db)
            db.voiceInput().recover()
            for (change in changes) {
                try {
                    when (change) {
                        is InputChange.Save -> store.save(change.record)
                        is InputChange.Sent -> db.voiceInput().sent("voice-input:${change.token}", change.until)
                        is InputChange.Finish -> store.finish(change.token, change.after)
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { android.util.Log.w("VoiceInput", "语音输入保存失败：1 条") }
            }
        }
    }
}
