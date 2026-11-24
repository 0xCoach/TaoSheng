package app.resound.ui.topic

import app.resound.topic.StartResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 卡 230：专题一写进库，“当前专题”就先更新，面板随即换成“进行中”，开始面板自己的协程被取消；
 * 旧写法 runCatching 把这次取消当成失败，已开成功却提示“没开始成功”。开始操作改放在不随界面取消的作用域里，
 * 结果回来再提示；取消不再算失败。
 */
internal object TopicStartLauncher {
    private val appScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    fun launch(start: suspend () -> StartResult, done: (StartResult?) -> Unit, scope: CoroutineScope? = null): Job =
        (scope ?: appScope).launch {
            val result = try { start() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            done(result)
        }
}
