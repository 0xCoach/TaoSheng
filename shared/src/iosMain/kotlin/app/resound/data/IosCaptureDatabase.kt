package app.resound.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

fun openCaptureDatabase(path: String): CaptureDatabase =
    Room.databaseBuilder<CaptureDatabase>(name = path)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .configureCapture()
        .build()
        .also { it.filePath = path }
