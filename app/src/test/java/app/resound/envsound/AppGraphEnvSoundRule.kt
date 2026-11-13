package app.resound.envsound

import app.resound.AppGraph
import org.junit.rules.ExternalResource

/** 测试借用全局装配项后恢复原值，包括 lateinit 尚未初始化的状态。 */
class AppGraphEnvSoundRule : ExternalResource() {
    private val field = AppGraph::class.java.getDeclaredField("envSound").apply { isAccessible = true }
    private var previous: Any? = null
    override fun before() { previous = field.get(AppGraph) }
    override fun after() {
        field.set(AppGraph, previous)
        check(field.get(AppGraph) === previous)
    }
}
