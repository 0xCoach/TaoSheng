package app.resound.review
import app.resound.digest.LlmInputBudget
import app.resound.digest.LlmPreset
internal object ReviewInputBudget {
    fun fit(input: String, system: String, preset: LlmPreset) = ReviewInputBudgetRules.fit(input, system, LlmInputBudget.inputChars(preset))
}
