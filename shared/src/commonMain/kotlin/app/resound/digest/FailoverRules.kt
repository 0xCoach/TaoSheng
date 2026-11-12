package app.resound.digest

/** 存储由平台实现；方法提供单次快照/原子更新，配置代次防止旧请求污染新配置。 */
interface FailoverStore {
    fun cooling(id: String): FailoverCooling?
    fun coolingSnapshot(ids: List<String>): Map<String, FailoverCooling>
    fun coolIfCurrent(id: String, generation: Long, cooling: FailoverCooling)
}
data class FailoverCooling(val until: Long, val reason: String, val error: Exception)
data class FailoverCandidate(val preset: LlmPreset, val key: String, val generation: Long)
data class FailoverFailure(val reason: String, val capacity: Boolean = false, val format: Boolean = false,
    val rejected: Boolean = false, val longCooling: Boolean = false)

/** 选择、容量递增、冷却、最多三次与错误优先级；I/O、凭据及异常类型映射均由安卓插口提供。 */
class FailoverRules(private val store: FailoverStore, private val now: app.resound.platform.NowMillis,
    private val capacity: (LlmPreset) -> Int,
    private val classify: (Exception) -> FailoverFailure?,
    private val safeCoolingError: (Exception) -> Exception,
    private val exhausted: (Exception, String) -> Exception) {
    suspend fun <T> execute(primary: LlmPreset, candidates: List<FailoverCandidate>, enabled: Boolean,
        canSwitch: () -> Boolean, switched: (LlmPreset, String) -> Unit,
        call: suspend (FailoverCandidate) -> T): Pair<T, LlmPreset> {
        fun ordered(list: List<FailoverCandidate>): List<FailoverCandidate> {
            if (!enabled) return list
            val cooling = store.coolingSnapshot(list.map { it.preset.id })
            return list.sortedBy { (cooling[it.preset.id]?.until ?: 0) > now.now() }
        }
        var remaining = ordered(candidates.take(3)).toMutableList()
        var attempts = 0
        val errors = linkedMapOf<String, Exception>()
        var minimumCapacity = -1
        val initialReason = store.cooling(primary.id)?.reason
        while (remaining.isNotEmpty() && attempts < 3) {
            val candidate = remaining.removeAt(0)
            val preset = candidate.preset
            if (capacity(preset) <= minimumCapacity) continue
            attempts++
            try {
                val result = call(candidate)
                if (preset.id != primary.id) switched(preset, errors.values.firstOrNull()?.let { classify(it)?.reason } ?: initialReason ?: "主模型暂不可用")
                return result to preset
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                val failure = classify(e) ?: throw e
                if (!enabled || !canSwitch() || failure.format) throw e
                errors[preset.id] = e
                if (failure.capacity) {
                    minimumCapacity = maxOf(minimumCapacity, capacity(preset))
                    remaining = ordered(candidates.filter { it.preset.id !in errors && capacity(it.preset) > minimumCapacity }).toMutableList()
                } else if (!failure.rejected) {
                    store.coolIfCurrent(preset.id, candidate.generation, FailoverCooling(
                        now.now() + (if (failure.longCooling) 30 else 5) * 60_000L, failure.reason, safeCoolingError(e)))
                }
            }
        }
        val error = errors[primary.id] ?: store.cooling(primary.id)?.error ?: errors.values.first()
        throw exhausted(error, if (candidates.size > 1) "；备用模型也没成功" else "")
    }
}
