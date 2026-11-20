package app.resound.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.resound.AppGraph
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.springClick
import kotlinx.coroutines.launch
import kotlinx.serialization.json.longOrNull

/**
 * AUTH 界面（待 Claude 审核接管）：登录面板 + 账号区。
 * 集成方式：在 MainActivity 根布局放 <AuthLoginHost()/>；设置页放 <MyAccountSection()/>。
 * 取消登录只关掉面板，游客浏览不受影响；登录成功 AuthGate 自动回放被拦动作。
 */

/** 挂一次即可：观察 loginRequested，弹统一登录面板；已登录但未归属时弹归属确认。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthLoginHost() {
    if (!AppGraph.isAuthReady) return
    val req by AppGraph.auth.loginRequested.collectAsState()
    req?.let { r ->
        // 跳过半展开：内容三字段+按钮在小屏/折叠屏上半展开会被底栏截断。
        ModalBottomSheet(onDismissRequest = { AppGraph.auth.dismissLogin() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            // 只有归属硬阻断才弹归属确认；凭证过期等登录态问题仍给登录面板（重新登录即续期）。
            if (AppGraph.auth.ownershipIssue())
                BindSheet(reason = r.reason, onCancel = { AppGraph.auth.dismissLogin() })
            else
                LoginSheet(reason = r.reason, onCancel = { AppGraph.auth.dismissLogin() })
        }
    }
}

/** 归属确认面板：系统凭据验证机主 → 备份验证 → 绑定 → 回放被拦动作。 */
@Composable
private fun BindSheet(reason: String, onCancel: () -> Unit) {
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val doBind: () -> Unit = {
        AppGraph.auth.markOwnerVerified(); busy = true
        scope.launch {
            when (AppGraph.auth.bindLocalData()) {
                is AuthGate.BindOutcome.Bound, is AuthGate.BindOutcome.AlreadyBound ->
                    AppGraph.auth.afterAuthSuccess()
                is AuthGate.BindOutcome.BackupUnavailable ->
                    error = "还没有可用备份：请先在「备份」里选本机文件夹或配置云备份，再绑定"
                is AuthGate.BindOutcome.BackupFailed ->
                    error = "备份未完成，归属未变更；请检查备份后重试"
                else -> error = "绑定失败，数据未变更"
            }
            busy = false
        }
    }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) doBind() else error = "未通过机主验证"
    }
    // 键盘顶起/小屏时按钮必须可达：列可滚动 + imePadding 把内容抬到键盘上方（实测登录按钮曾被键盘顶出可视区）。
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("确认本机数据归属", style = MaterialTheme.typography.titleLarge)
        Text(reason.ifBlank { "本机有历史数据未归属。验证机主身份并备份成功后，将绑定到当前账号。" },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            TextButton(onClick = onCancel, enabled = !busy) { Text("暂不") }
            Button(enabled = !busy, onClick = { startOwnerCredential(context, launcher, doBind) }) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("验证机主并绑定")
            }
        }
    }
}

/** 发起系统设备凭据验证；无锁屏（无法验证）按机主在场处理——直接进入绑定。 */
private fun startOwnerCredential(context: android.content.Context,
                                 launcher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>,
                                 onVerified: () -> Unit) {
    val km = context.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
    val intent = runCatching { km.createConfirmDeviceCredentialIntent("声声不息", "验证机主身份以确认数据归属") }.getOrNull()
    if (intent != null) launcher.launch(intent) else onVerified()
}

