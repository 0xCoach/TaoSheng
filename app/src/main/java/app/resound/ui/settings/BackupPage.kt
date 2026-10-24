package app.resound.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.resound.backup.BackupRepository
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.Dot
import app.resound.ui.verify.Pill
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.formatBytes
import app.resound.ui.verify.formatClock
import app.resound.ui.verify.springClick
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 数据备份：本机（存到手机文件夹、导出、从文件恢复）和坚果云两种，加密口令共用 */
@Composable
fun BackupPage(repo: BackupRepository, bottomPadding: Dp) {
    val c = Rs.colors
    val s by repo.state.collectAsState()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<String>?>(null) }
    val configured = s.account != null && s.hasPassword
    var settingPass by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf<app.resound.backup.RemoteBackup?>(null) }
    var remote by remember { mutableStateOf<Result<List<app.resound.backup.RemoteBackup>>?>(null) }
    androidx.compose.runtime.LaunchedEffect(configured, s.lastBackupAt) { if (configured) remote = repo.remoteBackups() }
    val local by repo.local.collectAsState()
    // 默认打开正在用的那一边；都没用时先看本机（不用注册账号就能用）
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(if (configured && local.folder == null) 1 else 0) }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding + 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        app.resound.ui.skills.Segments(listOf("本机", "坚果云"), tab) { tab = it }
        if (tab == 0) LocalBackupSection(repo, local) { restoring = it }
        else {
        // 状态
        RsCard(padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(when { s.lastError != null -> c.critical; s.running -> c.accent; s.lastBackupAt != null -> c.good; else -> c.textTertiary }, 9.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        s.running -> "正在备份…"
                        s.lastError != null -> "上次备份没成功"
                        s.lastBackupAt != null -> "已备份"
                        configured -> "还没备份过"
                        else -> "还没连接坚果云"
                    },
                    style = RsType.headline, color = c.text,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                s.lastError ?: listOfNotNull(
                    s.lastBackupAt?.let { "上次 ${dateTime(it)}" },
                    s.lastBackupBytes?.let { formatBytes(it) },
                    s.remoteCount?.let { "云端 $it 份" },
                ).joinToString(" · ").ifEmpty { "每晚充电且连着 WiFi 时自动备份，云端保留最近 ${s.keep} 天的恢复点。录音音频不备份。" },
                style = RsType.subhead,
                color = if (s.lastError != null) c.critical else c.textSecondary,
            )
            if (configured) {
                Spacer(Modifier.height(14.dp))
                Pill(if (s.running) "备份中…" else "立即备份", filled = !s.running) { if (!s.running) repo.backupNow() }
            }
        }

        // 账号
        RsCard(Modifier.animateContentSize(), padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
            Text("坚果云账号", style = RsType.body, color = c.text)
            Spacer(Modifier.height(10.dp))
            if (configured && !editing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.account.orEmpty(), style = RsType.subhead, color = c.textSecondary, modifier = Modifier.weight(1f))
                    Pill(if (testing) "测试中" else "测试", filled = false) {
                        if (testing) return@Pill
                        testing = true
                        scope.launch { result = repo.test(); testing = false }
                    }
                    Spacer(Modifier.width(8.dp))
                    Pill("更换", filled = false) { editing = true; account = s.account.orEmpty() }
                }
                result?.let { r ->
                    Spacer(Modifier.height(8.dp))
                    Text(r.getOrNull() ?: r.exceptionOrNull()?.message ?: "测试没通过", style = RsType.caption, color = if (r.isSuccess) c.good else c.critical)
                }
            } else {
                Field(account, "邮箱", KeyboardType.Email, secret = false) { account = it.trim() }
                Spacer(Modifier.height(10.dp))
                Field(password, "第三方应用密码（不是登录密码）", KeyboardType.Password, secret = true) { password = it.trim() }
                Spacer(Modifier.height(12.dp))
                Row {
                    Pill("保存", filled = account.isNotEmpty() && password.isNotEmpty()) {
                        if (account.isEmpty() || password.isEmpty()) return@Pill
                        repo.saveCredentials(account, password)
                        password = ""; editing = false; result = null
                    }
                    if (editing) {
                        Spacer(Modifier.width(8.dp))
                        Pill("取消", filled = false) { editing = false; password = "" }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("应用密码在坚果云网页版“账户信息 → 安全选项 → 第三方应用管理”里生成。密码只存在手机安全区，不会显示。",
                    style = RsType.caption, color = c.textTertiary)
            }
        }

        // 自动备份开关
        RsCard(padding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("每晚自动备份", style = RsType.body, color = c.text)
                    Text("充电、连着 WiFi、手机空闲时进行", style = RsType.caption, color = c.textSecondary)
                }
                Toggle(s.enabled) { repo.setEnabled(it) }
            }
            if (configured) {
                Spacer(Modifier.height(14.dp))
                Text("保留最近", style = RsType.body, color = c.text)
                Text("每晚一个恢复点。每周传一份完整的，其余几天只传变化的部分，省流量", style = RsType.caption, color = c.textSecondary)
                Spacer(Modifier.height(10.dp))
                val options = listOf(3, 7, 30)
                app.resound.ui.skills.Segments(options.map { "$it 天" }, options.indexOf(s.keep).coerceAtLeast(1)) { repo.setKeep(options[it]) }
            }
        }

        // 云端备份与恢复
        if (configured) {
            Text("云端备份", style = RsType.section, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            RsCard(padding = PaddingValues(vertical = 4.dp)) {
                val list = remote
                when {
                    list == null -> Text("正在读取…", style = RsType.subhead, color = c.textTertiary, modifier = Modifier.padding(16.dp))
                    list.isFailure -> Text(list.exceptionOrNull()?.message ?: "读取失败", style = RsType.subhead, color = c.critical, modifier = Modifier.padding(16.dp))
                    list.getOrThrow().isEmpty() -> Text("云端还没有备份", style = RsType.subhead, color = c.textTertiary, modifier = Modifier.padding(16.dp))
                    else -> list.getOrThrow().forEachIndexed { i, b ->
                        Row(Modifier.fillMaxWidth().springClick { restoring = b }.padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(dateTime(b.at), style = RsType.body, color = c.text)
                                Text(backupLine(b), style = RsType.caption, color = c.textTertiary)
                            }
                            Text("恢复 ›", style = RsType.subhead, color = c.accent)
                        }
                        if (i != list.getOrThrow().lastIndex) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
                    }
                }
            }
            Text("恢复“增量”时会自动连同它那份完整备份一起取回。旧的“回响备份”文件夹里的不会自动删。恢复会替换手机上的全部记录，替换前会先在本机留一份快照。", style = RsType.caption, color = c.textTertiary,
                modifier = Modifier.padding(horizontal = 4.dp))
        }
        }

        // 备份加密：本机和坚果云共用一个口令
        RsCard(padding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("备份加密", style = RsType.body, color = c.text)
                    Text(if (s.encrypted) "已开启：之后的备份（本机和坚果云）都用你的口令加密" else "默认不加密。开启后口令只存在手机里，忘了就无法恢复",
                        style = RsType.caption, color = c.textSecondary)
                }
                Toggle(s.encrypted) { on -> if (on) settingPass = true else repo.setEncryption(null) }
            }
        }
    }
    if (settingPass) PassphraseSheet { pass -> if (pass != null) repo.setEncryption(pass); settingPass = false }
    restoring?.let { b -> RestoreSheet(b, { pass -> if (b.name.startsWith(LOCAL_TAG)) repo.restoreLocal(b.name.removePrefix(LOCAL_TAG), pass) else repo.restore(b.name, pass) }) { restoring = null } }
}

