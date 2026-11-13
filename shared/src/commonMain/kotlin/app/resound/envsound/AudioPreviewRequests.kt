package app.resound.envsound

import app.resound.platform.FileRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** 由播放器所属的界面调度器串行调用；新的试听、停止和退出都会使旧请求失效。 */
class AudioPreviewRequests(
    private val scope: CoroutineScope,
    private val load: suspend (Long) -> FileRef?,
    private val onReady: (Long, FileRef, onCompleted: () -> Unit) -> Unit,
    private val onStop: () -> Unit,
) {
    private var revision = 0L
    private var pending: Job? = null

    fun play(segmentId: Long) {
        stop()
        val requested = revision
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val file = load(segmentId) ?: return@launch
            currentCoroutineContext().ensureActive()
            if (revision != requested) return@launch
            onReady(segmentId, file) {
                if (revision == requested) stop()
            }
        }
        pending = job
        job.start()
    }

    fun stop() {
        revision++
        pending?.cancel()
        pending = null
        onStop()
    }
}
