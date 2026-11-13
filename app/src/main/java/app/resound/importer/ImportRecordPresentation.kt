package app.resound.importer

fun ImportRecordWithSegments.record() = ImportRecord(row.id, row.fileName, ImportKind.valueOf(row.kind), row.startAt, row.endAt,
        ImportRecordStatus.valueOf(row.status), row.error, row.cardId,
        segments.sortedWith(compareBy({ it.startAt }, { it.importChannel }, { it.id })).map { it.id }, row.byChannel)