@Composable
private fun Field(value: String, hint: String, type: KeyboardType, secret: Boolean, onChange: (String) -> Unit) {
    val c = Rs.colors
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = RsType.body.copy(color = c.text),
        cursorBrush = SolidColor(c.accent),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(Modifier.clip(RoundedCornerShape(14.dp)).background(c.background).padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (value.isEmpty()) Text(hint, style = RsType.body, color = c.textTertiary)
                inner()
            }
        },
    )
}

/** 苹果风格开关 */
@Composable
private fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Rs.colors
    val track by animateColorAsState(if (on) c.accent else c.separator, label = "track")
    val x by animateDpAsState(if (on) 20.dp else 0.dp, spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium), label = "knob")
    Box(Modifier.size(width = 50.dp, height = 30.dp).clip(CircleShape).background(track).springClick { onChange(!on) }.padding(2.dp)) {
        Box(Modifier.offset(x = x).size(26.dp).clip(CircleShape).background(Color.White))
    }
}

private fun dateTime(at: Long): String =
    Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日")) + " " + formatClock(at)

/** 设加密口令：输两遍一致才保存 */
@Composable
private fun PassphraseSheet(onDone: (String?) -> Unit) {
    val c = Rs.colors
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    app.resound.ui.verify.RsSheet("设置备份口令", "之后的备份用它加密。口令只存在这台手机里，不上传；忘了口令，加密的备份就无法恢复。", onDismiss = { onDone(null) }) {
        Field(a, "口令（至少 8 位）", KeyboardType.Password, secret = true) { a = it }
        Field(b, "再输一遍", KeyboardType.Password, secret = true) { b = it }
        if (b.isNotEmpty() && a != b) Text("两次不一样", style = RsType.caption, color = c.critical)
        app.resound.ui.verify.ActionButton("开启加密", app.resound.ui.verify.ActionKind.Primary, enabled = a.length >= 8 && a == b) { onDone(a) }
    }
}

