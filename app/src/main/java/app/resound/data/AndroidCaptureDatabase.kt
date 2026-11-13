package app.resound.data

import android.content.Context
import androidx.room.Room

fun CaptureDatabase.Companion.open(context: Context, name: String = "capture.db") = Room.databaseBuilder(context, CaptureDatabase::class.java, name)
            .setDriver(androidx.sqlite.driver.AndroidSQLiteDriver())
            .addMigrations(*CaptureDatabase.MIGRATIONS)
            .addCallback(app.resound.search.SearchIndex.callback).addCallback(app.resound.importer.ImportRecordSchema.callback)
            .addCallback(app.resound.things.ThingSchema.callback).addCallback(PendingDigestSchema.callback).build()
            .also { it.filePath = context.getDatabasePath(name).absolutePath }
