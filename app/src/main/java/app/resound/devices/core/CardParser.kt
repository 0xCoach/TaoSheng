package app.resound.devices.core

import app.resound.devices.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** 字段表的 JSON 入口；版本由宿主传入，避免核心依赖 BuildConfig 或写死版本。 */
class CardParser(currentAppVersion: String) {
    private val current = version(currentAppVersion.substringBefore('-'), "当前 App 版本")

    fun parse(json: String): Result<DeviceCard> = parse(json, checkAppVersion = true)

    // 参数表先完整校验，再由加载器按宿主版本筛选；不因一张未来版本卡丢弃整表。
    internal fun parseForCatalog(json: String): Result<DeviceCard> = parse(json, checkAppVersion = false)
    internal fun supports(minApp: String): Boolean = compareVersions(version(minApp, "minApp"), current) <= 0

    private fun parse(json: String, checkAppVersion: Boolean): Result<DeviceCard> = runCatching {
        require(json.length <= 256 * 1024) { "说明卡：超过 256 KiB" }
        validateCardJson(json)
        val tokener = JSONTokener(json)
        val root = tokener.nextValue() as? JSONObject ?: fail("说明卡", "必须为 JSON 对象")
        require(tokener.nextClean() == '\u0000') { "说明卡：JSON 后有多余内容" }
        val r = Obj(root, "说明卡")
        r.only("id", "name", "version", "minApp", "status", "transport", "match", "mtu", "bond",
            "keepAliveSec", "keepAlive", "audio", "frame", "battery", "clock", "offline", "filePlugin")
        val id = r.str("id")
        check(id.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")), "id", "只允许小写字母、数字和横线")
        val minApp = r.str("minApp")
        val minimum = version(minApp, "minApp")
        check(!checkAppVersion || compareVersions(minimum, current) <= 0, "minApp", "需要涛声 $minApp 以上")
        val match = r.obj("match").let { m ->
            m.only("namePrefix", "serviceUuid", "manufacturerId")
            val names = m.strings("namePrefix")
            val services = if (!m.has("serviceUuid")) emptyList() else m.stringOrStrings("serviceUuid").map {
                uuid(it, "match.serviceUuid")
            }
            val manufacturer = m.optional("manufacturerId") {
                val v = m.value("manufacturerId")
                if (v is String && v.matches(Regex("0x[0-9a-fA-F]{1,4}"))) v.substring(2).toInt(16)
                else m.int("manufacturerId", range = 0..65535)
            }
            check(names.isNotEmpty() || services.isNotEmpty() || manufacturer != null, "match", "至少填写一种识别规则")
            CardMatch(names, services, manufacturer)
        }
        val link = LinkParams(r.optional("mtu") { r.int("mtu", range = 23..517) }, r.bool("bond", false),
            r.int("keepAliveSec", 0, 0..86400), r.optional("keepAlive") { command(r.value("keepAlive"), "keepAlive") })
        check(link.keepAliveSec == 0 || link.keepAlive != null, "keepAliveSec", "大于 0 时必须填写 keepAlive 命令")
        val audio = r.optional("audio") {
            val a = r.obj("audio")
            a.only("service", "notify", "write", "start", "stop", "codecRead", "codecMap", "codec", "sampleRate", "channels")
            val codec = a.optional("codec") { codec(a.str("codec"), "audio.codec") }
            val read = a.optional("codecRead") { uuid(a.str("codecRead"), "audio.codecRead") }
            val map = a.optional("codecMap") {
                val m = a.obj("codecMap")
                m.keys().associate { key ->
                    check(key.matches(Regex("0|[1-9][0-9]*")) && key.toIntOrNull() in 0..255, "audio.codecMap.$key", "编码号必须为 0–255 的整数")
                    key.toInt() to codec(m.str(key), "audio.codecMap.$key")
                }
            }.orEmpty()
            check((codec != null) != (read != null), "audio.codec", "codec 与 codecRead 必须二选一")
            check(if (read != null) map.isNotEmpty() else map.isEmpty(), "audio.codecMap", "必须与 codecRead 同时填写")
            CardAudio(uuid(a.str("service"), "audio.service"), uuid(a.str("notify"), "audio.notify"),
                a.optional("write") { uuid(a.str("write"), "audio.write") },
                a.optional("start") { command(a.value("start"), "audio.start") },
                a.optional("stop") { command(a.value("stop"), "audio.stop") }, read, map, codec,
                a.int("sampleRate", 16000, 1000..192000), a.int("channels", 1, 1..2),
                if (r.has("frame")) frame(r.obj("frame")) else FrameSpec())
        }
        check(audio != null || !r.has("frame"), "frame", "填写包外壳时必须有 audio")
        val commands = listOfNotNull(link.keepAlive, audio?.start, audio?.stop, audio?.frame?.probe)
        check(commands.isEmpty() || audio?.write != null, "audio.write", "有发送命令时必须填写写通道")
        audio?.frame?.let { spec -> commands.forEach { validateCommand(it, spec) } }
        val battery = r.optional("battery") {
            val b = r.value("battery")
            if (b is String) {
                check(b == "standard", "battery", "未知取值 $b")
                CardBattery()
            } else {
                val o = Obj(b as? JSONObject ?: fail("battery", "必须为 standard 或对象"), "battery")
                o.only("service", "read")
                CardBattery(false, uuid(o.str("service"), "battery.service"), uuid(o.str("read"), "battery.read"))
            }
        }
        val clock = r.optional("clock") {
            val c = r.obj("clock"); c.only("service", "write", "format")
            CardClock(uuid(c.str("service"), "clock.service"), uuid(c.str("write"), "clock.write"),
                choice(c.str("format"), "clock.format", mapOf("unix32le" to ClockFormat.Unix32Le, "unix32be" to ClockFormat.Unix32Be, "bcd" to ClockFormat.Bcd)))
        }
        val offline = choice(r.str("offline", "none"), "offline", mapOf("none" to OfflineMode.None, "files" to OfflineMode.Files))
        val plugin = r.optional("filePlugin") { r.str("filePlugin") }
        check(offline != OfflineMode.Files || plugin != null, "filePlugin", "offline=files 时必须填写文件插口")
        DeviceCard(id, r.str("name"), r.int("version", range = 1..Int.MAX_VALUE), minApp,
            choice(r.str("status"), "status", mapOf("trial" to CardStatus.Trial, "verified" to CardStatus.Verified)),
            choice(r.str("transport"), "transport", mapOf("ble" to Transport.Ble)), match, link, audio, battery, clock, offline, plugin)
    }.recoverCatching { error ->
        if (error is IllegalArgumentException) throw error
        throw IllegalArgumentException("说明卡：JSON 格式错误（${error.message}）", error)
    }

    private fun frame(f: Obj): FrameSpec {
        f.only("fields", "payload", "fragment", "opusPerPacket", "probe")
        val fields = f.objects("fields").map { o ->
            o.only("name", "bytes", "endian", "const", "role", "crc")
            val role = choice(o.str("role", "other"), "${o.path}.role", FieldRole.entries.associateBy { it.name.lowercase() })
            val crc = o.optional("crc") {
                val c = o.obj("crc"); c.only("algo", "over")
                CrcSpec(choice(c.str("algo"), "${c.path}.algo", mapOf("crc16-xmodem" to CrcAlgo.Crc16Xmodem,
                    "crc16-modbus" to CrcAlgo.Crc16Modbus, "crc32" to CrcAlgo.Crc32)), c.strings("over", required = true))
            }
            check((role == FieldRole.Crc) == (crc != null), "${o.path}.crc", "role=crc 时必须填写 crc，其他用途不能填写")
            FrameField(o.str("name"), o.int("bytes", range = 1..4),
                choice(o.str("endian", "le"), "${o.path}.endian", mapOf("le" to Endian.Le, "be" to Endian.Be, "probe" to Endian.Probe)),
                o.optional("const") { hex(o.str("const"), "${o.path}.const") }, role, crc)
        }
        val payload = f.optional("payload") {
            when (val p = f.value("payload")) {
                "rest" -> PayloadSpec.Rest
                is JSONObject -> Obj(p, "frame.payload").let { o -> o.only("lengthFrom"); PayloadSpec.LengthFrom(o.str("lengthFrom")) }
                else -> fail("frame.payload", "必须为 rest 或 lengthFrom 对象")
            }
        } ?: PayloadSpec.Rest
        val fragment = f.optional("fragment") {
            val o = f.obj("fragment"); o.only("indexField", "restartAt")
            FragmentSpec(o.str("indexField"), o.int("restartAt", 0, 0..Int.MAX_VALUE))
        }
        return FrameSpec(fields, payload, fragment,
            choice(f.str("opusPerPacket", "one"), "frame.opusPerPacket", mapOf("one" to OpusPacking.One, "lengthPrefixed" to OpusPacking.LengthPrefixed)),
            f.optional("probe") { command(f.value("probe"), "frame.probe") }).also { validateFrame(it) }
    }

    private fun command(value: Any, path: String): CardCommand = when (value) {
        is String -> CardCommand.Raw(hex(value, path).also { check(it.isNotEmpty(), path, "命令不能为空") })
        is JSONObject -> Obj(value, path).let { o ->
            o.only("type", "cmd", "payload")
            CardCommand.Framed(o.int("type", range = 0..255), o.int("cmd", range = 0..255),
                o.optional("payload") { hex(o.str("payload"), "$path.payload") } ?: ByteArray(0))
        }
        else -> fail(path, "必须为 hex: 字节串或命令对象")
    }

    private class Obj(private val json: JSONObject, val path: String) {
        fun has(key: String) = json.has(key)
        fun keys(): List<String> = json.keys().asSequence().toList()
        fun only(vararg allowed: String) { keys().firstOrNull { it !in allowed }?.let { fail("$path.$it", "未知字段") } }
        fun value(key: String): Any = if (!has(key) || json.isNull(key)) fail("$path.$key", "缺少值，不能为 null") else json.get(key)
        fun str(key: String, default: String? = null): String {
            if (!has(key) && default != null) return default
            return (value(key) as? String)?.takeIf { it.isNotBlank() } ?: fail("$path.$key", "必须为非空字符串")
        }
        fun int(key: String, default: Int? = null, range: IntRange): Int {
            if (!has(key) && default != null) return default
            val v = value(key)
            val number = if (v is Int || v is Long) (v as Number).toLong() else fail("$path.$key", "必须为整数")
            check(number in range.first.toLong()..range.last.toLong(), "$path.$key", "必须在 ${range.first}–${range.last} 之间")
            return number.toInt()
        }
        fun bool(key: String, default: Boolean): Boolean = if (!has(key)) default else value(key) as? Boolean ?: fail("$path.$key", "必须为布尔值")
        fun obj(key: String) = Obj(value(key) as? JSONObject ?: fail("$path.$key", "必须为对象"), "$path.$key")
        fun strings(key: String, required: Boolean = false): List<String> {
            if (!has(key) && !required) return emptyList()
            val a = value(key) as? JSONArray ?: fail("$path.$key", "必须为数组")
            return (0 until a.length()).map { i -> (a.get(i) as? String)?.takeIf { it.isNotBlank() } ?: fail("$path.$key[$i]", "必须为非空字符串") }
        }
        fun stringOrStrings(key: String) = if (value(key) is String) listOf(str(key)) else strings(key)
        fun objects(key: String): List<Obj> {
            if (!has(key)) return emptyList()
            val a = value(key) as? JSONArray ?: fail("$path.$key", "必须为数组")
            check(a.length() <= 64, "$path.$key", "字段不能超过 64 项")
            return (0 until a.length()).map { i -> Obj(a.get(i) as? JSONObject ?: fail("$path.$key[$i]", "必须为对象"), "$path.$key[$i]") }
        }
        fun <T> optional(key: String, block: () -> T): T? = if (has(key)) block() else null
    }

    companion object {
        private fun codec(value: String, path: String) = choice(value, path, Codec.entries.associateBy { it.name.lowercase() })
        private fun uuid(value: String, path: String): String {
            check(value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")), path, "UUID 必须为完整 128 位格式")
            return value.lowercase()
        }
        private fun version(value: String, path: String): List<Int> {
            check(value.matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")), path, "版本格式必须为 主版本.次版本.修订号，如 0.2.21")
            return value.split('.').map { it.toIntOrNull() ?: fail(path, "版本数字过大") }
        }
        private fun compareVersions(a: List<Int>, b: List<Int>): Int = a.indices.firstOrNull { a[it] != b[it] }?.let { a[it].compareTo(b[it]) } ?: 0
        private fun <T> choice(value: String, path: String, options: Map<String, T>): T = options[value] ?: fail(path, "未知取值 $value")
        private fun check(ok: Boolean, path: String, reason: String) { if (!ok) fail(path, reason) }
        private fun fail(path: String, reason: String): Nothing = throw IllegalArgumentException("$path：$reason")
        private fun hex(value: String, path: String): ByteArray {
            check(value.matches(Regex("hex:(?:[0-9a-fA-F]{2})*")), path, "字节必须为 hex: 后跟偶数个十六进制字符")
            return value.substring(4).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
    }
}
