package app.resound.runtime

import app.resound.data.CaptureDatabase
import app.resound.platform.PlatformServices

class CoreRuntime internal constructor(
    val platform: PlatformServices,
    database: Lazy<CaptureDatabase>,
) {
    val database: CaptureDatabase by database
}

/** 由平台决定何时首次取库，装配时保留已有的惰性实例。 */
fun createCoreRuntime(platform: PlatformServices, database: Lazy<CaptureDatabase>): CoreRuntime =
    CoreRuntime(platform, database)
