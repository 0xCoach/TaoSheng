package app.resound.devices.core

import app.resound.devices.*

/** fields 为无符号字段值。payload 是卡定义的整个载荷，不一定已排除命令头。 */
data class DecodedFrame(val fields: Map<String, Long>, val payload: ByteArray)

data class FrameDiagnostics(val received: Long, val lost: Long, val crcFailures: Long, val discarded: Long)

/**
 * 每个连接单独创建，串行使用；accept 接收完整协议包（不是任意 BLE 字节块）。
 * LengthFrom 严格表示 payload 长度。若 TYPE/CMD 位于 LEN 计数范围内，卡只声明外壳，
 * 将 TYPE/CMD 留在 payload 中；Framed 命令在没有这两个字段时自动前置这两个字节。
 */
class FrameCodec(private val spec: FrameSpec, private val maxPayloadBytes: Int = 1024 * 1024) {
    init { validateFrame(spec); require(maxPayloadBytes > 0) }
    var lockedEndian: Endian? = null
        private set
    private val needsProbe = spec.fields.any { it.endian == Endian.Probe }
    private val seqField = spec.fields.find { it.role == FieldRole.Seq }
    private var lastSeq: Long? = null
    private var sendSeq = 0L
    private var received = 0L
    private var lost = 0L
    private var crcFailures = 0L
    private var discarded = 0L
    private var fragments: ByteAccumulator? = null
    private var fragmentFields: Map<String, Long>? = null
    private var nextFragment = 0L
    val diagnostics get() = FrameDiagnostics(received, lost, crcFailures, discarded)

    /** 只有唯一一种字节序通过全部校验才锁定；歧义和坏包返回 null，不改变接收序号。 */
    fun probe(response: ByteArray): Endian? {
        if (!needsProbe) return null
        if (lockedEndian != null) return lockedEndian
        val candidates = listOf(Endian.Le, Endian.Be).filter { parse(response, it).frame != null }
        if (candidates.size == 1) lockedEndian = candidates.single()
        return lockedEndian
    }

    /** 未锁定前可用两种顺序生成探测命令；Raw 命令不受字节序影响。 */
    fun probeCommands(): List<ByteArray> {
        val command = requireNotNull(spec.probe) { "frame.probe：没有探测命令" }
        return if (command is CardCommand.Raw) listOf(command.bytes.copyOf())
        else listOf(pack(command, Endian.Le, sendSeq), pack(command, Endian.Be, sendSeq)).distinctBy { it.toList() }
    }

    fun accept(packet: ByteArray): DecodedFrame? {
        received++
        if (needsProbe && lockedEndian == null) { discarded++; return null }
        val parsed = parse(packet, lockedEndian)
        val frame = parsed.frame
        if (frame == null) {
            if (parsed.crcFailed) crcFailures++
            discardFragments(); discarded++; return null
        }
        var gap = false
        if (seqField != null) {
            val seq = frame.fields.getValue(seqField.name)
            val previous = lastSeq
            if (previous != null) {
                val modulus = fieldMax(seqField) + 1
                val delta = (seq - previous + modulus) % modulus
                // 序号采用串行数比较；重复/迟到不能当作绕回后的大量丢包。
                if (delta == 0L || delta > modulus / 2) { discardFragments(); discarded++; return null }
                if (delta > 1) { lost += delta - 1; gap = true }
            }
            lastSeq = seq
        }
        val fragment = spec.fragment ?: return frame
        val index = frame.fields.getValue(fragment.indexField)
        if (gap) discardFragments()
        if (index == fragment.restartAt.toLong()) {
            val ready = fragments?.let { DecodedFrame(fragmentFields!!, it.toByteArray()) }
            fragments = ByteAccumulator().apply { write(frame.payload) }
            fragmentFields = frame.fields
            nextFragment = index + 1
            return ready
        }
        val buffer = fragments
        if (buffer == null || index != nextFragment || buffer.size().toLong() + frame.payload.size > maxPayloadBytes) {
            discardFragments(); discarded++; return null
        }
        buffer.write(frame.payload); nextFragment++
        return null
    }

    /** 无末片标志，断开时不能证明末帧完整；只能丢弃，不能强行交付。 */
    fun discardFragments() { fragments = null; fragmentFields = null; nextFragment = 0 }

