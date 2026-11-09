package app.resound.devices

import org.json.JSONObject
import java.util.UUID

/** 卡 118 未合入时的保守解析接点。未知字段拒绝；合并后由 CardParser 收拢。 */
internal object BootstrapCardParser {
    fun parse(text: String): Result<DeviceCard> = runCatching {
        val j = JSONObject(text).only("id name version minApp status transport match mtu bond keepAliveSec keepAlive audio frame battery clock offline filePlugin")
        val id = j.s("id").also { require(it.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) }
        val match = j.getJSONObject("match").only("namePrefix serviceUuid manufacturerId")
        val prefixes = match.strings("namePrefix").also { require(it.all(String::isNotBlank)) }
        val services = if (match.opt("serviceUuid") is String) listOf(match.uuid("serviceUuid")) else match.strings("serviceUuid").onEach(::validateUuid)
        val manufacturer = if (match.has("manufacturerId")) match.i("manufacturerId").also { require(it in 0..65535) } else null
        val link = LinkParams(if (j.has("mtu")) j.i("mtu").also { require(it in 23..517) } else null,
            j.bool("bond", false), j.i("keepAliveSec", 0).also { require(it >= 0) }, j.command("keepAlive"))
        require(link.keepAliveSec == 0 || link.keepAlive != null)
        val audio = j.optJSONObject("audio")?.let { a ->
            a.only("service notify write start stop codecRead codecMap codec sampleRate channels")
            val codec = a.optStringValue("codec")?.let(::codec)
            val codecRead = a.optionalUuid("codecRead")
            require((codec != null) xor (codecRead != null))
            require(!a.has("codecMap") || a.opt("codecMap") is JSONObject)
            val map = a.optJSONObject("codecMap")?.let { m -> m.keys().asSequence().associate { it.toInt().also { n -> require(n >= 0) } to codec(m.s(it)) } }.orEmpty()
            require(codecRead == null || map.isNotEmpty())
            CardAudio(a.uuid("service"), a.uuid("notify"), a.optionalUuid("write"), a.command("start"), a.command("stop"),
                codecRead, map, codec, a.i("sampleRate", 16000).also { require(it in 8000..48000) },
                a.i("channels", 1).also { require(it in 1..2) }, frame(j.getJSONObject("frame")))
                .also { require((it.start == null && it.stop == null) || it.write != null) }
        }
        require(!j.has("audio") || audio != null)
        require(!j.has("frame") || audio != null)
        val battery = if (!j.has("battery")) null else if (j.opt("battery") == "standard") CardBattery() else {
            val b = j.getJSONObject("battery").only("service read")
            CardBattery(false, b.uuid("service"), b.uuid("read"))
        }
        val clock = j.optJSONObject("clock")?.let { c ->
            c.only("service write format")
            CardClock(c.uuid("service"), c.uuid("write"), when(c.s("format")) {
                "unix32le" -> ClockFormat.Unix32Le; "unix32be" -> ClockFormat.Unix32Be; "bcd" -> ClockFormat.Bcd; else -> error("clock_format")
            })
        }
        require(!j.has("clock") || clock != null)
        val offline = when(j.optStringValue("offline") ?: "none") { "none" -> OfflineMode.None; "files" -> OfflineMode.Files; else -> error("offline") }
        val plugin = j.optStringValue("filePlugin")
        require(offline != OfflineMode.Files || !plugin.isNullOrBlank())
        DeviceCard(id, j.s("name").also { require(it.isNotBlank()) }, j.i("version").also { require(it > 0) },
            j.s("minApp").also { versionParts(it) }, when(j.s("status")) { "verified" -> CardStatus.Verified; "trial" -> CardStatus.Trial; else -> error("status") },
            Transport.Ble.also { require(j.s("transport") == "ble") }, CardMatch(prefixes, services, manufacturer), link, audio, battery, clock, offline, plugin)
    }
    private fun frame(j: JSONObject): FrameSpec {
        j.only("fields payload fragment opusPerPacket probe")
        val fields = j.optJSONArray("fields")?.let { items -> (0 until items.length()).map { index ->
            val f = items.getJSONObject(index).only("name bytes endian const role crc")
            val crc = f.optJSONObject("crc")?.let { c ->
                c.only("algo over"); CrcSpec(when(c.s("algo")) { "crc16-xmodem" -> CrcAlgo.Crc16Xmodem; "crc16-modbus" -> CrcAlgo.Crc16Modbus; "crc32" -> CrcAlgo.Crc32; else -> error("crc") }, c.strings("over"))
            }
            require(!f.has("crc") || crc != null)
            val role = FieldRole.entries.firstOrNull { it.name.lowercase() == (f.optStringValue("role") ?: "other") } ?: error("role")
            require(role != FieldRole.Crc || crc != null)
            FrameField(f.s("name"), f.i("bytes").also { require(it in 1..8) },
                when(f.optStringValue("endian") ?: "le") { "le" -> Endian.Le; "be" -> Endian.Be; "probe" -> Endian.Probe; else -> error("endian") },
                f.optStringValue("const")?.let(::hex), role, crc).also { require(it.const == null || it.const.size == it.bytes) }
        } }.orEmpty()
        require(!j.has("fields") || j.opt("fields") is org.json.JSONArray)
        require(fields.map { it.name }.distinct().size == fields.size)
        val names = fields.map { it.name }.toSet()
        val payload = if (!j.has("payload") || j.opt("payload") == "rest") PayloadSpec.Rest else {
            val p = j.getJSONObject("payload").only("lengthFrom"); PayloadSpec.LengthFrom(p.s("lengthFrom").also { require(it in names) })
        }
        val fragment = j.optJSONObject("fragment")?.let { f -> f.only("indexField restartAt"); FragmentSpec(f.s("indexField").also { require(it in names) }, f.i("restartAt", 0)) }
        require(!j.has("fragment") || fragment != null)
        fields.forEach { f -> f.crc?.let { require(it.over.isNotEmpty() && it.over.all { n -> n in names || n == "payload" }) } }
        val probe = j.command("probe")
        require(fields.none { it.endian == Endian.Probe } || probe != null)
        return FrameSpec(fields, payload, fragment, when(j.optStringValue("opusPerPacket") ?: "one") { "one" -> OpusPacking.One; "lengthPrefixed" -> OpusPacking.LengthPrefixed; else -> error("opus") }, probe)
    }
    private fun codec(s: String) = when(s) { "pcm16" -> Codec.Pcm16; "pcm8" -> Codec.Pcm8; "mulaw" -> Codec.Mulaw; "opus10" -> Codec.Opus10; "opus20" -> Codec.Opus20; else -> error("codec") }
    private fun hex(s: String): ByteArray {
        require(s.startsWith("hex:")); val h = s.removePrefix("hex:")
        require(h.length % 2 == 0 && h.length <= 1024 && h.all { it in "0123456789abcdefABCDEF" })
        return h.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    private fun JSONObject.command(k: String): CardCommand? = if (!has(k)) null else when(val v = get(k)) {
        is String -> CardCommand.Raw(hex(v))
        is JSONObject -> { v.only("type cmd payload"); CardCommand.Framed(v.i("type"), v.i("cmd"), v.optStringValue("payload")?.let(::hex) ?: byteArrayOf()) }
        else -> error("command")
    }
    private fun validateUuid(s: String): String = s.also { require(UUID.fromString(it).toString() == it) }
    private fun JSONObject.uuid(k: String): String = validateUuid(s(k))
    private fun JSONObject.optionalUuid(k: String): String? = optStringValue(k)?.let(::validateUuid)
    private fun JSONObject.bool(k: String, default: Boolean) = if (has(k)) get(k) as? Boolean ?: error("boolean") else default
    private fun JSONObject.strings(k: String): List<String> = if (!has(k)) emptyList() else getJSONArray(k).let { a -> (0 until a.length()).map { a.get(it) as? String ?: error("string") } }
}
internal fun JSONObject.only(keys: String): JSONObject = apply { require(keys().asSequence().all { it in keys.split(' ') }) { "unknown_field" } }
internal fun JSONObject.s(k: String) = get(k) as? String ?: error("string")
internal fun JSONObject.optStringValue(k: String) = if (has(k)) s(k) else null
internal fun JSONObject.i(k: String, default: Int? = null): Int = if (!has(k) && default != null) default else (get(k) as? Int ?: error("integer"))
internal fun versionParts(v: String): List<Int> { require(v.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))); return v.split('.').map(String::toInt) }
internal fun compatible(required: String, current: String): Boolean {
    val a = versionParts(required); val b = versionParts(current.substringBefore('-'))
    return a.zip(b).firstOrNull { it.first != it.second }?.let { it.first < it.second } ?: true
}
