package app.resound.transcribe.local

/** 本机识别此刻可用的模型及用户选择。 */
data class LocalFacts(
    val wish: LocalBackend,
    val npuSupported: Boolean,
    val npuReady: Boolean,
    val cpuReady: Boolean,
    /** 本版本与本模型的 NPU 检查：true 通过、false 失败、null 待检查。 */
    val npuCheck: Boolean?,
)

object LocalEngine {
    fun choose(facts: LocalFacts): LocalBackend? {
        val npuUsable = facts.npuSupported && facts.npuReady && facts.npuCheck != false
        return when {
            facts.wish == LocalBackend.Npu && npuUsable -> LocalBackend.Npu
            facts.cpuReady -> LocalBackend.Cpu
            npuUsable -> LocalBackend.Npu
            else -> null
        }
    }

    fun status(facts: LocalFacts): String = when (choose(facts)) {
        LocalBackend.Npu -> when {
            facts.wish == LocalBackend.Cpu && facts.npuCheck != true -> "CPU 模型还没下载好，下次转写时先试用 NPU 识别"
            facts.wish == LocalBackend.Cpu -> "CPU 模型还没下载好，先用 NPU 识别"
            facts.npuCheck == true -> "正在使用：NPU 识别"
            else -> "已选 NPU 识别，下次转写时先试用"
        }
        LocalBackend.Cpu -> when {
            facts.wish == LocalBackend.Cpu || !facts.npuSupported -> "正在使用：CPU 识别"
            !facts.npuReady -> "NPU 模型还没下载好，先用 CPU 识别"
            else -> "NPU 识别暂时不可用，已自动改用 CPU（重新选一次 NPU 识别可再试）"
        }
        null -> when {
            !facts.npuSupported -> "请先下载 CPU 模型"
            facts.npuReady -> "NPU 识别暂时不可用，请先下载 CPU 模型"
            else -> "请先下载 NPU 或 CPU 模型"
        }
    }
}
