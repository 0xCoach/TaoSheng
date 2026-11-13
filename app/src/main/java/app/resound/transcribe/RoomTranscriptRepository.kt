package app.resound.transcribe

import android.content.Context
import androidx.work.WorkInfo
import app.resound.data.TranscribeDao
import app.resound.data.deleteFailed
import app.resound.platform.NowMillis
import app.resound.platform.ZoneProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.map
import java.io.File
import java.time.LocalDate

/** 安卓只装配广播、任务状态、文件和一次原子偏好写入。 */
class RoomTranscriptRepository private constructor(private val store: TranscriptStoreRepository) : TranscriptRepository by store {
    constructor(context: Context, dao: TranscribeDao, settings: TranscribeSettings,
        scheduler: TranscribeScheduler, scope: CoroutineScope, zoneProvider: ZoneProvider,
        now: NowMillis, io: CoroutineDispatcher,
    ) : this(context.getSharedPreferences("transcribe-queue", Context.MODE_PRIVATE).let { prefs ->
        TranscriptStoreRepository(dao, settings, scope, zoneProvider, now, io,
            calendarChanges(context), scheduler.observe().map { work ->
                work.map { TranscriptWorkSchedule(it.state == WorkInfo.State.ENQUEUED, it.nextScheduleTimeMillis) }
            }, { LocalDate.now().toString() },
            prefs.getLong("lastRunAt", 0).takeIf { it > 0 } to prefs.getString("lastError", null),
            { at, error -> check(prefs.edit().putLong("lastRunAt", at ?: 0).putString("lastError", error).commit()) },
            { File(it.id).isFile }, VoiceInputMetadata::read, scheduler::runNow,
            { dao.deleteFailed(it) })
    })
    internal val clock get() = store.clock
    fun updateProgress(total: Int, done: Int) = store.updateProgress(total, done)
    fun updateState(at: Long?, error: String?) = store.updateState(at, error)
    suspend fun diagnostics(): String = store.diagnostics()
}
