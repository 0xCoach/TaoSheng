package app.resound.push

object PushTemplates {
    fun textOnly() = PushSubscription(summaries = false, records = RecordKind.entries.toSet(), lines = LinesMode.All)
    fun keepRecordings() = PushSubscription(summaries = false, reminders = false, records = emptySet(),
        lines = LinesMode.Off, audio = AudioPush.Speech, audioText = AudioText.Daily, excludeMedia = true,
        audioWifiOnly = true, audioChargingOnly = true, backfillDays = null, fullCheckAt = null)
}
