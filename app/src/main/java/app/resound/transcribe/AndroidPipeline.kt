package app.resound.transcribe

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import app.resound.AppGraph
import app.resound.PipelineProgress
import app.resound.PipelineProgress.Stage
import app.resound.ResoundApp
import app.resound.data.retryFailed
import app.resound.digest.core.DigestOutcome
import app.resound.scheduling.PipelineCoordinator
import app.resound.scheduling.PipelineModes
import app.resound.scheduling.PipelineResult
import app.resound.scheduling.PipelineStages
import kotlinx.coroutines.CancellationException

/** 安卓阶段适配；顺序、force/manual 和重试汇总都由 shared 决定。 */
internal class AndroidPipeline(private val app: ResoundApp) : PipelineStages {
    suspend fun run(mode: String, manual: Boolean, timing: (String, Long) -> Unit,
                    skipped: (String) -> Unit): PipelineResult {
        val pending = app.database.transcribe().pendingMillis()
        val skip = if (mode == PipelineModes.DIGEST) {
            val settings = AppGraph.transcribeSettings
            val cm = app.getSystemService(ConnectivityManager::class.java)
            PipelineCoordinator.digestTranscribeSkip(
                (settings as? AndroidTranscribeSettings)?.source?.value == TranscribeSource.Local,
                settings.wifiOnly.value,
                cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true,
                EarlyTranscribe.deviceAllows(app, realtime = true, pendingMillis = pending), pending)
        } else null
        if (skip != null) skipped(skip)
        return PipelineCoordinator(this, SystemClock::elapsedRealtime).run(mode, manual, skip, timing)
    }

    override suspend fun transcribe(retryFailed: Boolean): Boolean {
        PipelineProgress.stage(Stage.Transcribe)
        if (retryFailed) app.database.transcribe().retryFailed()
        return app.transcribeRunner.run()
    }

    override suspend fun voiceprint(): Boolean {
        var retry = false
        if (app.voiceprintRepository.hasPending()) {
            PipelineProgress.stage(Stage.Voiceprint)
            retry = app.voiceprintRepository.run(PipelineProgress::progress)
        }
        app.voiceprintRepository.cleanupAudio()
        return retry
    }

    override suspend fun organize(force: Boolean, manual: Boolean): DigestOutcome {
        PipelineProgress.stage(Stage.Digest)
        val result = app.voiceprintRepository.withStableResults { app.digestRunner.runOutcome(force, manual) }
        app.voiceprintRepository.cleanupAudio()
        if (AppGraph.isLlmBalanceReady) {
            try { AppGraph.llmBalance.refresh() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* 余额查询是附加信息，不改变已经提交的整理结果。 */ }
        }
        return result
    }
}

// 保留基线测试的调用面；规则本体只有 shared 一份。
internal fun digestTranscribeSkip(local: Boolean, wifiOnly: Boolean, unmetered: Boolean,
                                  deviceAllows: Boolean, pendingMillis: Long): String? =
    PipelineCoordinator.digestTranscribeSkip(local, wifiOnly, unmetered, deviceAllows, pendingMillis)

internal suspend fun runPipelineStages(mode: String, skip: String?,
                                       transcribe: suspend () -> Unit, organize: suspend () -> Unit) =
    PipelineCoordinator.selectStages(mode, skip, transcribe, organize)