/** 用某一份云端备份恢复：危险操作，点两次；加密的且本机解不开时要输口令 */
@Composable
private fun RestoreSheet(b: app.resound.backup.RemoteBackup, restore: suspend (String?) -> Result<Unit>, title: String? = null, onDismiss: () -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    app.resound.ui.verify.RsSheet(title ?: "恢复到 ${dateTime(b.at)}", "用这份备份替换手机上的全部记录。替换前会先在本机留一份快照。恢复后 App 会自动重启，转写、整理和推送的密钥需要重新填写。",
        onDismiss = { if (!busy) onDismiss() }) {
        if (b.encrypted) Field(pass, "备份口令（本机记得的话可以不填）", KeyboardType.Password, secret = true) { pass = it }
        error?.let { Text(it, style = RsType.subhead, color = c.critical) }
        app.resound.ui.verify.ActionButton(
            when { busy -> "正在恢复…"; confirm -> "确定恢复？再点一次"; else -> "用这份恢复" },
            app.resound.ui.verify.ActionKind.Danger, enabled = !busy,
        ) {
            if (!confirm) { confirm = true; return@ActionButton }
            busy = true; error = null
            scope.launch {
                restore(pass.ifBlank { null }).onFailure { error = it.message ?: "没恢复成功"; busy = false; confirm = false }
            }
        }
        app.resound.ui.verify.ActionButton("取消", app.resound.ui.verify.ActionKind.Secondary, enabled = !busy) { onDismiss() }
    }
}


/** 列表里每份备份的第二行：完整 / 增量 · 大小 · 是否加密 */
private fun backupLine(b: app.resound.backup.RemoteBackup) =
    listOfNotNull(if (b.full) "完整" else "增量", formatBytes(b.bytes), "已加密".takeIf { b.encrypted }).joinToString(" · ")

/** 一行：标题 + 一句短说明，右边一个胶囊按钮（上下居中）；整行都能点 */
@Composable
private fun ActionRow(title: String, subtitle: String, action: String, onClick: () -> Unit) {
    val c = Rs.colors
    RsCard(Modifier.springClick(onClick), padding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = RsType.body, color = c.text)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = RsType.caption, color = c.textSecondary, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            Text(action, style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.accent,
                modifier = Modifier.clip(CircleShape).background(c.accentSoft).padding(horizontal = 14.dp, vertical = 6.dp))
        }
    }
}

/** 本机列表里的条目在名字前加这个标记，恢复时据此走本机恢复 */
private const val LOCAL_TAG = "local:"

