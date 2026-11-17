package app.resound.importer

fun ImportRecordWithSegments.record() = ImportRecord(row.id, row.fileName, ImportKind.valueOf(row.kind), row.startAt, row.endAt,
        ImportRecordStatus.valueOf(row.status), row.error, row.cardId,
        segments.sortedWith(compareBy({ it.startAt }, { it.importChannel }, { it.id })).map { it.id }, row.byChannel)

/** 只向纪要提供名字，不能用 display（它允许退回电话号码）。 */
internal suspend fun callPromptName(db: app.resound.data.CaptureDatabase, id: Long): String? {
    val row = db.folderRecordings().byImport(id) ?: return null
    val label = db.folderRecordings().labelSnapshot().firstOrNull { it.number == row.number }?.name
    return CallNamePolicy.prompt(label,row.partyName)
}
