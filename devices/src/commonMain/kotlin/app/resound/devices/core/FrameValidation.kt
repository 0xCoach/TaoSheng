package app.resound.devices.core

import app.resound.devices.*

/** 解析 JSON 和直接构造 FrameSpec 共用同一组约束。 */
internal fun validateFrame(spec: FrameSpec) {
    fun valid(ok: Boolean, item: String, reason: String) { require(ok) { "frame.$item：$reason" } }
    val fields = spec.fields
    valid(fields.size <= 64, "fields", "字段不能超过 64 项")
    valid(fields.map { it.name }.distinct().size == fields.size, "fields", "字段名不能重复")
    fields.forEach { f ->
        valid(f.name.isNotBlank() && f.name != "payload", "fields", "字段名不能为空或使用保留名 payload")
        valid(f.bytes in 1..4, f.name, "字段宽度必须为 1–4 字节")
        valid(f.const == null || f.const.size == f.bytes, "${f.name}.const", "字节数必须与 bytes 相同")
        valid(f.const == null || f.role in listOf(FieldRole.Magic, FieldRole.Other), f.name, "动态用途不能同时指定 const")
        valid((f.role == FieldRole.Crc) == (f.crc != null), "${f.name}.crc", "role=crc 时必须填写 crc，其他用途不能填写")
        f.crc?.let { c ->
            valid(f.bytes == if (c.algo == CrcAlgo.Crc32) 4 else 2, "${f.name}.bytes", "与 CRC 算法宽度不符")
            valid(c.over.isNotEmpty() && c.over.distinct().size == c.over.size, "${f.name}.crc.over", "覆盖范围不能为空或重复")
            c.over.forEach { name ->
                valid(name == "payload" || fields.any { it.name == name && it.role != FieldRole.Crc }, "${f.name}.crc.over", "引用不存在的字段或 CRC 字段：$name")
            }
        }
    }
    listOf(FieldRole.Seq, FieldRole.Len, FieldRole.Type, FieldRole.Cmd, FieldRole.Frag).forEach { role ->
        valid(fields.count { it.role == role } <= 1, "fields", "$role 用途不能重复")
    }
    (spec.payload as? PayloadSpec.LengthFrom)?.let { p ->
        valid(fields.any { it.name == p.field && it.role == FieldRole.Len }, "payload.lengthFrom", "必须引用存在且 role=len 的字段：${p.field}")
    }
    spec.fragment?.let { fragment ->
        val index = fields.find { it.name == fragment.indexField }
        valid(index?.role == FieldRole.Frag, "fragment.indexField", "必须引用存在且 role=frag 的字段")
        valid(fragment.restartAt >= 0 && fragment.restartAt.toLong() <= fieldMax(index!!), "fragment.restartAt", "超出分片字段范围")
        valid(fields.any { it.role == FieldRole.Seq }, "fragment", "分片必须有 seq 字段以检测丢包")
    }
    if (fields.any { it.endian == Endian.Probe }) {
        valid(spec.probe != null, "probe", "endian=probe 时必须有探测命令")
        valid(fields.any { it.crc != null }, "probe", "字节顺序试探必须有 CRC 校验")
    }
}

internal fun validateCommand(command: CardCommand, spec: FrameSpec) {
    if (command is CardCommand.Raw) return
    command as CardCommand.Framed
    require(command.type in 0..255 && command.cmd in 0..255) { "命令：type 和 cmd 必须在 0–255 之间" }
    val type = spec.fields.any { it.role == FieldRole.Type }
    val cmd = spec.fields.any { it.role == FieldRole.Cmd }
    require(type == cmd) { "命令：type、cmd 字段必须同时声明或同时放在 payload 开头" }
    require(spec.fields.none { it.role == FieldRole.Other && it.const == null || it.role == FieldRole.Magic && it.const == null }) {
        "命令：未知用途字段必须指定 const，无法自动填充"
    }
}

internal fun fieldMax(field: FrameField): Long = (1L shl (field.bytes * 8)) - 1