@Composable
private fun LoginSheet(reason: String, onCancel: () -> Unit) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var registerMode by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 键盘顶起/小屏时按钮必须可达：列可滚动 + imePadding 把内容抬到键盘上方（实测登录按钮曾被键盘顶出可视区）。
    Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
        // 登录 / 注册 页签：自助注册即 Free，注册成功直接落会话并回放被拦动作。
        TabRow(selectedTabIndex = if (registerMode) 1 else 0, containerColor = MaterialTheme.colorScheme.surface) {
            Tab(selected = !registerMode, onClick = { registerMode = false; error = null }, text = { Text("登录") })
            Tab(selected = registerMode, onClick = { registerMode = true; error = null }, text = { Text("注册") })
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (registerMode) "注册 Free 账号" else "登录后使用", style = MaterialTheme.typography.titleLarge)
        Text(reason.ifBlank { if (registerMode) "注册即得免费版：每月 30 小时转写 + 100 官方 AI 点" else "此操作需要账号授权" },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(user, { user = it; error = null }, Modifier.fillMaxWidth(), label = { Text("账号") }, singleLine = true)
        OutlinedTextField(pass, { pass = it; error = null }, Modifier.fillMaxWidth(), label = { Text("密码") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        var pass2 by remember { mutableStateOf("") }
        if (registerMode) {
            OutlinedTextField(pass2, { pass2 = it; error = null }, Modifier.fillMaxWidth(), label = { Text("确认密码") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        } else {
            OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), label = { Text("激活码（首次登录如需）") }, singleLine = true)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            TextButton(onClick = onCancel, enabled = !busy) { Text("取消") }
            Button(enabled = !busy && user.isNotBlank() && pass.isNotBlank() && (!registerMode || pass2.isNotBlank()), onClick = {
                if (registerMode && pass != pass2) { error = "两次输入的密码不一致"; return@Button }
                busy = true
                scope.launch {
                    if (registerMode) {
                        when (val o = AppGraph.auth.register(user.trim(), pass, android.os.Build.MODEL ?: "android")) {
                            is AuthGate.RegisterOutcome.Ok -> AppGraph.auth.afterAuthSuccess()
                            is AuthGate.RegisterOutcome.UsernameTaken -> error = "账号名已被占用，换一个试试"
                            is AuthGate.RegisterOutcome.RateLimited -> error = "注册太频繁，请稍后再试"
                            is AuthGate.RegisterOutcome.Closed -> error = "暂未开放自助注册"
                            is AuthGate.RegisterOutcome.Offline -> error = "网络不通，注册需要联网"
                            is AuthGate.RegisterOutcome.Invalid -> error = when {
                                o.msg.contains("username") -> "账号名需 3~24 位字母、数字、下划线或连字符"
                                o.msg.contains("password") -> "密码至少 8 位"
                                else -> o.msg
                            }
                            is AuthGate.RegisterOutcome.Error -> error = o.msg
                        }
                    } else {
                        when (val o = AppGraph.auth.login(user.trim(), pass, android.os.Build.MODEL ?: "android", code.trim())) {
                            is AuthGate.LoginOutcome.Ok -> AppGraph.auth.afterAuthSuccess()
                            is AuthGate.LoginOutcome.BadCredentials -> error = "账号或密码错误"
                            is AuthGate.LoginOutcome.NeedActivation -> error = "需要激活码，请向管理员索取"
                            is AuthGate.LoginOutcome.Disabled -> error = "账号已被停用"
                            is AuthGate.LoginOutcome.RateLimited -> error = "尝试太频繁，请稍后再试"
                            is AuthGate.LoginOutcome.Offline -> error = "网络不通，登录需要联网"
                            is AuthGate.LoginOutcome.Error -> error = o.msg
                        }
                    }
                    busy = false
                }
            }) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(if (registerMode) "注册并登录" else "登录") }
        }
        }
    }
}

/** 胶囊按钮：filled=主色实心（主操作），danger=红调（退出类），默认浅紫底紫字（次操作）。 */
@Composable
private fun AccPill(text: String, filled: Boolean = false, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Rs.colors
    val bg = if (!enabled) c.track else when {
        filled -> c.accent
        danger -> c.critical.copy(alpha = 0.10f)
        else -> c.accentSoft
    }
    val fg = if (!enabled) c.textTertiary else when {
        filled -> c.onAccent
        danger -> c.critical
        else -> c.accent
    }
    Box(Modifier.then(if (enabled) Modifier.springClick(onClick) else Modifier)
        .clip(CircleShape).background(bg).padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center) {
        Text(text, style = RsType.subhead, color = fg)
    }
}

/** 圆角填充底输入框，与项目设置项同款。 */
@Composable
private fun AccField(value: String, placeholder: String, onChange: (String) -> Unit) {
    val c = Rs.colors
    BasicTextField(value, onChange, singleLine = true,
        textStyle = RsType.body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(Modifier.clip(RoundedCornerShape(12.dp)).background(c.fill)
                .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = RsType.body, color = c.textTertiary)
                    inner()
                }
            }
        })
}

