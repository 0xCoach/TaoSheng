package app.resound.ui.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.resound.digest.LlmProviders
import app.resound.digest.ProviderTemplate
import app.resound.digest.SavedProvider
import app.resound.ui.theme.Rs
import app.resound.ui.theme.RsType
import app.resound.ui.verify.ActionButton
import app.resound.ui.verify.ActionKind
import app.resound.ui.verify.RsCard
import app.resound.ui.verify.RsSheet
import app.resound.ui.verify.SheetInput
import app.resound.ui.verify.springClick
import kotlinx.coroutines.launch

private const val WORKSPACE = "{WorkspaceId}"

/**
 * 整理用的大模型：已保存的服务商一行一个，点一下切换为当前；“›”编辑或删除。
 * “添加服务商”列出常见几家，点一个自动填好地址、模型和余额查询，只需粘贴密钥。
 */
@Composable
fun ProvidersCard(providers: LlmProviders) {
    val c = Rs.colors
    val saved by providers.saved.collectAsState()
    val active by providers.activeId.collectAsState()
    var editing by remember { mutableStateOf<SavedProvider?>(null) }
    var adding by remember { mutableStateOf(false) }
    RsCard(Modifier.animateContentSize(), padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
        Text("整理用的大模型", style = RsType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        Text("点一下切换；整理、问问、回顾都用当前这个，技能可以另选。", style = RsType.caption, color = c.textTertiary)
        Spacer(Modifier.height(10.dp))
        saved.forEachIndexed { i, p ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator))
            val on = p.id == active
            Row(Modifier.fillMaxWidth().springClick { if (!on) providers.activate(p.id) }.padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(20.dp).clip(CircleShape).then(if (on) Modifier.background(c.accent) else Modifier.border(1.5.dp, c.separator, CircleShape)),
                    contentAlignment = Alignment.Center) {
                    if (on) Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = RsType.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(p.model + if (!p.hasKey) " · 未填密钥" else "", style = RsType.caption,
                        color = if (!p.hasKey) c.warning else c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("编辑", style = RsType.subhead, color = c.accent,
                    modifier = Modifier.clip(CircleShape).springClick { editing = p }.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.accentSoft).springClick { adding = true }.padding(vertical = 12.dp),
            contentAlignment = Alignment.Center) {
            Text("＋ 添加服务商", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.accent)
        }
        Text("密钥分开保存在手机安全区里，不会显示。", style = RsType.caption, color = c.textTertiary, modifier = Modifier.padding(top = 8.dp))
    }
    if (adding) AddProviderSheet(providers, onPicked = { t -> adding = false; editing = draft(t) }) { adding = false }
    editing?.let { p -> ProviderSheet(providers, p, isNew = saved.none { it.id == p.id }, isActive = p.id == active) { editing = null } }
}

/** 模板转成一条待保存的服务商（id 为空串表示新建） */
private fun draft(t: ProviderTemplate?): SavedProvider = if (t == null) SavedProvider("", null, "自定义", "", "", false)
    else SavedProvider("", t.id, t.name, t.baseUrl, t.models.firstOrNull().orEmpty(), false, t.balance?.url.orEmpty(), t.balance?.path.orEmpty())

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddProviderSheet(providers: LlmProviders, onPicked: (ProviderTemplate?) -> Unit, onDismiss: () -> Unit) {
    val c = Rs.colors
    RsSheet("添加服务商", "选一家，地址、模型、余额查询都已填好，粘贴密钥就能用。", onDismiss = onDismiss) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            providers.templates.forEach { t ->
                Text(t.name, style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.text,
                    modifier = Modifier.clip(CircleShape).background(c.background).springClick { onPicked(t) }.padding(horizontal = 14.dp, vertical = 9.dp))
            }
            Text("自己填写", style = RsType.subhead.copy(fontWeight = FontWeight.Medium), color = c.accent,
                modifier = Modifier.clip(CircleShape).background(c.accentSoft).springClick { onPicked(null) }.padding(horizontal = 14.dp, vertical = 9.dp))
        }
        Text("“自己填写”适合其他兼容 OpenAI 接口的服务，或自己部署的模型。", style = RsType.caption, color = c.textTertiary)
    }
}

