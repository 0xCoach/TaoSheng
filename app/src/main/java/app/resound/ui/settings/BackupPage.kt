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
import androidx.compose.runtime.mutableIntStateOf
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
    val cache = remember(repo) { BackupPageCache.forRepository(repo) }
    val cloudCache = remember(cache, s.account, configured) { cache.cloud(s.account, configured) }
    var remote by remember(cloudCache) { mutableStateOf(cloudCache.backups) }
    var cloud by remember(cloudCache) { mutableStateOf(cloudCache.summary) }
    var cloudReadError by remember(cloudCache) { mutableStateOf<String?>(null) }
    var restoringAll by remember { mutableStateOf<String?>(null) }
    val local by repo.local.collectAsState()
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(if (configured && local.folder == null) 1 else 0) }
    androidx.compose.runtime.LaunchedEffect(cloudCache, s.lastBackupAt, tab) {
        if (configured && tab == 1) {
            cloudReadError = null
            val overview = repo.cloudSummary()
            cloudCache.acceptSummary(overview); cloud = cloudCache.summary
            if (overview.isFailure) cloudReadError = overview.exceptionOrNull()?.message ?: "请稍后重试"
            // 限流或断网时避免追加请求；口令不匹配时仍允许查看历史版本。
            if ((overview.exceptionOrNull() as? app.resound.backup.BackupFailure)?.retryable != true) {
                val files = repo.remoteBackups()
                cloudCache.acceptBackups(files); remote = cloudCache.backups
                if (files.isFailure) cloudReadError = files.exceptionOrNull()?.message ?: "请稍后重试"
            }
        }
    }
    val success = latestBackupSuccess(cloud, s.lastBackupAt, s.lastBackupBytes)

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding + 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        app.resound.ui.skills.Segments(listOf("本机", "坚果云"), tab) { tab = it }
        if (tab == 0) LocalBackupSection(repo, cache, local, { restoringAll = "local" }) { restoring = it }
        else {
        // 状态
        RsCard(Modifier.animateContentSize(), padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(when { s.running -> c.accent; success.at != null -> c.good; s.lastError != null -> c.critical; else -> c.textTertiary }, 9.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    backupHeadline(false, configured, s.running, success.at, s.lastError),
                    style = RsType.headline, color = c.text,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                summaryLine(cloud, s.lastBackupAt, s.lastBackupBytes).ifEmpty { "每天凌晨自动备份：记录、设置、密钥和账号全都备份，每晚只传有变化的那几天。录音音频不备份。" },
                style = RsType.subhead,
                color = c.textSecondary,
            )
            if (!s.running) s.lastError?.let { Spacer(Modifier.height(6.dp)); Text("最近一次操作未完成：$it", style = RsType.caption, color = c.warning) }
            cloudReadError?.let { Spacer(Modifier.height(6.dp)); Text("备份信息暂未刷新：$it", style = RsType.caption, color = c.warning) }
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
                    Text("每天凌晨 3 点左右，有网就传，手机流量也行", style = RsType.caption, color = c.textSecondary)
                }
                Toggle(s.enabled) { repo.setEnabled(it) }
            }

        }

        // 云端恢复：新格式一键恢复全部；历史版本收在下面
        if (configured) {
            androidx.compose.animation.AnimatedVisibility(cloud != null, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
                ActionRow("从云端恢复全部", "记录、设置、密钥和账号全部还原", "恢复") { restoringAll = "cloud" }
            }
            HistoryRetention(s.keep, repo::setKeep)
            HistoryList(remote) { restoring = it }
        }
        }

        // 备份加密：本机和坚果云共用一个口令
        RsCard(padding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("备份加密", style = RsType.body, color = c.text)
                    Text(if (s.encrypted) "已开启：之后的备份（本机和坚果云）都用你的口令加密" else "备份里有你的密钥和账号，建议开启。口令只存在手机里，忘了就无法恢复",
                        style = RsType.caption, color = if (s.encrypted) c.textSecondary else c.warning)
                }
                Toggle(s.encrypted) { on -> if (on) settingPass = true else repo.setEncryption(null) }
            }
        }
    }
    if (settingPass) PassphraseSheet { pass -> if (pass != null) repo.setEncryption(pass); settingPass = false }
    restoringAll?.let { from ->
        RestoreSheet(app.resound.backup.RemoteBackup("all", System.currentTimeMillis(), 0, s.encrypted),
            { pass -> if (from == "cloud") repo.restoreAllFromCloud(pass) else repo.restoreAllFromLocal(pass) },
            title = if (from == "cloud") "从云端恢复全部" else "从本机文件夹恢复全部") { restoringAll = null }
    }
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
    Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm:ss"))

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
    app.resound.ui.verify.RsSheet(title ?: "恢复到 ${dateTime(b.at)}", "用备份替换手机上的全部记录，设置、密钥和账号也一起还原（备份里没有的保留手机上现在的）。替换前会先在本机留一份快照，恢复后 App 会自动重启。",
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


/** 概况一句话：共几天 · 合计多大 · 上次几点、传了多少 */
private fun summaryLine(sum: app.resound.backup.BackupSummary?, lastAt: Long?, lastBytes: Long?): String {
    val success = latestBackupSuccess(sum, lastAt, lastBytes)
    return listOfNotNull(
        sum?.let { "共 ${it.days} 天的记录" }, sum?.let { "合计 ${formatBytes(it.bytes)}" },
        success.at?.let { "最近成功 ${dateTime(it)}" }, success.uploadedBytes?.let { "传了 ${formatBytes(it)}" },
    ).joinToString(" · ")
}

/** 可选择的历史恢复点，默认展开。 */
@Composable
private fun HistoryList(list: Result<List<app.resound.backup.RemoteBackup>>?, onRestore: (app.resound.backup.RemoteBackup) -> Unit) {
    val c = Rs.colors
    val items = list?.getOrNull().orEmpty()
    if (items.isEmpty()) {
        Text("历史版本", style = RsType.section, color = c.textSecondary)
        Text("完成下一次备份后，会在这里保留可按时间恢复的版本。", style = RsType.caption, color = c.textTertiary)
        return
    }
    var open by remember { mutableStateOf(true) }
    Text("历史版本 · ${items.size} 份 ${if (open) "▾" else "▸"}", style = RsType.section, color = c.textSecondary,
        modifier = Modifier.springClick { open = !open }.padding(start = 4.dp, top = 4.dp))
    if (open) RsCard(padding = PaddingValues(vertical = 4.dp)) {
        items.forEachIndexed { i, b ->
            Row(Modifier.fillMaxWidth().springClick { onRestore(b) }.padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(dateTime(b.at), style = RsType.body, color = c.text)
                    Text("增量恢复点 · " + formatBytes(b.bytes) + if (b.encrypted) " · 已加密" else "", style = RsType.caption, color = c.textTertiary)
                }
                Text("恢复 ›", style = RsType.subhead, color = c.accent)
            }
            if (i != items.lastIndex) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(c.separator))
        }
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
private fun LocalBackupSection(repo: BackupRepository, cache: BackupPageCache, l: app.resound.backup.LocalBackupState, onRestoreAll: () -> Unit, onRestore: (app.resound.backup.RemoteBackup) -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    var folderEpoch by remember { mutableIntStateOf(0) }
    val localCache = remember(cache, l.folder, folderEpoch) { cache.local(l.folder) }
    var list by remember(localCache) { mutableStateOf(localCache.backups) }
    var note by remember { mutableStateOf<String?>(null) }
    var readError by remember(localCache) { mutableStateOf<String?>(null) }
    var fileRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    var summary by remember(localCache) { mutableStateOf(localCache.summary) }
    androidx.compose.runtime.LaunchedEffect(localCache, l.lastAt, l.count) {
        if (l.folder != null) {
            readError = null
            val overview = repo.localSummary()
            localCache.acceptSummary(overview); summary = localCache.summary
            if (overview.isFailure) readError = overview.exceptionOrNull()?.message ?: "请稍后重试"
            if ((overview.exceptionOrNull() as? app.resound.backup.BackupFailure)?.retryable != true) {
                val files = repo.localBackups()
                localCache.acceptBackups(files); list = localCache.backups
                if (files.isFailure) readError = files.exceptionOrNull()?.message ?: "请稍后重试"
            }
        }
    }
    val success = latestBackupSuccess(summary, l.lastAt, l.lastBytes)
    val pickFolder = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { cache.invalidateLocal(); repo.setLocalFolder(it); folderEpoch++ }
    }
    val export = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { scope.launch { note = repo.exportTo(it).fold({ "已导出" }, { e -> e.message ?: "没导出成功" }) } }
    }
    val pickFile = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        fileRestore = uri
    }

    // 状态
    RsCard(Modifier.animateContentSize(), padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(when { l.running -> c.accent; success.at != null -> c.good; l.lastError != null -> c.critical; else -> c.textTertiary }, 9.dp)
            Spacer(Modifier.width(8.dp))
            Text(backupHeadline(true, l.folder != null, l.running, success.at, l.lastError), style = RsType.headline, color = c.text)
        }
        Spacer(Modifier.height(6.dp))
        Text(summaryLine(summary, l.lastAt, l.lastBytes).ifEmpty { "不用注册账号：记录、设置、密钥和账号都存到你选的文件夹，每晚只写有变化的那几天。换手机时把文件夹拷过去就能恢复。录音音频不备份。" },
            style = RsType.subhead, color = c.textSecondary)
        if (!l.running) l.lastError?.let { Spacer(Modifier.height(6.dp)); Text("最近一次操作未完成：$it", style = RsType.caption, color = c.warning) }
        readError?.let { Spacer(Modifier.height(6.dp)); Text("备份信息暂未刷新：$it", style = RsType.caption, color = c.warning) }
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
        }

        androidx.compose.animation.AnimatedVisibility(summary != null, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
            ActionRow("从本机文件夹恢复全部", "记录、设置、密钥和账号全部还原", "恢复", onRestoreAll)
        }
        HistoryRetention(l.keep, repo::setLocalKeep)
        HistoryList(list?.map { all -> all.map { it.copy(name = LOCAL_TAG + it.name) } }, onRestore)
    }

    // 从任意文件恢复（比如从旧手机拷来的、从坚果云网页下载的）
    ActionRow("从文件恢复", "选一个 resound-….zip 备份文件", "选择") { pickFile.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
    fileRestore?.let { uri ->
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "所选文件"
        RestoreSheet(app.resound.backup.RemoteBackup(name, System.currentTimeMillis(), 0, name.endsWith(".enc")),
            { pass -> repo.restoreFrom(uri, pass) }, title = "用所选文件恢复") { fileRestore = null }
    }
}

@Composable
private fun HistoryRetention(keep: Int, onChange: (Int) -> Unit) {
    val c = Rs.colors
    RsCard(padding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
        Text("历史版本保留", style = RsType.body, color = c.text)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(3, 7, 30).forEach { count -> Pill("$count 份", filled = keep == count) { onChange(count) } }
        }
        Spacer(Modifier.height(6.dp))
        Text("每次成功备份保留一个恢复点，未变化的数据共用。下次备份时按所选数量清理。", style = RsType.caption, color = c.textSecondary)
    }
}
