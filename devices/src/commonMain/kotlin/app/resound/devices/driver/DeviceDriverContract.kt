package app.resound.devices.driver

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/*
 * 外设驱动约定（卡 138，2026-11-11）。设计说明见 docs/方案/外设驱动插口设计.md，协议见 docs/taoecho/。
 *
 * 一句话：核心只认 TaoEcho 模型。2 级 TaoEcho 设备、3 级说明卡、4 级插件都实现 [DeviceDriver]，
 * 把各自的设备翻译成 [DriverConnection]；蓝牙、存储、加密、时间、后台、界面全部由核心通过 [DriverSession] 提供。
 *
 * 规则（自动检查会守住）：
 *  - 本模块（commonMain）不引用 android.*、java.*、javax.*、org.json、OkHttp；只用 Kotlin 标准库和 kotlinx.coroutines。
 *  - 驱动只能用 [DriverSession] 里给的东西：不能自己扫描、不能另开连接、不能联网、不能读写手机文件、不能画界面。
 *  - 品牌名只出现在 plugins/<品牌>/ 和设备数据文件里。
 *  - 改本文件签名需 Claude 确认（同 *Contract.kt 约定）。
 */

// ============================== 基础类型 ==============================

/** 不透明的设备身份。TaoEcho 设备用 HELLO 里的序列号；老设备由驱动给替代身份（见 [IdScope]）。不要假定它是 MAC 地址。 */
data class DeviceId(val value: String) {
    init { require(value.isNotBlank() && value.length <= 128) { "设备身份为空或过长" } }
}

/** Global：设备自报、全球唯一（换手机也一样）；PlatformLocal：只在这部手机这个平台上稳定（如安卓蓝牙地址） */
enum class IdScope { Global, PlatformLocal }

/** 中英文两份文字。设备和驱动不发给人看的句子，界面文字由 App 按语言选 */
data class LocalizedText(val zh: String, val en: String = zh)

enum class Platform { Android, Ios }

/** 设备分级：System=1 级（不经过这里），TaoEcho=2 级，Card=3 级，Plugin=4 级 */
enum class DriverTier { TaoEcho, Card, Plugin }

enum class Stability { Stable, Trial }

/** 设备时间。三项都可能未知；核心据此换算到真实时间，换算不了就用收到的时刻估算 */
data class DeviceTime(
    /** 设备认为的 UTC 毫秒；没对过时为 null */
    val epochMillis: Long? = null,
    /** 设备启动编号（每次开机加 1）；不支持为 null */
    val bootId: Long? = null,
    /** 本次开机后经过的毫秒数 */
    val sinceBootMillis: Long? = null,
)

// ============================== 驱动本身 ==============================

interface DeviceDriver {
    val meta: DriverMeta
    /** 看一条广播，回答是不是我的设备。只能看广播内容，不能自己扫描或连接 */
    fun match(ad: Advertisement): MatchConfidence
    /** 核心连上设备后调用；返回的连接由核心持有，断开或出错时核心调用 [DriverConnection.close] */
    suspend fun open(session: DriverSession): DriverConnection
}

data class DriverMeta(
    /** 驱动编号：小写字母、数字、横线、冒号，如 taoecho、card:omi、d3200 */
    val id: String,
    val tier: DriverTier,
    val stability: Stability,
    /** 显示名，如“安克 / 飞书录音豆 D3200” */
    val name: LocalizedText,
    /** 别名，搜索用（如“安克录音豆”“飞书录音豆”） */
    val aliases: List<String> = emptyList(),
    /** 设备图片在设备数据里的路径；没有为 null */
    val image: String? = null,
    /** 配对步骤，一步一条 */
    val pairingSteps: List<LocalizedText> = emptyList(),
    /** 维护人（GitHub 账号）；4 级必填 */
    val maintainer: String? = null,
    /** 能在哪些平台工作；用到 [PlatformExtras] 的功能另在 [Capability] 层面标注 */
    val platforms: Set<Platform> = setOf(Platform.Android, Platform.Ios),
) {
    init { require(Regex("[a-z0-9:-]{1,64}").matches(id)) { "驱动编号不合法：$id" } }
}

/** 平台无关的广播内容。iOS 拿不到地址，[platformHandle] 只在本机本平台有意义 */
data class Advertisement(
    val platformHandle: String,
    val name: String?,
    /** 广播里的服务编号，完整 128 位小写 */
    val services: Set<String>,
    /** 厂商编号 → 厂商数据 */
    val manufacturerData: Map<Int, ByteArray>,
    val rssi: Int?,
)