/** 新建或编辑一家：模型胶囊（可自填）、密钥、测试、保存；编辑时可删（当前在用的先切换再删） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderSheet(providers: LlmProviders, p: SavedProvider, isNew: Boolean, isActive: Boolean, onDismiss: () -> Unit) {
    val c = Rs.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val template = providers.templates.firstOrNull { it.id == p.templateId }
    val custom = template == null
    var name by remember { mutableStateOf(p.name) }
    var baseUrl by remember { mutableStateOf(p.baseUrl) }
    var workspace by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(p.model) }
    var key by remember { mutableStateOf("") }
    var otherModel by remember { mutableStateOf(template != null && p.model.isNotBlank() && p.model !in template.models) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<String>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var fetching by remember { mutableStateOf(false) }
    var fetched by remember { mutableStateOf<List<String>?>(null) }
    val needsWorkspace = baseUrl.contains(WORKSPACE)
    val url = if (needsWorkspace) baseUrl.replace(WORKSPACE, workspace.trim()) else baseUrl.trim()
    val ready = url.isNotBlank() && model.isNotBlank() && (!needsWorkspace || workspace.isNotBlank()) && (p.hasKey || key.isNotBlank() || custom)
    val keyArg = key.trim().ifBlank { null }
    RsSheet(if (isNew) "添加 ${p.name}" else p.name, template?.note, onDismiss = { if (!busy) onDismiss() }) {
        if (custom) {
            SheetInput(name, "名称，如 我的本地模型") { name = it }
            SheetInput(baseUrl, "接口地址，如 https://…/v1") { baseUrl = it.trim(); result = null }
        }
        if (needsWorkspace) SheetInput(workspace, "工作空间 ID（在控制台的工作空间页面）") { workspace = it.trim(); result = null }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("模型", style = RsType.caption.copy(fontWeight = FontWeight.Medium), color = c.textTertiary, modifier = Modifier.weight(1f))
            // 拉取这家服务商的全部模型（要地址和密钥），从列表里点选
            val canFetch = url.isNotBlank() && (!needsWorkspace || workspace.isNotBlank()) && (p.hasKey || key.isNotBlank() || custom) && !fetching
            Text(if (fetching) "拉取中…" else "拉取模型", style = RsType.caption.copy(fontWeight = FontWeight.Medium),
                color = if (canFetch) c.accent else c.textTertiary,
                modifier = Modifier.clip(CircleShape).background(if (canFetch) c.accentSoft else c.background).springClick {
                    if (!canFetch) return@springClick
                    fetching = true
                    scope.launch { fetched = providers.models(p.id.ifBlank { null }, url, keyArg); fetching = false }
                }.padding(horizontal = 10.dp, vertical = 4.dp))
        }
        if (template != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            template.models.forEachIndexed { i, m ->
                val on = !otherModel && m == model
                Text(m + if (i == 0) " · 推荐" else "", style = RsType.subhead, color = if (on) Color.White else c.textSecondary,
                    modifier = Modifier.clip(CircleShape).background(if (on) c.accent else c.background).springClick { model = m; otherModel = false; result = null }
                        .padding(horizontal = 12.dp, vertical = 8.dp))
            }
            Text("其他", style = RsType.subhead, color = if (otherModel) Color.White else c.textSecondary,
                modifier = Modifier.clip(CircleShape).background(if (otherModel) c.accent else c.background).springClick { otherModel = true; model = ""; result = null }
                    .padding(horizontal = 12.dp, vertical = 8.dp))
        }
        if (custom || otherModel) SheetInput(model, "模型名，如 deepseek-flash") { model = it.trim(); result = null }
        fetched?.let { list ->
            if (list.isEmpty()) Text("没拉到模型列表，可以手动填写", style = RsType.caption, color = c.textTertiary)
            else Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(14.dp)).background(c.background)
                .verticalScroll(rememberScrollState())) {
                list.forEach { m ->
                    val on = m == model
                    Text(m, style = RsType.subhead, color = if (on) c.accent else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().springClick {
                            model = m; result = null
                            otherModel = template != null && m !in template.models
                        }.padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
        }
        SheetInput(key, if (p.hasKey) "已填写密钥（不改就留空）" else "粘贴 API Key") { key = it.trim(); result = null }
        template?.keyUrl?.let { link ->
            Text("去申请密钥 ›", style = RsType.subhead, color = c.accent, modifier = Modifier.springClick {
                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link))) }
            })
        }
        result?.let { r -> Text(r.fold({ "可以用：$it" }, { it.message ?: "没连上" }), style = RsType.subhead, color = if (r.isSuccess) c.good else c.critical) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(if (busy && result == null) "测试中…" else "测试", ActionKind.Secondary, Modifier.weight(1f), enabled = !busy && ready) {
                busy = true; result = null
                scope.launch { result = providers.test(p.id.ifBlank { null }, url, model, keyArg); busy = false }
            }
            ActionButton(if (isNew) "保存" else "保存修改", ActionKind.Primary, Modifier.weight(1f), enabled = !busy && ready) {
                busy = true
                scope.launch {
                    runCatching { providers.save(p.id.ifBlank { null }, p.templateId, name.trim().ifBlank { p.name }, url, model, keyArg, p.balanceUrl, p.balancePath) }
                        .onSuccess { onDismiss() }.onFailure { result = Result.failure(it) }
                    busy = false
                }
            }
        }
        if (!isNew) {
            if (isActive) Text("这是当前在用的，先切换到别的再删除。", style = RsType.caption, color = c.textTertiary)
            else ActionButton(if (confirmDelete) "确定删除？再点一次" else "删除这个服务商", ActionKind.Danger) {
                if (!confirmDelete) { confirmDelete = true; return@ActionButton }
                providers.delete(p.id); onDismiss()
            }
        }
    }
}