/** 设置页「账号与会员」：账号卡（状态/归属/设备/退出）+ 会员卡（套餐/兑换）。 */
@Composable
fun MyAccountSection(onManageDevices: () -> Unit = {}) {
    if (!AppGraph.isAuthReady) return
    val status by AppGraph.auth.status.collectAsState()
    val store = AppGraph.auth.store
    val scope = rememberCoroutineScope()
    var rev by remember { mutableStateOf(0) } // 绑定/登出后强制重读归属标志
    val c = Rs.colors
    @Suppress("UNUSED_EXPRESSION") rev
    val bound = store.boundAccountId
    val loggedIn = store.sessionAccountId != null
    // 页面外层无 Surface：LocalContentColor 默认黑，未显式着色的标题/正文会黑到看不见——统一补上。
    CompositionLocalProvider(LocalContentColor provides c.text) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RsCard {
            Text("我的账号", style = RsType.section, color = c.textSecondary)
            Spacer(Modifier.height(10.dp))
            Text(
                when (status) {
                    AuthStatus.Guest -> "游客模式——录音与个人数据需要登录授权"
                    AuthStatus.Authenticated -> "已登录——等待授权凭证"
                    AuthStatus.Authorized -> "已授权——离线期内可正常使用"
                    AuthStatus.Expired -> "授权已过期——联网后自动续期"
                    AuthStatus.Disabled -> "账号已停用"
                    AuthStatus.Locked -> "隐私锁已启用"
                },
                style = RsType.body,
                color = if (status == AuthStatus.Authorized) c.accent else c.textSecondary,
            )
            // 数据归属：未绑定且有历史数据 → 提示确认归属；绑定错号 → 明确提示隔离中。
            if (bound == null && loggedIn && store.hasLocalData) {
                Spacer(Modifier.height(8.dp))
                Text("本机有历史数据尚未归属。确认后只有本账号能读取，其他账号登录本机将被隔离。",
                    style = RsType.caption, color = c.textSecondary)
                var bindNote by remember { mutableStateOf<String?>(null) }
                var busy by remember { mutableStateOf(false) }
                val context = androidx.compose.ui.platform.LocalContext.current
                val doBind: () -> Unit = {
                    AppGraph.auth.markOwnerVerified(); busy = true
                    scope.launch {
                        bindNote = when (AppGraph.auth.bindLocalData()) {
                            is AuthGate.BindOutcome.Bound -> { rev++; "已绑定：本机数据归属当前账号" }
                            is AuthGate.BindOutcome.AlreadyBound -> { rev++; "已是本账号数据" }
                            is AuthGate.BindOutcome.BoundToOther -> "已归属其他账号，未变更"
                            is AuthGate.BindOutcome.BackupUnavailable -> "还没有可用备份：请先在「备份」里选本机文件夹或配置云备份"
                            is AuthGate.BindOutcome.BackupFailed -> "备份未完成，归属未变更；请检查备份后重试"
                            else -> "绑定失败，数据未变更"
                        }
                        busy = false
                    }
                }
                val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { res ->
                    if (res.resultCode == android.app.Activity.RESULT_OK) doBind() else bindNote = "未通过机主验证"
                }
                Spacer(Modifier.height(10.dp))
                AccPill(if (busy) "绑定中…" else "绑定本机数据到此账号", filled = true, enabled = !busy) {
                    startOwnerCredential(context, launcher, doBind)
                }
                bindNote?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = RsType.caption,
                        color = if (it.startsWith("已绑定") || it.startsWith("已是")) c.accent else c.critical)
                }
            } else if (bound != null && loggedIn && bound != store.sessionAccountId) {
                Spacer(Modifier.height(6.dp))
                Text("本机数据已归属另一账号——当前账号的录音与个人数据访问被隔离。",
                    style = RsType.caption, color = c.critical)
            } else if (bound != null && loggedIn) {
                Spacer(Modifier.height(6.dp))
                Text("本机数据已归属当前账号", style = RsType.caption, color = c.textSecondary)
            }
            var showDevices by remember { mutableStateOf(false) }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (status == AuthStatus.Guest) {
                    AccPill("登录", filled = true) { AppGraph.auth.requestLogin(Capability.ReadPersonalData, "登录后使用全部功能") }
                } else {
                    AccPill(if (showDevices) "收起设备" else "设备管理") { showDevices = !showDevices; onManageDevices() }
                    AccPill("退出登录", danger = true) { scope.launch { AppGraph.auth.logout(); rev++ } }
                }
            }
            if (showDevices) AuthDevicesList()
        }
        if (loggedIn) MembershipCard()
    }
    }
}

