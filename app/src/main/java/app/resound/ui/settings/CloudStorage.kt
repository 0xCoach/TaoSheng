package app.resound.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.resound.backup.BackupRepository
import app.resound.backup.BackupState
import app.resound.backup.CloudKind
import app.resound.backup.S3Preset
import app.resound.backup.S3Settings
import app.resound.ui.items.Choice
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.Pill
import app.resound.ui.verify.RsCard
import kotlinx.coroutines.launch

/** 卡 114：云端存储三选一（坚果云 / WebDAV / S3）的显示与编辑 */

internal fun CloudKind.title() = when (this) { CloudKind.Nutstore -> "坚果云"; CloudKind.WebDav -> "WebDAV"; CloudKind.S3 -> "S3 存储" }

/** 已配好时的一行说明：账号、地址或桶 */
internal fun BackupState.cloudLine(): String = when (cloud) {
    CloudKind.Nutstore -> account.orEmpty()
    CloudKind.WebDav -> listOfNotNull(account, webDavUrl?.removePrefix("https://")?.removePrefix("http://")?.trimEnd('/')).joinToString(" · ")
    CloudKind.S3 -> s3?.let { "${it.bucket} · ${it.endpoint.removePrefix("https://").removePrefix("http://")}" }.orEmpty()
}

/** 地址模板里除 {region} 以外要用户补的那一项 */
private fun extraSlot(template: String): Pair<String, String>? = when {
    "{accountId}" in template -> "{accountId}" to "账户 ID（R2 控制台右侧）"
    "{host}" in template -> "{host}" to "服务器地址，如 192.168.1.10"
    else -> null
}

private fun fill(template: String, region: String, extra: String): String =
    template.replace("{region}", region).replace("{accountId}", extra).replace("{host}", extra)

@Composable
internal fun CloudStorageCard(repo: BackupRepository, s: BackupState) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    val configured = s.account != null && s.hasPassword
    var editing by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<String>?>(null) }
    RsCard(Modifier.animateContentSize(), padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
        Text("云端存储", style = RsType.body, color = c.text)
        Spacer(Modifier.height(10.dp))
        AnimatedContent(configured && !editing, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "cloud") { showing ->
            if (showing) Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.cloud.title(), style = RsType.subhead, color = c.text)
                        Text(s.cloudLine(), style = RsType.caption, color = c.textSecondary, maxLines = 1)
                    }
                    Pill(if (testing) "测试中" else "测试", filled = false) {
                        if (testing) return@Pill
                        testing = true
                        scope.launch { result = repo.test(); testing = false }
                    }
                    Spacer(Modifier.width(8.dp))
                    Pill("更换", filled = false) { editing = true; result = null }
                }
                result?.let { r ->
                    Spacer(Modifier.height(8.dp))
                    Text(r.getOrNull() ?: r.exceptionOrNull()?.message ?: "测试没通过", style = RsType.caption, color = if (r.isSuccess) c.good else c.critical)
                }
            } else CloudEditor(repo, s, cancellable = configured) { editing = false }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CloudEditor(repo: BackupRepository, s: BackupState, cancellable: Boolean, onDone: () -> Unit) {
    val c = Rs.colors
    val presets = remember(repo) { repo.s3Presets }
    var kind by remember { mutableStateOf(s.cloud) }
    // 换类型时账号不沿用另一种的（坚果云邮箱不能当 S3 密钥 ID）
    var account by remember(kind) { mutableStateOf(if (kind == s.cloud) s.account.orEmpty() else "") }
    var secret by remember(kind) { mutableStateOf("") }
    var url by remember { mutableStateOf(s.webDavUrl.orEmpty()) }
    val saved = s.s3
    var preset by remember { mutableStateOf(presets.firstOrNull { it.id == saved?.presetId } ?: presets.firstOrNull()) }
    var region by remember { mutableStateOf(saved?.region ?: preset?.regions?.firstOrNull().orEmpty()) }
    var extra by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf(saved?.endpoint.orEmpty()) }
    var bucket by remember { mutableStateOf(saved?.bucket.orEmpty()) }
    var prefix by remember { mutableStateOf(saved?.prefix ?: "resound/") }
    var pathStyle by remember { mutableStateOf(saved?.pathStyle ?: preset?.pathStyle ?: false) }
    var error by remember { mutableStateOf<String?>(null) }
    // 预设地址：选服务商、区域、补账户 ID 时自动填；自定义时由用户自己填
    fun refill(p: S3Preset?) { if (p != null && p.endpointTemplate.isNotEmpty()) endpoint = fill(p.endpointTemplate, region, extra) }
    val reuseSecret = kind == s.cloud && s.hasPassword
    val ready = account.isNotBlank() && (secret.isNotBlank() || reuseSecret) && when (kind) {
        CloudKind.Nutstore -> true
        CloudKind.WebDav -> url.isNotBlank()
        CloudKind.S3 -> endpoint.isNotBlank() && region.isNotBlank() && bucket.isNotBlank() && "{" !in endpoint
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CloudKind.entries.forEach { k -> Choice(k.title(), kind == k) { kind = k; error = null } }
        }
        when (kind) {
            CloudKind.Nutstore -> {
                CloudField(account, "邮箱", KeyboardType.Email) { account = it.trim() }
                CloudField(secret, if (reuseSecret) "应用密码（不改就留空）" else "第三方应用密码（不是登录密码）", KeyboardType.Password, secret = true) { secret = it.trim() }
                Hint("应用密码在坚果云网页版“账户信息 → 安全选项 → 第三方应用管理”里生成。")
            }
            CloudKind.WebDav -> {
                CloudField(url, "地址，如 https://dav.example.com/remote.php/dav/files/我/", KeyboardType.Uri) { url = it.trim() }
                CloudField(account, "用户名", KeyboardType.Text) { account = it.trim() }
                CloudField(secret, if (reuseSecret) "密码（不改就留空）" else "密码", KeyboardType.Password, secret = true) { secret = it.trim() }
                Hint("Nextcloud、群晖、威联通等都支持 WebDAV。只接受 https；家里局域网的地址可以用 http。")
            }
            CloudKind.S3 -> {
                Label("服务商")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { p ->
                        Choice(p.title, preset?.id == p.id) {
                            preset = p; pathStyle = p.pathStyle
                            region = p.regions.firstOrNull().orEmpty(); extra = ""
                            endpoint = if (p.endpointTemplate.isEmpty()) "" else fill(p.endpointTemplate, region, "")
                        }
                    }
                }
                val p = preset
                if (p != null && p.regions.size > 1) {
                    Label("区域")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        p.regions.forEach { r -> Choice(r, region == r) { region = r; refill(p) } }
                    }
                }
                if (p == null || p.endpointTemplate.isEmpty() || p.regions.isEmpty())
                    CloudField(region, "区域，如 us-east-1", KeyboardType.Text) { region = it.trim(); refill(p) }
                else if (region !in p.regions)  // 恢复的设置里区域不在常用列表，也能看到并改
                    CloudField(region, "区域", KeyboardType.Text) { region = it.trim(); refill(p) }
                extraSlot(p?.endpointTemplate.orEmpty())?.let { (_, hint) ->
                    CloudField(extra, hint, KeyboardType.Uri) { extra = it.trim(); refill(p) }
                }
                CloudField(endpoint, "服务地址，如 https://s3.example.com", KeyboardType.Uri) { endpoint = it.trim() }
                CloudField(bucket, "存储桶名", KeyboardType.Text) { bucket = it.trim() }
                CloudField(prefix, "桶里的目录（默认 resound/，可留空）", KeyboardType.Text) { prefix = it.trim() }
                CloudField(account, "访问密钥 ID", KeyboardType.Text) { account = it.trim() }
                CloudField(secret, if (reuseSecret) "私有访问密钥（不改就留空）" else "私有访问密钥", KeyboardType.Password, secret = true) { secret = it.trim() }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("路径式地址", style = RsType.subhead, color = c.text)
                        Text(if (pathStyle) "地址/桶名/文件" else "桶名.地址/文件", style = RsType.caption, color = c.textSecondary)
                    }
                    app.resound.ui.skills.Toggle(pathStyle) { pathStyle = it }
                }
                p?.hint?.takeIf { it.isNotBlank() }?.let { Hint(it) }
                Hint("存储桶要先在服务商那里建好。")
            }
        }
        error?.let { Text(it, style = RsType.caption, color = c.critical) }
        Row {
            Pill("保存", filled = ready) {
                if (!ready) return@Pill
                val s3 = if (kind == CloudKind.S3) S3Settings(preset?.id ?: "custom", endpoint, region, bucket, prefix, pathStyle) else null
                val before = repo.state.value.lastError
                repo.saveCloud(kind, account, secret.ifBlank { null }, url.takeIf { kind == CloudKind.WebDav }, s3)
                val after = repo.state.value.lastError
                // 保存失败（地址不合法、安全区写不进）留在编辑页给出原因，不假装成功
                if (after != null && after != before) error = after else { secret = ""; error = null; onDone() }
            }
            if (cancellable) {
                Spacer(Modifier.width(8.dp))
                Pill("取消", filled = false) { onDone() }
            }
        }
        Hint("密码和密钥只存在手机安全区，不会显示；会跟着备份加密保存，换手机恢复后不用重填。")
    }
}

@Composable
private fun Label(text: String) = Text(text, style = RsType.caption, color = Rs.colors.textSecondary)

@Composable
private fun Hint(text: String) = Text(text, style = RsType.caption, color = Rs.colors.textTertiary)

@Composable
private fun CloudField(value: String, hint: String, type: KeyboardType, secret: Boolean = false, onChange: (String) -> Unit) {
    val c = Rs.colors
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = RsType.body.copy(color = c.text), cursorBrush = SolidColor(c.accent),
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
