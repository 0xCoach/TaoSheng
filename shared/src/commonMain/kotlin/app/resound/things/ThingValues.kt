package app.resound.things

data class ThingData(val id: Long = 0, val name: String,
    val aliasesJson: String = "[]", val owner: String, val createdAt: Long, val updatedAt: Long, val deleted: Boolean = false)

data class ThingRecordData(val id: Long = 0, val thingId: Long? = null,
    val name: String, val action: String, val place: String?, val who: String?, val owner: String,
    val at: Long, val sourceLineId: Long?, val sourceText: String?, val status: String = "Pending",
    val origin: String = "Live", val explicit: Boolean = false, val sameAsId: Long? = null,
    val createdAt: Long, val acceptedAt: Long? = null, val aliasesJson: String = "[]")

data class ThingWatchData(val slot: Int = 1, val watermark: Long = 0,
    val enabled: Boolean = true, val revision: Long = 0, val failedEnd: Long? = null, val failures: Int = 0)

data class ThingLine(val id: Long, val startAt: Long, val endAt: Long, val segmentId: Long,
    val speakerName: String?, val speakerPersonId: Long?, val text: String, val timeText: String = "")
data class ThingProposalData(val row: ThingRecordData, val confident: Boolean)