    /** Raw 原样返回；Framed 自动补常量、递增序号、长度与校验。 */
    fun encode(command: CardCommand): ByteArray {
        if (command is CardCommand.Raw) return command.bytes.copyOf()
        check(!needsProbe || lockedEndian != null) { "frame.endian：尚未锁定字节顺序" }
        val result = pack(command, lockedEndian, sendSeq)
        sendSeq = (sendSeq + 1) and (seqField?.let(::fieldMax) ?: 0xffffffffL)
        return result
    }

    private data class ParseResult(val frame: DecodedFrame? = null, val crcFailed: Boolean = false)
    private fun parse(packet: ByteArray, probeOrder: Endian?): ParseResult {
        val headerBytes = spec.fields.sumOf { it.bytes }
        if (packet.size < headerBytes || packet.size - headerBytes > maxPayloadBytes) return ParseResult()
        var offset = 0
        val raw = linkedMapOf<String, ByteArray>()
        val values = linkedMapOf<String, Long>()
        for (field in spec.fields) {
            val bytes = packet.copyOfRange(offset, offset + field.bytes); offset += field.bytes
            if (field.const != null && !bytes.contentEquals(field.const)) return ParseResult()
            raw[field.name] = bytes
            val endian = if (field.endian == Endian.Probe) probeOrder ?: return ParseResult() else field.endian
            values[field.name] = read(bytes, endian)
        }
        val available = packet.size - offset
        val length = when (val p = spec.payload) {
            PayloadSpec.Rest -> available.toLong()
            is PayloadSpec.LengthFrom -> values.getValue(p.field)
        }
        if (length != available.toLong()) return ParseResult()
        val payload = packet.copyOfRange(offset, packet.size)
        raw["payload"] = payload
        for (field in spec.fields) {
            val crc = field.crc ?: continue
            if (values.getValue(field.name) != checksum(crc, raw)) return ParseResult(crcFailed = true)
        }
        return ParseResult(DecodedFrame(values, payload))
    }

    private fun pack(command: CardCommand, probeOrder: Endian?, sequence: Long): ByteArray {
        if (command is CardCommand.Raw) return command.bytes.copyOf()
        validateCommand(command, spec)
        command as CardCommand.Framed
        val explicitType = spec.fields.any { it.role == FieldRole.Type }
        val payload = if (explicitType) command.payload else byteArrayOf(command.type.toByte(), command.cmd.toByte()) + command.payload
        require(payload.size <= maxPayloadBytes) { "命令.payload：载荷过大" }
        val raw = linkedMapOf("payload" to payload)
        for (field in spec.fields) {
            val value = when (field.role) {
                FieldRole.Seq -> sequence and fieldMax(field)
                FieldRole.Len -> payload.size.toLong()
                FieldRole.Type -> command.type.toLong()
                FieldRole.Cmd -> command.cmd.toLong()
                FieldRole.Frag -> spec.fragment?.restartAt?.toLong() ?: 0L
                else -> 0L
            }
            raw[field.name] = field.const?.copyOf() ?: write(value, field, probeOrder)
        }
        spec.fields.forEach { field -> field.crc?.let { raw[field.name] = write(checksum(it, raw), field, probeOrder) } }
        return ByteAccumulator().apply { spec.fields.forEach { write(raw.getValue(it.name)) }; write(payload) }.toByteArray()
    }

    private fun checksum(crc: CrcSpec, raw: Map<String, ByteArray>): Long = Crc.compute(crc.algo,
        ByteAccumulator().apply { crc.over.forEach { write(raw.getValue(it)) } }.toByteArray())

    private fun read(bytes: ByteArray, order: Endian): Long {
        var value = 0L
        val indices = if (order == Endian.Le) bytes.indices.reversed() else bytes.indices
        for (i in indices) value = (value shl 8) or (bytes[i].toLong() and 255)
        return value
    }
    private fun write(value: Long, field: FrameField, probeOrder: Endian?): ByteArray {
        require(value in 0..fieldMax(field)) { "frame.${field.name}：值超出 ${field.bytes} 字节范围" }
        val order = if (field.endian == Endian.Probe) requireNotNull(probeOrder) { "frame.endian：尚未锁定字节顺序" } else field.endian
        return ByteArray(field.bytes) { i -> (value ushr (8 * if (order == Endian.Le) i else field.bytes - 1 - i)).toByte() }
    }
}
