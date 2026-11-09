package app.resound.devices

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/*
 * 外设接入约定（0.2.21）。界面（Claude）与插件只依赖这里；核心（Codex）实现。改签名需 Claude 确认。
 * 字段的含义、例子与已定规则见 docs/方案/外设接入字段表.md。
 *
 * 三层接法：
 *   1. 普通蓝牙 / 有线 / USB 麦克风：走系统，不经过这里（audio/ 包）。
 *   2. 实时传输设备：一张说明卡 [DeviceCard]（纯数据），核心照卡收发蓝牙，产出标准的 [app.resound.audio.AudioSource]。
 *      “实时供音”插口就是现有的 AudioSource，签名不变。
 *   3. 带存储的设备：说明卡 offline=Files + 一个文件插口 [FileSourcePlugin]（轻量代码，只管列文件、下载、删除）。
 *
 * 平台无关：本文件不引用 android.*。插件只通过 [DeviceLink] 收发字节，不直接碰系统蓝牙接口，
 * 以后换 iOS / 鸿蒙只重写 DeviceLink 的实现。核心与插件里都不许出现具体品牌名（说明卡和插件自己的文件夹除外）。
 */

// ============================== 说明卡（数据） ==============================

data class DeviceCard(
    /** 唯一编号：小写字母、数字、横线 */
    val id: String,
    val name: String,
    /** 说明卡自己的版本；远程参数表据此判断要不要更新 */
    val version: Int,
    /** 最低涛声版本，如 "0.2.21"；App 太旧不加载 */
    val minApp: String,
    val status: CardStatus,
    val transport: Transport,
    val match: CardMatch,
    val link: LinkParams = LinkParams(),
    val audio: CardAudio? = null,
    val battery: CardBattery? = null,
    val clock: CardClock? = null,
    val offline: OfflineMode = OfflineMode.None,
    /** offline=Files 时用哪个文件插口（[FileSourcePlugin.id]） */
    val filePlugin: String? = null,
)

/** Verified：官方真机验证过；Trial：试用，用户在设备页手动打开才生效 */
enum class CardStatus { Verified, Trial }

/** 第一版只有蓝牙；以后加 Wifi 不改别的字段 */
enum class Transport { Ble }

/** 满足任一条即认为是这台设备 */
data class CardMatch(
    val namePrefix: List<String> = emptyList(),
    /** 广播里带的服务编号（完整 128 位，小写） */
    val serviceUuid: List<String> = emptyList(),
    val manufacturerId: Int? = null,
)

data class LinkParams(
    /** 连接后请求的包大小；null 用系统默认 */
    val mtu: Int? = null,
    val bond: Boolean = false,
    /** 定时心跳间隔；0 不需要 */
    val keepAliveSec: Int = 0,
    /** 心跳命令（keepAliveSec>0 时必填） */
    val keepAlive: CardCommand? = null,
)

data class CardAudio(
    val service: String,
    /** 订阅这个通道收声音包 */
    val notify: String,
    /** 发命令的通道；没有命令可为 null */
    val write: String? = null,
    val start: CardCommand? = null,
    val stop: CardCommand? = null,
    /** 先读这个通道得到格式编号，再用 [codecMap] 对照；与 [codec] 二选一 */
    val codecRead: String? = null,
    val codecMap: Map<Int, Codec> = emptyMap(),
    val codec: Codec? = null,
    val sampleRate: Int = 16_000,
    val channels: Int = 1,
    val frame: FrameSpec,
)

/** 发给设备的命令：直接写字节，或按包外壳组装（核心补上开头标记、序号、长度、校验） */
sealed interface CardCommand {
    class Raw(val bytes: ByteArray) : CardCommand
    class Framed(val type: Int, val cmd: Int, val payload: ByteArray = ByteArray(0)) : CardCommand
}

/** 第一版支持的声音格式；Opus 的两种帧长编号不同，必须写清 */
enum class Codec { Pcm16, Pcm8, Mulaw, Opus10, Opus20 }

/** 设备发来的每个包长什么样 */
data class FrameSpec(
    /** 从前到后的字段；空表示整包都是声音 */
    val fields: List<FrameField> = emptyList(),
    val payload: PayloadSpec = PayloadSpec.Rest,
    /** 一帧声音分几个包发时怎么拼回去；null 表示一包一帧 */
    val fragment: FragmentSpec? = null,
    val opus: OpusPacking = OpusPacking.One,
    /** 字节顺序为 [Endian.Probe] 时，连上后发这条命令，按应答里校验能对上的顺序锁定 */
    val probe: CardCommand? = null,
)

data class FrameField(
    val name: String,
    val bytes: Int,
    val endian: Endian = Endian.Le,
    /** 固定值（如开头标记），对不上就丢弃这个包 */
    val const: ByteArray? = null,
    val role: FieldRole = FieldRole.Other,
    /** role=Crc 时必填 */
    val crc: CrcSpec? = null,
)

enum class Endian { Le, Be, Probe }
enum class FieldRole { Magic, Seq, Len, Crc, Type, Cmd, Frag, Other }

data class CrcSpec(val algo: CrcAlgo, /** 覆盖哪些字段，按名字；"payload" 表示声音部分 */ val over: List<String>)
enum class CrcAlgo { Crc16Xmodem, Crc16Modbus, Crc32 }

