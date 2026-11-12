package app.resound.digest
/** 安卓同步配置快照；容量计算和目录解析来自 shared。 */
internal object LlmInputBudget {
    const val DEFAULT_CONTEXT_TOKENS = InputCapacity.DEFAULT_CONTEXT_TOKENS
    private var catalog = emptyMap<Pair<String, String>, InputCapacity.Capacity>()
    private val snapshots = mutableMapOf<LlmPreset, InputCapacity.Capacity>()
    fun parse(text: String) = InputCapacity.parse(text)
    @Synchronized fun configure(text: String) { catalog = parse(text) }
    @Synchronized fun bind(preset: LlmPreset, templateId: String?): LlmPreset {
        snapshots[preset] = catalog[templateId to preset.model] ?: InputCapacity.Capacity(DEFAULT_CONTEXT_TOKENS)
        return preset
    }
    @Synchronized fun capacity(preset: LlmPreset) = snapshots[preset] ?: InputCapacity.Capacity(DEFAULT_CONTEXT_TOKENS)
    fun inputChars(preset: LlmPreset) = capacity(preset).inputChars
}
