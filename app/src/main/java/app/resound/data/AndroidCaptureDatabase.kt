package app.resound.data

import app.resound.importer.ImportRecordSchema
import app.resound.search.SearchIndex
import app.resound.things.ThingSchema

import android.content.Context
import androidx.room.Room

fun CaptureDatabase.Companion.open(context: Context, name: String = "capture.db") = Room.databaseBuilder(context, CaptureDatabase::class.java, name)
            .setDriver(androidx.sqlite.driver.AndroidSQLiteDriver())
            .addMigrations(*CaptureDatabase.MIGRATIONS)
            .addCallback(SearchIndex.callback).addCallback(ImportRecordSchema.callback)
            .addCallback(ThingSchema.callback).addCallback(PendingDigestSchema.callback).build()
            .also { it.filePath = context.getDatabasePath(name).absolutePath }
