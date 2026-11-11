package app.resound.devices.card

import app.resound.devices.*
import app.resound.devices.driver.*
import kotlinx.coroutines.flow.*

/** 启用状态由说明卡目录决定；Trial 默认关闭的规则仍由 DeviceCards 保存。 */
class CardDriver(
    val card: DeviceCard,
    private val enabled: Boolean,
    private val filePlugin: FileSourcePlugin? = null,
    private val protocol: LiveProtocol = object : LiveProtocol {},
    private val report: (LinkDiagnostics) -> Unit = {},
    private val readTimeout: Long = 2_000,
    private val zeroLimitMillis: Long = 20_000,
    private val checksum: ChecksumAlgo? = null,
) : DeviceDriver {
    override val meta = DriverMeta("card:${card.id}", DriverTier.Card,
        if (card.status == CardStatus.Verified) Stability.Stable else Stability.Trial, LocalizedText(card.name))

    override fun match(ad: Advertisement): MatchConfidence {
        val rule = card.match
        return if (enabled && (rule.namePrefix.any { ad.name?.startsWith(it) == true } ||
            rule.serviceUuid.any { uuid -> ad.services.any { it.equals(uuid, true) } } ||
            rule.manufacturerId in ad.manufacturerData.keys)) MatchConfidence.Certain else MatchConfidence.None
    }

    override suspend fun open(session: DriverSession): DriverConnection {
        if (!enabled) throw DeviceException(ErrorCode.Unsupported)
        val identity = (session.link as? CardLink)?.identity ?: throw DeviceException(ErrorCode.BadArgs, "card identity missing")
        return object : DriverConnection {
            override val identity = identity
            override val status = MutableStateFlow(DeviceStatusModel())
            override val closed = MutableSharedFlow<DeviceException>(replay = 1)
            private fun battery(value: Int) {
                status.update { it.copy(batteries = listOf(BatteryPart(1, percent = value, readAtMillis = session.clock.nowEpochMillis()))) }
            }
            override val live: LiveAudio? = card.audio?.let {
                CardLiveAudio(card, session, protocol, ::battery, report, { closed.tryEmit(it) }, readTimeout, zeroLimitMillis)
            }
            override val files: DeviceFiles? = if (card.offline == OfflineMode.Files && filePlugin != null)
                CardFiles(filePlugin, CardDeviceLink(session.link), checksum) else null
            override val capabilities = buildSet {
                if (live != null) add(Capability.LiveAudio)
                if (files != null) add(Capability.Files)
                if (card.battery != null) add(Capability.Battery)
                if (card.clock != null) add(Capability.Clock)
            }
            override val controls = object : DeviceControls {
                override suspend fun syncClock(epochMillis: Long, zoneOffsetMinutes: Int) {
                    val spec = card.clock ?: throw DeviceException(ErrorCode.Unsupported)
                    session.link.write(spec.service, spec.write, clockBytes(spec.format, epochMillis))
                }
                override suspend fun refresh() {
                    val spec = card.battery ?: throw DeviceException(ErrorCode.Unsupported)
                    val bytes = session.link.read(if (spec.standard) CardLiveAudio.BATTERY_SERVICE else requireNotNull(spec.service),
                        if (spec.standard) CardLiveAudio.BATTERY_LEVEL else requireNotNull(spec.read))
                    bytes.singleOrNull()?.toInt()?.and(255)?.takeIf { it in 0..100 }?.let(::battery)
                }
            }
            override val settings: DeviceSettings? = null
            override val policy = DriverPolicy()
            override val binding: DeviceBinding? = null
            override suspend fun close() { live?.stop() }
        }
    }
}

/** Unix32 为秒、指定端序；BCD 固定 UTC 年/月/日/时/分/秒，与旧编码一致。 */
fun clockBytes(format: ClockFormat, now: Long): ByteArray {
    if (format != ClockFormat.Bcd) {
        val seconds = now / 1000
        return ByteArray(4) { i -> (seconds ushr (8 * if (format == ClockFormat.Unix32Le) i else 3 - i)).toByte() }
    }
    val seconds = now.floorDiv(1000)
    val days = seconds.floorDiv(86400)
    val daySeconds = seconds.mod(86400).toInt()
    // 公历 civil-from-days；不依赖平台时区或日期库。
    val z = days + 719468
    val era = z.floorDiv(146097)
    val doe = (z - era * 146097).toInt()
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    var year = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = mp + if (mp < 10) 3 else -9
    if (month <= 2) year++
    return listOf((year % 100).toInt(), month, day, daySeconds / 3600, daySeconds / 60 % 60, daySeconds % 60)
        .map { ((it / 10 shl 4) or (it % 10)).toByte() }.toByteArray()
}
