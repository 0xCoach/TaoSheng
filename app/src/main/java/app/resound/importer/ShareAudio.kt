package app.resound.importer

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import app.resound.AppGraph
import kotlinx.coroutines.CancellationException

/** 由 Activity 的 lifecycleScope 调用；消费 action 避免旋转后重复接收。 */
@Suppress("DEPRECATION")
suspend fun ComponentActivity.receiveSharedAudio(intent: Intent?) {
    if (intent?.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_VIEW)) return
    val incoming = requireNotNull(intent)
    val uris = buildList {
        when (incoming.action) {
            Intent.ACTION_SEND -> incoming.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(::add)
            Intent.ACTION_SEND_MULTIPLE -> addAll(incoming.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty())
            Intent.ACTION_VIEW -> incoming.data?.let(::add)
        }
        incoming.clipData?.let { clip -> repeat(clip.itemCount) { clip.getItemAt(it).uri?.let(::add) } }
    }.distinct()
    incoming.action = null
    try {
        if (uris.isEmpty()) throw ImportFailure("没有收到音频，请重新分享")
        val errors = AppGraph.importer.receive(uris).mapNotNull { it.exceptionOrNull()?.message }.distinct()
        if (errors.isNotEmpty()) Toast.makeText(this, errors.joinToString("；"), Toast.LENGTH_LONG).show()
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { Toast.makeText(this, "无法接收录音，请重新分享", Toast.LENGTH_LONG).show() }
}
