package app.resound.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.resound.AppGraph
import kotlinx.coroutines.launch

/**
 * AUTH 界面（待 Claude 审核接管）：登录面板 + 账号区。
 * 集成方式：在 MainActivity 根布局放 <AuthLoginHost()/>；设置页放 <MyAccountSection()/>。
 * 取消登录只关掉面板，游客浏览不受影响；登录成功 AuthGate 自动回放被拦动作。
 */

/** 挂一次即可：观察 loginRequested，弹统一登录面板。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthLoginHost() {
    if (!AppGraph.isAuthReady) return
    val req by AppGraph.auth.loginRequested.collectAsState()
    req?.let { r ->
        ModalBottomSheet(onDismissRequest = { AppGraph.auth.dismissLogin() }) {
            LoginSheet(reason = r.reason, onCancel = { AppGraph.auth.dismissLogin() })
        }
    }
}

@Composable
private fun LoginSheet(reason: String, onCancel: () -> Unit) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("登录后使用", style = MaterialTheme.typography.titleLarge)
        Text(reason.ifBlank { "此操作需要账号授权" }, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(user, { user = it; error = null }, Modifier.fillMaxWidth(), label = { Text("账号") }, singleLine = true)
        OutlinedTextField(pass, { pass = it; error = null }, Modifier.fillMaxWidth(), label = { Text("密码") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), label = { Text("激活码（首次登录如需）") }, singleLine = true)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            TextButton(onClick = onCancel, enabled = !busy) { Text("取消") }
            Button(enabled = !busy && user.isNotBlank() && pass.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    when (val o = AppGraph.auth.login(user.trim(), pass, android.os.Build.MODEL ?: "android", code.trim())) {
                        is AuthGate.LoginOutcome.Ok -> AppGraph.auth.afterAuthSuccess()
                        is AuthGate.LoginOutcome.BadCredentials -> error = "账号或密码错误"
                        is AuthGate.LoginOutcome.NeedActivation -> error = "需要激活码，请向管理员索取"
                        is AuthGate.LoginOutcome.Disabled -> error = "账号已被停用"
                        is AuthGate.LoginOutcome.RateLimited -> error = "尝试太频繁，请稍后再试"
                        is AuthGate.LoginOutcome.Offline -> error = "网络不通，登录需要联网"
                        is AuthGate.LoginOutcome.Error -> error = o.msg
                    }
                    busy = false
                }
            }) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("登录") }
        }
    }
}

/** 设置页「我的账号」：状态 / 设备管理 / 退出。 */
@Composable
fun MyAccountSection(onManageDevices: () -> Unit = {}) {
    if (!AppGraph.isAuthReady) return
    val status by AppGraph.auth.status.collectAsState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("我的账号", style = MaterialTheme.typography.titleMedium)
        Text(
            when (status) {
                AuthStatus.Guest -> "游客模式——录音与个人数据需要登录授权"
                AuthStatus.Authenticated -> "已登录——等待授权凭证"
                AuthStatus.Authorized -> "已授权——离线期内可正常使用"
                AuthStatus.Expired -> "授权已过期——联网后自动续期"
                AuthStatus.Disabled -> "账号已停用"
                AuthStatus.Locked -> "隐私锁已启用"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (status == AuthStatus.Authorized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (status == AuthStatus.Guest) {
                TextButton(onClick = { AppGraph.auth.requestLogin(Capability.ReadPersonalData, "登录后使用全部功能") }) { Text("登录") }
            } else {
                TextButton(onClick = onManageDevices) { Text("设备管理") }
                TextButton(onClick = { scope.launch { AppGraph.auth.logout() } }) { Text("退出登录") }
            }
        }
    }
}
