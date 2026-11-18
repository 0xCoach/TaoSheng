package app.resound.skills

// 同名类型与本文件声明或已有 import 冲突，以下引用保留全限定名：
// app.resound.skills.core.SkillSchedule

import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import android.content.Context
import androidx.work.*
import app.resound.AppGraph
import app.resound.review.withReviewForeground
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

object SkillSchedule {
 const val DAILY = "daily"
 const val AFTER = "after_digest"
 const val MANUAL = "manual"
 const val TRIAL = "trial"
 fun automatic(r: SkillRunRow) = app.resound.skills.core.SkillSchedule.automatic(r.snapshot())
 fun date(at: Long): LocalDate = app.resound.skills.core.SkillSchedule.date(at).toJavaLocalDate()
 fun autoCount(runs: List<SkillRunRow>, day: LocalDate) = app.resound.skills.core.SkillSchedule.autoCount(runs.map { it.snapshot() }, day.toKotlinLocalDate())
 fun next(skill: Skill, runs: List<SkillRunRow>, now: Long, catchUp: Boolean = true) = app.resound.skills.core.SkillSchedule.next(skill, runs.map { it.snapshot() }, now, catchUp)
}

class SkillScheduler(private val context: Context) {
    private var initial = true
    fun schedule(next: Long?, runningWorker: Boolean = false) {
        val wm = WorkManager.getInstance(context)
        val policy = if (runningWorker) ExistingWorkPolicy.APPEND_OR_REPLACE
            else if (initial) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
        initial = false
        if (next == null) { if (!runningWorker) wm.cancelUniqueWork(NAME); return }
        val request = OneTimeWorkRequestBuilder<SkillWork>()
            .setInitialDelay((next - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        // 在 Worker 末尾接续，避免 REPLACE 取消正在完成的本次任务。
        wm.enqueueUniqueWork(NAME, policy, request)
    }
    fun afterDigest(manual: Boolean = false) {
        WorkManager.getInstance(context).enqueueUniqueWork(AFTER_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SkillAfterDigestWork>().setInputData(workDataOf(MANUAL to manual))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    /** 固定首次启动的两天，断点续做时不随日期滚动。完成标记独立于可清理的运行日志。 */
    fun backfill207() {
        val prefs = context.getSharedPreferences("skills-backfill-207", Context.MODE_PRIVATE)
        if (prefs.getBoolean("done", false)) return
        if (!prefs.contains("day")) {
            val day = app.resound.skills.core.SkillSchedule.date(System.currentTimeMillis()).toEpochDays().toLong()
            check(prefs.edit().putLong("day", day).commit())
        }
        WorkManager.getInstance(context).enqueueUniqueWork("skills-backfill-207", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SkillBackfill207Work>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    companion object { const val NAME = "skills-daily"; const val AFTER_NAME = "skills-after-digest"; const val MANUAL = "manual" }
}
class SkillWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = AppGraph.skills as LocalSkillRepository
        if (repository.skills.value.any { it.id == "weekly" && it.enabled }) withReviewForeground { repository.runDue() }
        else repository.runDue()
        repository.reschedule(runningWorker = true)
        return Result.success()
    }
}
class SkillAfterDigestWork(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (AppGraph.skills as LocalSkillRepository).afterDigest(inputData.getBoolean(SkillScheduler.MANUAL, false))
        return Result.success()
    }
}

/** WorkManager 提供执行机会，业务筛选、门槛、日上限由共用仓储执行。 */
class SkillBackfill207Work(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("skills-backfill-207", Context.MODE_PRIVATE)
        if (prefs.getBoolean("done", false)) return Result.success()
        if (!prefs.contains("day")) return Result.failure()
        val today = prefs.getLong("day", 0)
        val repository = AppGraph.skills as LocalSkillRepository
        val completed = app.resound.skills.core.MeetingBackfill207.run(today,
            completed = { day -> prefs.getBoolean("day-$day", false) },
            complete = { day -> prefs.edit().putBoolean("day-$day", true).commit() },
            runDay = repository::backfillMeetings207)
        if (!completed) return Result.retry()
        return if (prefs.edit().putBoolean("done", true).commit()) Result.success() else Result.retry()
    }
}