/** 会员中心：套餐 / 本月额度 / 兑换码。数据来自服务端视图或受约束的离线缓存。 */
@Composable
private fun MembershipCard() {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) } // sync/redeem 后强制重读
    var syncNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (!AppGraph.membership.sync()) syncNote = "无法连接授权服务，显示为离线缓存"
        runCatching { AppGraph.membership.flush() }
        tick++
    }
    @Suppress("UNUSED_EXPRESSION") tick
    val view = AppGraph.membership.cachedView
    val pro = AppGraph.membership.isPro()
    val used = AppGraph.membership.usedMinutes()
    val limit = AppGraph.membership.freeLimit()
    val remaining = AppGraph.membership.remainingMinutes()
    val fmt = { m: Double -> if (m >= 60) "%.1f 小时".format(m / 60) else "%.0f 分钟".format(m) }
    RsCard {
        Text("会员", style = RsType.section, color = c.textSecondary)
        Spacer(Modifier.height(10.dp))
        if (pro) {
            val until = view?.proUntil?.let { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(it * 1000)) }
            Text("Pro 会员" + (until?.let { " · 到期 $it" } ?: ""), style = RsType.body, color = c.accent)
            Spacer(Modifier.height(4.dp))
            Text("本地转写不限时长", style = RsType.caption, color = c.textSecondary)
        } else {
            Text("免费版 · 每月 ${fmt(limit)} 转写额度", style = RsType.body, color = c.text)
            Spacer(Modifier.height(4.dp))
            Text("本月已用 ${fmt(used)} · 剩余 ${fmt(remaining)}", style = RsType.caption,
                color = if (remaining <= 0.0) c.critical else c.textSecondary)
            if (remaining <= 0.0) {
                Spacer(Modifier.height(4.dp))
                Text("免费额度已用完：新转写已暂停，录音与保存不受影响；下月恢复或兑换 Pro",
                    style = RsType.caption, color = c.critical)
            }
        }
        AppGraph.membership.lastSyncAt.takeIf { it > 0 }?.let {
            Spacer(Modifier.height(4.dp))
            Text("上次同步 " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it)),
                style = RsType.caption, color = c.textTertiary)
        }
        syncNote?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = RsType.caption, color = c.textSecondary)
        }
        var code by remember { mutableStateOf("") }
        var msg by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        Spacer(Modifier.height(12.dp))
        AccField(code, "兑换码") { code = it; msg = null }
        msg?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = RsType.caption,
                color = if (it.startsWith("兑换成功")) c.accent else c.critical)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AccPill(if (busy) "兑换中…" else "兑换", filled = true, enabled = !busy && code.isNotBlank()) {
                busy = true; msg = null
                scope.launch {
                    msg = when (val r = AppGraph.auth.redeem(code.trim())) {
                        is AuthGate.RedeemOutcome.Ok ->
                            if (r.duplicated) "该码你已兑换过（${r.grantedDays} 天）"
                            else { code = ""; AppGraph.membership.sync(); tick++; "兑换成功：Pro +${r.grantedDays} 天" }
                        is AuthGate.RedeemOutcome.Invalid -> "兑换码无效"
                        is AuthGate.RedeemOutcome.Rejected -> "兑换失败：${r.msg}"
                        is AuthGate.RedeemOutcome.Offline -> "网络不通，兑换需要联网"
                        is AuthGate.RedeemOutcome.NeedLogin -> "请先登录"
                    }
                    busy = false
                }
            }
            AccPill("刷新") { scope.launch { syncNote = null; if (!AppGraph.membership.sync()) syncNote = "无法连接授权服务"; tick++ } }
        }
    }
}

/** 设备会话列表：拉取 /auth/devices，支持撤销单个会话（踢下线）。 */
@Composable
private fun AuthDevicesList() {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<kotlinx.serialization.json.JsonObject>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableStateOf(0) }
    LaunchedEffect(refreshKey) {
        note = null
        when (val r = AppGraph.auth.devices()) {
            is AuthApi.Result.Ok -> devices = runCatching {
                r.body["sessions"]?.let { it as? kotlinx.serialization.json.JsonArray }
                    ?.mapNotNull { it as? kotlinx.serialization.json.JsonObject }
                    ?.filter { (it["revoked_at"] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull == null }
            }.getOrNull() ?: emptyList()
            is AuthApi.Result.Denied -> note = "无权查看设备（${r.code}）"
            is AuthApi.Result.Network -> note = "网络不通，稍后重试"
            null -> note = "未登录"
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("登录设备", style = RsType.section, color = c.textSecondary)
        note?.let { Text(it, style = RsType.caption, color = c.critical) }
        devices?.takeIf { it.isEmpty() }?.let { Text("没有其他登录设备", style = RsType.caption, color = c.textSecondary) }
            ?: if (devices == null && note == null) Text("加载中…", style = RsType.caption, color = c.textTertiary) else Unit
        devices?.forEach { d ->
            // worker /auth/devices 返回 sessions[]：id, device_id, device_name, created_at, last_seen_at, revoked_at
            val sid = d["id"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content ?: return@forEach
            val name = d["device_name"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content ?: "未知设备"
            val lastSeen = d["last_seen_at"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.longOrNull
            val current = d["device_id"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content ==
                AppGraph.auth.store.deviceId
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name + if (current) "（本机）" else "", style = RsType.body, color = c.text)
                    lastSeen?.let {
                        Text("最近活跃 " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it * 1000)),
                            style = RsType.caption, color = c.textTertiary)
                    }
                }
                if (!current) AccPill("踢下线") { scope.launch {
                    AppGraph.auth.revokeDevice(sid); refreshKey++
                } }
            }
        }
    }
}