/** Certain：确定是我的；Possible：可能是（让用户看图选）；None：不是 */
enum class MatchConfidence { Certain, Possible, None }

// ============================== 核心交给驱动的东西 ==============================

interface DriverSession {
    val link: GattLink
    val toolbox: Toolbox
    /** 本驱动、本设备专用的小块存储；核心负责备份和清理 */
    val store: DriverStore
    val clock: ClockPort
    /** 平台专有能力，默认全空；用到的功能必须在能力上标“仅安卓” */
    val extras: PlatformExtras
    /** 定时任务；核心限制最短间隔（不足按最短间隔执行）和同时运行数，连接断开自动取消 */
    fun schedule(name: String, everyMillis: Long, task: suspend () -> Unit): ScheduledTask
    /** 诊断日志，进诊断导出；不要写录音内容和密钥 */
    fun log(level: LogLevel, message: String)
}

enum class LogLevel { Debug, Info, Warn, Error }

interface ScheduledTask { fun cancel() }

/** 蓝牙收发把手。所有操作由核心排队执行；驱动不能扫描、不能另开连接 */
interface GattLink {
    /** 当前一次写入最多多少字节（iOS 由系统决定，不能指定）。超过会抛 [DeviceException]，平台层不会悄悄拆包 */
    fun maxWriteLength(withResponse: Boolean): Int
    suspend fun read(service: String, characteristic: String): ByteArray
    suspend fun write(service: String, characteristic: String, bytes: ByteArray, withResponse: Boolean = true)
    /** 订阅通知；多次调用同一通道共享同一订阅 */
    fun notifications(service: String, characteristic: String): Flow<ByteArray>
}

interface DriverStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getBytes(key: String): ByteArray?
    fun putBytes(key: String, value: ByteArray?)
    fun keys(): Set<String>
}

interface ClockPort {
    /** 墙上时间（UTC 毫秒），可能被用户改 */
    fun nowEpochMillis(): Long
    /** 单调计时（毫秒），只用来算间隔 */
    fun monotonicMillis(): Long
    /** 当前时区相对 UTC 的分钟数 */
    fun zoneOffsetMinutes(): Int
}

interface PlatformExtras {
    /** 经典蓝牙串口（仅安卓；iOS 永远为 null）。录音豆灯控等用 */
    val classicSerial: ClassicSerialPort? get() = null
}

interface ClassicSerialPort {
    /** 按服务编号打开；地址由核心根据当前设备提供，驱动拿不到 */
    suspend fun open(serviceUuid: String): SerialChannel
}

interface SerialChannel {
    suspend fun send(bytes: ByteArray)
    val incoming: Flow<ByteArray>
    suspend fun close()
}

// ============================== 工具箱（平台各自实现） ==============================

interface Toolbox {
    /** 每个会话自己建、自己关 */
    fun opusDecoder(sampleRate: Int, channels: Int): OpusDecoder
    val crypto: CryptoPort
}

interface OpusDecoder {
    /** 一个裸 Opus 包 → 16 位 PCM（交错存放多声道） */
    fun decode(packet: ByteArray): ShortArray
    fun close()
}

