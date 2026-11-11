package app.resound.devices

/** Optional provenance for private streams; counters advance only after VAD accepts a frame. */
internal interface AudioTimeline {
    val epochMillis: Long?
    val archiveSourceId: String?
    fun processed(at: Long?, samples: Int)
    fun finishCapture()
}
