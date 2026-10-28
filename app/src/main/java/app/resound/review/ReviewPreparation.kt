package app.resound.review

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import app.resound.AppGraph
import app.resound.ResoundApp
import app.resound.digest.DigestAlarm
import app.resound.transcribe.PipelineTimes
import app.resound.transcribe.TranscribeWork
import kotlinx.coroutines.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** 前台服务被系统拒绝时跳过准备，给普通 Worker 留出生成回顾的执行时间。 */
private class PreparationAllowed(val value: Boolean) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PreparationAllowed>
}

internal suspend fun <T> CoroutineWorker.withReviewForeground(block: suspend () -> T): T {
    val allowed = try {
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("review-preparation", "回顾与整理", NotificationManager.IMPORTANCE_LOW))
        setForeground(ForegroundInfo(5300 + (id.hashCode() and 0xffff),
            NotificationCompat.Builder(applicationContext, "review-preparation")
                .setSmallIcon(app.resound.R.drawable.ic_notify).setContentTitle("涛声正在整理和生成回顾")
                .setOngoing(true).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        true
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { false }
    return withContext(PreparationAllowed(allowed)) { block() }
}

internal object ReviewPreparation {
    suspend fun run(app: ResoundApp) {
        prepare(record = { PipelineTimes.record(app, "${System.currentTimeMillis()} 回顾前整理 $it") }) {
            when {
                currentCoroutineContext()[PreparationAllowed]?.value == false -> "前台申请失败，使用已有内容"
                !DigestAlarm.online(app) -> "没有网络，使用已有内容"
                !AppGraph.digestSettings.hasKey.value -> "没有密钥，使用已有内容"
                else -> {
                    val retry = TranscribeWork.beforeReview(app)
                    if (retry || app.digestRunner.last.value.second != null) "未全部完成，使用已有内容" else "完成"
                }
            }
        }
    }

    /** 只吞本次超时和普通失败，系统取消必须继续抛出；等待流水线锁也包含在额度内。 */
    internal suspend fun prepare(timeoutMillis: Long = 10 * 60_000L, record: (String) -> Unit, action: suspend () -> String) {
        val result = try {
            withTimeoutOrNull(timeoutMillis) { action() } ?: "超时，使用已有内容"
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { "失败，使用已有内容" }
        record(result)
    }
}