/** 本机备份：选文件夹、每晚自动、只留最新几份、立即备份、导出一份、从文件恢复 */
@Composable
private fun LocalBackupSection(repo: BackupRepository, l: app.resound.backup.LocalBackupState, onRestore: (app.resound.backup.RemoteBackup) -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<Result<List<app.resound.backup.RemoteBackup>>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var fileRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    androidx.compose.runtime.LaunchedEffect(l.folder, l.lastAt, l.count) { if (l.folder != null) list = repo.localBackups() }
    val pickFolder = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { repo.setLocalFolder(it) }
    }
    val export = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { scope.launch { note = repo.exportTo(it).fold({ "已导出" }, { e -> e.message ?: "没导出成功" }) } }
    }
    val pickFile = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        fileRestore = uri
    }

    // 状态
    RsCard(padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(when { l.lastError != null -> c.critical; l.running -> c.accent; l.lastAt != null -> c.good; else -> c.textTertiary }, 9.dp)
            Spacer(Modifier.width(8.dp))
            Text(when {
                l.running -> "正在备份…"
                l.lastError != null -> "上次备份没成功"
                l.lastAt != null -> "已备份到本机"
                l.folder != null -> "还没备份过"
                else -> "还没选备份文件夹"
            }, style = RsType.headline, color = c.text)
        }
        Spacer(Modifier.height(6.dp))
        Text(l.lastError ?: listOfNotNull(
            l.lastAt?.let { "上次 ${dateTime(it)}" }, l.lastBytes?.let { formatBytes(it) }, l.count?.let { "本机 $it 份" },
        ).joinToString(" · ").ifEmpty { "不用注册账号：备份存到手机里你选的文件夹，换手机时把文件拷过去就能恢复。录音音频不备份。" },
            style = RsType.subhead, color = if (l.lastError != null) c.critical else c.textSecondary)
        Spacer(Modifier.height(14.dp))
        Row {
            if (l.folder != null) {
                Pill(if (l.running) "备份中…" else "立即备份", filled = !l.running) { if (!l.running) repo.backupLocalNow() }
                Spacer(Modifier.width(8.dp))
            }
            Pill("导出一份", filled = false) { export.launch("resound-" + java.time.LocalDate.now() + ".zip") }
        }
        note?.let { Spacer(Modifier.height(8.dp)); Text(it, style = RsType.caption, color = c.textSecondary) }
    }

    // 文件夹
    ActionRow("备份文件夹", l.folder ?: "建议在 Download 里新建 Resound", if (l.folder == null) "选择" else "更换") { pickFolder.launch(null) }

    if (l.folder != null) {
        // 自动与保留份数
        RsCard(padding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("每晚自动备份", style = RsType.body, color = c.text)
                    Text("和坚果云同一时间；不需要联网", style = RsType.caption, color = c.textSecondary)
                }
                Toggle(l.auto) { repo.setLocalAuto(it) }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("保留最近", style = RsType.body, color = c.text)
                    Text("每晚一个恢复点，多的自动删；文件夹里别的文件不动", style = RsType.caption, color = c.textSecondary)
                }
            }
            Spacer(Modifier.height(10.dp))
            val options = listOf(1, 3, 7)
            app.resound.ui.skills.Segments(options.map { "$it 天" }, options.indexOf(l.keep).coerceAtLeast(0)) { repo.setLocalKeep(options[it]) }
            if (l.keep == 1) {
                Spacer(Modifier.height(8.dp))
                Text("只留 1 天时，万一最新这份出问题就没有退路，建议至少 3 天。", style = RsType.caption, color = c.textTertiary)
            }
        }

        // 本机的备份列表
        Text("本机备份", style = RsType.section, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
        RsCard(padding = PaddingValues(vertical = 4.dp)) {
            val r = list
            when {
                r == null -> Text("正在读取…", style = RsType.subhead, color = c.textTertiary, modifier = Modifier.padding(16.dp))
                r.isFailure -> Text(r.exceptionOrNull()?.message ?: "读取失败", style = RsType.subhead, color = c.critical, modifier = Modifier.padding(16.dp))
                r.getOrThrow().isEmpty() -> Text("文件夹里还没有备份", style = RsType.subhead, color = c.textTertiary, modifier = Modifier.padding(16.dp))
                else -> r.getOrThrow().forEachIndexed { i, b ->
                    Row(Modifier.fillMaxWidth().springClick { onRestore(b.copy(name = LOCAL_TAG + b.name)) }.padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(dateTime(b.at), style = RsType.body, color = c.text)
                            Text(backupLine(b), style = RsType.caption, color = c.textTertiary)
                        }
                        Text("恢复 ›", style = RsType.subhead, color = c.accent)
                    }
                    if (i != r.getOrThrow().lastIndex) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
                }
            }
        }
    }

    // 从任意文件恢复（比如从旧手机拷来的、从坚果云网页下载的）
    ActionRow("从文件恢复", "选一个 resound-….zip 备份文件", "选择") { pickFile.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
    fileRestore?.let { uri ->
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "所选文件"
        RestoreSheet(app.resound.backup.RemoteBackup(name, System.currentTimeMillis(), 0, name.endsWith(".enc")),
            { pass -> repo.restoreFrom(uri, pass) }, title = "用所选文件恢复") { fileRestore = null }
    }
}
