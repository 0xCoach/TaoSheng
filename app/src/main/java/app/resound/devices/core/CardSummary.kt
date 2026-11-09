package app.resound.devices.core

import app.resound.devices.*

/** 给审核者看的完整要点；不联网、不访问设备，也不执行命令。 */
fun describeCard(card: DeviceCard): String = buildString {
    appendLine("${card.name}（${card.id}），卡版本 ${card.version}，最低涛声 ${card.minApp}，${if (card.status == CardStatus.Trial) "试用" else "已验证"}，蓝牙。")
    appendLine("识别规则（满足任一条）：")
    if (card.match.namePrefix.isNotEmpty()) appendLine("  广播名前缀：${card.match.namePrefix.joinToString()}")
    if (card.match.serviceUuid.isNotEmpty()) appendLine("  广播服务：${card.match.serviceUuid.joinToString()}")
    card.match.manufacturerId?.let { appendLine("  厂商编号：0x${it.toString(16).padStart(4, '0')}") }
    appendLine("连接：MTU=${card.link.mtu ?: "系统默认"}，配对=${card.link.bond}，心跳=${card.link.keepAliveSec} 秒。")
    card.audio?.let { a ->
        appendLine("声音通道：服务 ${a.service}；通知 ${a.notify}；写入 ${a.write ?: "无"}。")
        appendLine("声音格式：${a.codec?.name ?: "读取 ${a.codecRead}，编号映射 ${a.codecMap}"}；${a.sampleRate} Hz，${a.channels} 声道。")
        appendLine("命令：开始=${commandText(a.start)}；停止=${commandText(a.stop)}；心跳=${commandText(card.link.keepAlive)}；探测=${commandText(a.frame.probe)}。")
        appendLine("包外壳（按接收顺序）：")
        a.frame.fields.forEach { f ->
            val endian = when (f.endian) { Endian.Le -> "小端"; Endian.Be -> "大端"; Endian.Probe -> "试探后锁定" }
            append("  ${f.name}：${f.bytes} 字节，$endian，用途 ${f.role}")
            f.const?.let { append("，固定 ${it.hexText()}") }
            f.crc?.let { append("，${it.algo} 校验依次覆盖 ${it.over.joinToString(" + ")}") }
            appendLine()
        }
        appendLine("载荷：${when (val p = a.frame.payload) { PayloadSpec.Rest -> "字段后所有剩余字节"; is PayloadSpec.LengthFrom -> "长度取自 ${p.field}（仅载荷长度）" }}。")
        if (a.frame.fields.none { it.role == FieldRole.Type || it.role == FieldRole.Cmd }) {
            appendLine("组装 Framed 命令时 TYPE/CMD 放在载荷前两字节；收包保留整个载荷，调用方按协议分流后再送解码器。")
        }
        a.frame.fragment?.let { appendLine("分片：${it.indexField} 从 ${it.restartAt} 开始，下一首片到来才交付上一帧；跳号丢整帧。") }
        appendLine("Opus 分帧：${if (a.frame.opus == OpusPacking.One) "一包一帧" else "每帧前 1 字节长度，一包可多帧"}。")
        listOf("开始" to a.start, "停止" to a.stop, "心跳" to card.link.keepAlive, "探测" to a.frame.probe).forEach { (label, command) ->
            if (command != null) {
                // 每条示例从 SEQ=0 起，不影响实际连接状态。
                val fixed = a.frame.copy(fields = a.frame.fields.map { if (it.endian == Endian.Probe) it.copy(endian = Endian.Le) else it })
                appendLine("  $label 命令字节示例（SEQ=0${if (a.frame.fields.any { it.endian == Endian.Probe }) "，试探字段暂按小端" else ""}）：${FrameCodec(fixed).encode(command).hexText()}")
            }
        }
    }
    card.battery?.let { appendLine("电量：${if (it.standard) "标准电池服务" else "服务 ${it.service}，读取 ${it.read}"}。") }
    card.clock?.let { appendLine("对时：服务 ${it.service}，写入 ${it.write}，格式 ${it.format}。") }
    appendLine("离线：${if (card.offline == OfflineMode.Files) "保存文件，文件插口 ${card.filePlugin}" else "不补传文件"}。")
}.trimEnd()

private fun commandText(command: CardCommand?): String = when (command) {
    null -> "无"
    is CardCommand.Raw -> command.bytes.hexText()
    is CardCommand.Framed -> "按外壳组装 TYPE=${command.type} CMD=${command.cmd} PAYLOAD=${command.payload.hexText()}"
}
private fun ByteArray.hexText(): String = "hex:" + joinToString("") { "%02X".format(it.toInt() and 255) }
