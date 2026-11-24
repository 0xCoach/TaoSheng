package app.resound.data

import androidx.room.RoomDatabase
import app.resound.importer.ImportRecordSchema
import app.resound.search.SearchIndex
import app.resound.things.ThingSchema

/** 供各平台建库入口注册同一组迁移和回调。 */
fun RoomDatabase.Builder<CaptureDatabase>.configureCapture(): RoomDatabase.Builder<CaptureDatabase> =
    addMigrations(*CaptureDatabase.MIGRATIONS)
        .addCallback(SearchIndex.callback)
        .addCallback(ImportRecordSchema.callback)
        .addCallback(ThingSchema.callback)
        .addCallback(PendingDigestSchema.callback)
