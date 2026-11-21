package app.resound.night
import android.content.Context
import androidx.work.*
import app.resound.AppGraph
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
class NightWork(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        if(!AppGraph.isNightReady)return Result.retry()
        return try { (AppGraph.night as AndroidNight).let { it.process();it.maintainInsights() };Result.success() } catch(e: CancellationException) {throw e} catch(e: Exception) {app.resound.diagnostics.FailureNotes.record("night/processing",e);Result.retry()}
    }
    companion object {
        fun run(context: Context) {WorkManager.getInstance(context).enqueueUniqueWork("night-process",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<NightWork>().build())}
        fun schedule(context: Context) {WorkManager.getInstance(context).enqueueUniquePeriodicWork("night-maintenance",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<NightWork>(15,TimeUnit.MINUTES).build())}
    }
}