/** 加密插口。字节编码写死，换平台不许改：公钥为 65 字节未压缩点（04||X||Y），ECDSA 签名为 DER，公钥验签用 SPKI DER */
interface CryptoPort {
    fun randomBytes(size: Int): ByteArray
    fun sha256(data: ByteArray): ByteArray
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray
    fun p256KeyPair(): P256KeyPair
    /** 返回共享密钥（32 字节 X 坐标） */
    fun p256Ecdh(own: P256KeyPair, peerPublic: ByteArray): ByteArray
    fun p256VerifyDer(spkiPublicKey: ByteArray, data: ByteArray, derSignature: ByteArray): Boolean
    /** AES-CTR，iv 为 16 字节初始计数块（大端递增） */
    fun aesCtr(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray
    /** AES-GCM，输出 = 密文 || 16 字节标签 */
    fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray
    /** 标签不对抛 [DeviceException]（ErrorCode.CryptoFailed） */
    fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray
}

/** 私钥只以句柄形式存在，驱动拿不到私钥字节 */
interface P256KeyPair {
    /** 65 字节未压缩公钥 */
    val publicKey: ByteArray
}

// ============================== 驱动交给核心的东西（TaoEcho 模型） ==============================

interface DriverConnection {
    val identity: DeviceIdentity
    val capabilities: Set<Capability>
    /** 有 [Capability.LiveAudio] 时非空 */
    val live: LiveAudio?
    /** 有 [Capability.Files] 时非空 */
    val files: DeviceFiles?
    /** 电量、存储、录音状态；读不到的项为 null（界面显示“未知”） */
    val status: StateFlow<DeviceStatusModel>
    val controls: DeviceControls
    /** 有 [Capability.Settings] 时非空 */
    val settings: DeviceSettings?
    val policy: DriverPolicy
    /** null 表示设备不支持绑定（老设备）；TaoEcho 设备必须支持 */
    val binding: DeviceBinding?
    /** 设备主动断开或驱动发现协议错误时结束（带原因）；核心据此重连 */
    val closed: Flow<DeviceException>
    suspend fun close()
}

data class DeviceIdentity(
    val id: DeviceId,
    val scope: IdScope,
    val vendor: String? = null,
    val model: String? = null,
    /** 各部件固件版本（部件编号同 [BatteryPart.part]） */
    val firmware: List<PartVersion> = emptyList(),
)

data class PartVersion(val part: Int, val version: String)

enum class Capability {
    LiveAudio, Files, Battery, Record, Light, Storage, Button, Clock, Identify, Diagnostics, Settings,
    /** 灯光等功能只能在安卓用（走经典蓝牙）；界面在 iOS 上隐藏 */
    AndroidOnlyLight,
}

// ---------- 实时声音 ----------

interface LiveAudio {
    /** 开始收音；返回后 [frames] 开始出数据 */
    suspend fun start()
    suspend fun stop()
    /** 已解码的声音：一律 16 kHz 单声道 16 位 PCM（双声道由驱动合成单声道） */
    val frames: Flow<AudioChunk>
}

data class AudioChunk(
    val pcm: ShortArray,
    /** 这一块第一个采样的设备时间；不知道为 null */
    val deviceTime: DeviceTime? = null,
    /** 这一块之前发现丢了多少个采样（序号跳号换算）；0 表示连续 */
    val lostSamplesBefore: Int = 0,
) {
    override fun equals(other: Any?) = other is AudioChunk && pcm.contentEquals(other.pcm) &&
        deviceTime == other.deviceTime && lostSamplesBefore == other.lostSamplesBefore
    override fun hashCode() = 31 * pcm.contentHashCode() + lostSamplesBefore
}

// ---------- 离线文件 ----------

interface DeviceFiles {
    val caps: FileCaps
    /** 按开始时间从旧到新 */
    suspend fun list(): List<RecordingFile>
    /** 从 [fromOffset] 开始读，一块一块交出；caps.resume=false 时核心只传 0 */
    fun read(file: RecordingFile, fromOffset: Long): Flow<ByteArray>
    /** 核心已安全保存且校验通过后调用（TaoEcho FILE_CONFIRM）。caps.confirm=false 时不调用 */
    suspend fun confirm(file: RecordingFile, checksum: ByteArray?)
    /** 只会在 confirm 成功之后调用；caps.delete=false 时不调用 */
    suspend fun delete(file: RecordingFile)
}

data class FileCaps(
    val resume: Boolean,
    /** 设备能接收“已安全保存”确认 */
    val confirm: Boolean,
    val delete: Boolean,
    val checksum: ChecksumAlgo?,
    val reclaim: Reclaim,
)

enum class ChecksumAlgo { Crc32, Sha256 }

/** Auto：设备空间不够时自动删已确认的最早录音；Manual：等核心调用 delete；Never：设备从不删（如“保留原文件”） */
enum class Reclaim { Auto, Manual, Never }

data class RecordingFile(
    /** 设备内唯一、删除后不复用 */
    val id: String,
    val state: FileState,
    val bytes: Long?,
    val format: AudioFileFormat,
    val start: DeviceTime,
    val durationMillis: Long?,
    val checksum: ByteArray? = null,
) {
    override fun equals(other: Any?) = other is RecordingFile && id == other.id && state == other.state &&
        bytes == other.bytes && format == other.format && start == other.start &&
        durationMillis == other.durationMillis && sameBytes(checksum, other.checksum)
    override fun hashCode() = id.hashCode()
}

enum class FileState { Recording, Complete, Confirmed }

enum class AudioFileFormat { Opus20, Pcm16k, Pcm8k, OggOpus, Wav, Mp3, M4a, Aac }

// ---------- 状态 ----------

data class DeviceStatusModel(
    val batteries: List<BatteryPart> = emptyList(),
    val storage: StorageInfo? = null,
    val recording: RecordingState? = null,
)

data class BatteryPart(
    /** 1 主机、2 充电盒、3–15 其他（与 TaoEcho 一致） */
    val part: Int,
    val name: LocalizedText? = null,
    /** 0–100；未知为 null */
    val percent: Int? = null,
    val charging: Boolean? = null,
    /** 这条读数的时间（墙上时间）；用来显示“5 分钟前” */
    val readAtMillis: Long? = null,
)

data class StorageInfo(val totalBytes: Long? = null, val freeBytes: Long? = null, val unconfirmedBytes: Long? = null)

enum class RecordingState { Recording, Paused, Stopped }

// ---------- 控制 ----------

/** 每个控制都返回设备确认后的实际状态（TaoEcho R3）。没有对应能力的设备，核心不会调用 */
interface DeviceControls {
    suspend fun setRecording(target: RecordingState): RecordingState = unsupported()
    /** 支持的灯光模式（含“关闭”）；是否在某平台提供“关闭”由核心按产品策略决定 */
    suspend fun lightModes(): List<LightMode> = unsupported()
    suspend fun setLight(modeId: Int): LightMode = unsupported()
    suspend fun syncClock(epochMillis: Long, zoneOffsetMinutes: Int): Unit = unsupported()
    suspend fun identify(seconds: Int): Unit = unsupported()
    /** 立刻重读电量、存储、录音状态并更新 [DriverConnection.status] */
    suspend fun refresh(): Unit = unsupported()
    /** 诊断数据原样交给核心，核心只保存、供导出 */
    suspend fun diagnostics(): ByteArray = unsupported()
}

data class LightMode(val id: Int, val kind: LightKind, val name: LocalizedText)

enum class LightKind { Default, Off, Dim, Preset }

// ---------- 设置项 ----------

interface DeviceSettings {
    suspend fun schema(): List<SettingItem>
    /** 返回设备确认后的实际值 */
    suspend fun set(key: String, value: SettingValue): SettingValue
}

data class SettingItem(
    val key: String,
    val kind: SettingKind,
    val label: LocalizedText,
    val value: SettingValue?,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val unit: String? = null,
    val options: List<SettingOption> = emptyList(),
)

enum class SettingKind { Switch, Number, Choice, ReadOnly }

data class SettingOption(val id: Int, val label: LocalizedText)

sealed interface SettingValue {
    data class Bool(val value: Boolean) : SettingValue
    data class Num(val value: Double) : SettingValue
    data class Choice(val id: Int) : SettingValue
    data class Text(val value: String) : SettingValue
}

// ---------- 绑定 ----------

interface DeviceBinding {
    suspend fun isBound(): Boolean
    /**
     * 绑定。驱动在需要用户动作时调用 [prompt]：PressButton 时界面提示“按一下设备上的键”，
     * ConfirmBlink 时界面问“设备在闪灯吗？”并返回用户的回答。
     */
    suspend fun bind(prompt: suspend (BindPrompt) -> Boolean)
    suspend fun unbind()
}

enum class BindPrompt { PressButton, ConfirmBlink }

// ---------- 驱动偏好 ----------

data class DriverPolicy(
    /** 设备在实时收音时读不了文件：核心先补传再开始实时收音 */
    val backfillBeforeLive: Boolean = false,
    /** 补传时新录音优先 */
    val newestFirst: Boolean = false,
    /** 补传期间不允许控制命令和定时查询 */
    val exclusiveBackfill: Boolean = false,
)

// ============================== 错误 ==============================

/** 前 13 个与 TaoEcho 错误码一一对应（见规范第 9 节），后面是 App 侧原因 */
enum class ErrorCode {
    Unsupported, BadArgs, NotBound, AlreadyBound, BindTimeout, BusyRetry, NotFound, NotConfirmed,
    ChecksumMismatch, StorageFull, LowBattery, StateConflict, VendorError,
    Disconnected, Timeout, ProtocolError, CryptoFailed, WriteTooLong, PermissionDenied,
}

/** 驱动和平台层只抛这一种异常；界面文字由核心按 [code] 翻译，[detail] 只进诊断日志 */
class DeviceException(val code: ErrorCode, val detail: String = "", cause: Throwable? = null) : Exception(detail, cause)

private fun unsupported(): Nothing = throw DeviceException(ErrorCode.Unsupported)

private fun sameBytes(a: ByteArray?, b: ByteArray?): Boolean = if (a == null || b == null) a === b else a.contentEquals(b)

// ============================== 驱动目录 ==============================

/** 装配层把所有驱动放进来；核心据此分发广播、查冲突 */
interface DriverCatalog {
    val drivers: List<DeviceDriver>
}
