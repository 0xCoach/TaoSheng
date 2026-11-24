package app.resound.platform

/** 平台提供给共用核心的插口。 */
data class PlatformServices(
    val now: NowMillis,
    val zone: ZoneProvider,
    val logger: Logger,
    val keyValueStore: KeyValueStore,
    val files: FileStore,
    val dispatchers: AppDispatchers,
    val entitlements: Entitlements,
)