sealed interface PayloadSpec {
    /** 字段之后剩下的全是声音 */
    data object Rest : PayloadSpec
    /** 声音长度由某个字段给出 */
    data class LengthFrom(val field: String) : PayloadSpec
}

data class FragmentSpec(/** 分片序号字段名 */ val indexField: String, /** 新一帧从几开始 */ val restartAt: Int = 0)

/** One：一个包一帧；LengthPrefixed：一个包里多帧、每帧前面带 1 字节长度 */
enum class OpusPacking { One, LengthPrefixed }

data class CardBattery(
    /** true：标准电池服务；false：用下面的自定义读法 */
    val standard: Boolean = true,
    val service: String? = null,
    val read: String? = null,
)

data class CardClock(val service: String, val write: String, val format: ClockFormat)
enum class ClockFormat { Unix32Le, Unix32Be, Bcd }

/** None：断开不存，换别的输入；Files：设备自己存成文件，重连后由文件插口补传 */
enum class OfflineMode { None, Files }

// ============================== 说明卡管理 ==============================

data class InstalledCard(
    val card: DeviceCard,
    val source: CardSource,
    /** Verified 默认 true；Trial 默认 false，用户手动打开 */
    val enabled: Boolean,
)

/** Bundled：包里自带；Remote：远程参数表下发（必须官方签名，验签失败不加载） */
enum class CardSource { Bundled, Remote }

interface DeviceCards {
    val cards: StateFlow<List<InstalledCard>>
    /** 解析并校验一张卡；字段超出字段表范围即失败，说明写进 Result */
    fun parse(json: String): Result<DeviceCard>
    fun setEnabled(cardId: String, enabled: Boolean)
}

// ============================== 插口二：文件来源 ==============================

/**
 * 带存储设备的文件插口。插件只做三件事：列文件、从某个位置下载、删文件。
 * 排队、断点续传、校验、去重、存好才删、格式转换、对时换算、通知、保活全部由核心做。
 * 插件不能联网、不能读写手机文件；只能通过 [DeviceLink] 和设备说话。
 */
interface FileSourcePlugin {
    /** 与说明卡里的 filePlugin 对应 */
    val id: String
    val caps: FileCaps
    /** 连上后调用一次（比如探测字节顺序、读设备信息） */
    suspend fun open(link: DeviceLink) {}
    suspend fun list(link: DeviceLink): List<DeviceFile>
    /** 从 [fromOffset] 字节开始下载，一块一块交出；caps.resume=false 时核心只会传 0 */
    fun download(link: DeviceLink, file: DeviceFile, fromOffset: Long): Flow<ByteArray>
    /** 只在核心确认已安全存好、校验通过后才调用；caps.delete=false 时不会调用 */
    suspend fun delete(link: DeviceLink, file: DeviceFile) {}
    /** 设备给的校验值；caps.checksum=false 时不会调用 */
    suspend fun checksum(link: DeviceLink, file: DeviceFile): ByteArray? = null
}

data class FileCaps(val resume: Boolean, val delete: Boolean, val checksum: Boolean)

data class DeviceFile(
    /** 插件内唯一，用于下载和删除 */
    val id: String,
    val name: String,
    val bytes: Long?,
    /** 开始时间（设备时钟，毫秒）；没有为 null，核心按文件结束时间对齐 */
    val startedAt: Long?,
    val durationMillis: Long?,
    val format: FileFormat,
)

enum class FileFormat { Wav, Opus, Ogg, Mp3, M4a, Aac, Pcm16 }

/** 核心交给插件的设备通道：只收发字节。通道编号用完整 128 位 UUID 字符串 */
interface DeviceLink {
    val mtu: Int
    suspend fun read(service: String, characteristic: String): ByteArray
    /** withResponse=true 等设备确认；整条命令一次写入，不由核心拆分（声云等设备要求） */
    suspend fun write(service: String, characteristic: String, bytes: ByteArray, withResponse: Boolean = true)
    fun notifications(service: String, characteristic: String): Flow<ByteArray>
}

// ============================== 给界面 ==============================

data class FoundDevice(val address: String, val name: String, val cardId: String, val trial: Boolean, val rssi: Int?)

data class DeviceState(
    val address: String,
    val name: String,
    val cardId: String,
    val status: DeviceStatus,
    /** 电量 0–100；读不到为 null */
    val battery: Int? = null,
    /** 正在补传时的进度 */
    val backfill: BackfillProgress? = null,
    /** 给人看的最近一次错误；没有为 null */
    val lastError: String? = null,
)

enum class DeviceStatus { Connecting, Live, Waiting, Backfilling, Disconnected }

data class BackfillProgress(val files: Int, val doneFiles: Int, val bytes: Long, val doneBytes: Long)

interface Devices {
    /** 已配对的设备 */
    val devices: StateFlow<List<DeviceState>>
    /** 正在搜索时找到的、有说明卡能认出的设备 */
    val found: StateFlow<List<FoundDevice>>
    fun startScan()
    fun stopScan()
    /** 配对并记住；之后在录音输入里可以选它 */
    fun pair(device: FoundDevice)
    fun forget(address: String)
}
