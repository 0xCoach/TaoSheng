package app.resound.data

import android.content.Context
import androidx.room.Room

private val mainStats = app.resound.diagnostics.DatabaseQueryStats()
internal val captureQueryStats get() = mainStats

fun CaptureDatabase.Companion.open(context: Context, name: String = "capture.db") = Room.databaseBuilder(context, CaptureDatabase::class.java, name)
            .setDriver(androidx.sqlite.driver.AndroidSQLiteDriver().let { driver ->
                if (name == "capture.db") app.resound.diagnostics.QueryStatsDriver(driver, mainStats) else driver
            })
            .configureCapture()
            .build()
            .also { it.filePath = context.getDatabasePath(name).absolutePath }
