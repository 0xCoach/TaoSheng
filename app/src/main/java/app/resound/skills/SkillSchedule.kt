package app.resound.skills

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
