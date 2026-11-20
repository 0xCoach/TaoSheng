package app.resound.ui.settings

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import app.resound.transcribe.CustomCapability
import app.resound.transcribe.CustomEndpointMode
import app.resound.transcribe.CustomKeyState
import app.resound.transcribe.CustomSaveResult
import app.resound.transcribe.EndpointKind
import app.resound.transcribe.OpenAiEndpoint
import app.resound.transcribe.RemoteModels
import app.resound.ui.theme.*
import app.resound.ui.verify.Pill
import app.resound.ui.verify.springClick
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 自定义转写才有：地址写法（自动匹配 / 完整地址）、是否附带热词、密钥三态与测试结论（卡 212）。
 * [qwen] 为千问接口：只按完整地址请求，不拉取模型、不切写法，热词开关常显。 */
internal class EndpointModeUi(val initial: CustomEndpointMode, val prompt: kotlinx.coroutines.flow.StateFlow<Boolean>,
    val setPrompt: (Boolean) -> Unit, val save: (String, String, String, CustomEndpointMode) -> CustomSaveResult?,
    val keyState: CustomKeyState? = null, val capability: CustomCapability? = null, val qwen: Boolean = false)

/** 测试通过后告诉用户这个模型能做到哪一步 */
internal fun capabilityText(c: CustomCapability) = when (c) {
    CustomCapability.Speakers -> "能分说话人"
    CustomCapability.TimedText -> "只出文字，带逐句时间"
    CustomCapability.TextOnly -> "只出文字"
}

/** 沿用原密钥输入框，不引入另一套 Material 文本框外观。 */
@Composable
internal fun SettingsInput(value:String,onChange:(String)->Unit,hint:String,modifier:Modifier=Modifier,secret:Boolean=false) {
    val c=Rs.colors
    BasicTextField(value=value,onValueChange=onChange,singleLine=true,
        textStyle=RsType.body.copy(color=c.text),cursorBrush=SolidColor(c.accent),
        visualTransformation=if(secret)PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions=KeyboardOptions(keyboardType=if(secret)KeyboardType.Password else KeyboardType.Uri,imeAction=ImeAction.Done),modifier=modifier,
        decorationBox={inner->Box(Modifier.clip(RoundedCornerShape(14.dp)).background(c.fill).padding(horizontal=14.dp,vertical=12.dp)) {
            if(value.isEmpty())Text(hint,style=RsType.body,color=c.textTertiary)
            inner()
        }})
}

@Composable
internal fun CustomModelForm(initialEndpoint:String,initialModel:String,hasKey:Boolean,description:String,
    fetch:suspend(String,String)->RemoteModels,save:(String,String,String)->Unit,modes:EndpointModeUi?=null) {
    val c=Rs.colors;val scope=rememberCoroutineScope()
    var endpoint by remember {mutableStateOf(initialEndpoint)}
    var key by remember {mutableStateOf("")}
    var model by remember {mutableStateOf(initialModel)}
    var models by remember {mutableStateOf<List<String>>(emptyList())}
    var menu by remember {mutableStateOf(false)}
    var fetching by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf("")}
    var error by remember {mutableStateOf(false)}
    var mode by remember {mutableStateOf(modes?.initial ?: CustomEndpointMode.Auto)}
    val full=mode==CustomEndpointMode.Full
    val qwen=modes?.qwen==true
    val keyState=modes?.keyState
    Column(Modifier.animateContentSize()) {
        Text(description,style=RsType.caption,color=c.textSecondary)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Text("服务地址",style=RsType.body,color=c.text,modifier=Modifier.weight(1f))
            if(modes!=null && !qwen) app.resound.ui.skills.Segments(listOf("自动匹配","完整地址"),if(full)1 else 0) {
                mode=if(it==1)CustomEndpointMode.Full else CustomEndpointMode.Auto;message="";models=emptyList()
            }
        }
        Spacer(Modifier.height(8.dp))
        SettingsInput(endpoint,{endpoint=it.trim();models=emptyList()},if(full)"服务文档里的完整请求地址" else "https://… 或 http://192.168.…",Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        // 按地址类型给一行提示：局域网 http 放心用；公网 http 提醒未加密，但不拦
        val kind=remember(endpoint,mode){when{
            endpoint.isBlank()->null
            full && runCatching{app.resound.transcribe.fullEndpoint(endpoint)}.isFailure->EndpointKind.Invalid
            else->OpenAiEndpoint.kind(endpoint)
        }}
        val tip=when{qwen->"已预填北京地域地址，一般不用改。";full->"按填写的地址请求，可带参数。";else->"可填根地址或带 /v1 的地址。"}
        when(kind) {
            EndpointKind.LocalHttp->Text("局域网连接 · $tip",style=RsType.caption,color=c.textSecondary)
            EndpointKind.PublicHttp->Text("未加密的公网连接，密钥和内容可能被截获，建议只在内网使用。",style=RsType.caption,color=c.warning)
            EndpointKind.Invalid->Text(if(full)"请填写 http:// 或 https:// 开头的完整地址，不含账号信息和 #。" else "请填写 http:// 或 https:// 开头的地址。",style=RsType.caption,color=c.critical)
            else->Text(tip,style=RsType.caption,color=c.textTertiary)
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment=androidx.compose.ui.Alignment.Bottom) {
            Text("API Key",style=RsType.body,color=c.text)
            Spacer(Modifier.width(6.dp))
            if(!qwen) Text("可选",style=RsType.caption,color=c.textTertiary)
        }
        Spacer(Modifier.height(8.dp))
        // #56：密钥框只看“真的存了密钥”，不再把“地址和模型已保存”当成密钥已保存
        val keySaved=if(keyState!=null) keyState!=CustomKeyState.None else hasKey
        SettingsInput(key,{key=it.trim()},when{keySaved->"已保存，留空沿用";qwen->"填写百炼控制台的 API Key";else->"本地模型不需要可留空"},Modifier.fillMaxWidth(),secret=true)
        if(keyState!=null) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                val (dot,line)=when(keyState){
                    CustomKeyState.None->null to (if(qwen)"还没保存密钥" else "还没保存密钥，本地服务可不填")
                    CustomKeyState.Saved->c.warning to "已保存，未验证 · 点下面的「测试」确认"
                    CustomKeyState.Verified->c.good to ("已验证"+(modes?.capability?.let{" · "+capabilityText(it)}?:""))
                }
                if(dot!=null){app.resound.ui.verify.Dot(dot,6.dp);Spacer(Modifier.width(6.dp))}
                Text(line,style=RsType.caption,color=when(keyState){CustomKeyState.Verified->c.good;CustomKeyState.Saved->c.textSecondary;else->c.textTertiary})
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("模型",style=RsType.body,color=c.text)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            SettingsInput(model,{model=it.trim()},if(full||qwen)"填写模型名" else "拉取后选择，或手动填写",Modifier.weight(1f))
            if(models.isNotEmpty()) Box {
                Text("⌄",style=RsType.headline,color=c.accent,modifier=Modifier.springClick{menu=true}.padding(12.dp))
                DropdownMenu(expanded=menu,onDismissRequest={menu=false},containerColor=c.card,modifier=Modifier.heightIn(max=300.dp)) {
                    models.forEach{id->DropdownMenuItem(text={Text(id,style=RsType.body,color=c.text)},onClick={model=id;menu=false})}
                }
            }
        }
        if(modes!=null && (full || qwen)) {
            val prompt by modes.prompt.collectAsStateWithLifecycle()
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("附带热词",style=RsType.body,color=c.text)
                    Text(if(qwen)"把热词表发给千问，人名和专有名词更准。" else "服务支持时打开，人名和专有名词更准。",style=RsType.caption,color=c.textTertiary)
                }
                Spacer(Modifier.width(12.dp))
                app.resound.ui.skills.Toggle(prompt){modes.setPrompt(it)}
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            if(!full && !qwen) Pill(if(fetching)"拉取中…" else "拉取模型",filled=false) {
                if(!fetching){fetching=true;error=false;scope.launch {
                    try{val result=fetch(endpoint,key);endpoint=result.base;models=result.ids;menu=true;message="找到 ${models.size} 个模型，请选择后保存"}
                    catch(e:CancellationException){throw e}
                    catch(e:Exception){error=true;message=e.message ?: "拉取失败，可手动填写模型"}
                    finally{fetching=false}
                }}
            }
            Pill("保存",filled=kind!=null && kind!=EndpointKind.Invalid && model.isNotBlank()) {
                if(!fetching){error=false
                    try{
                        val withKey=key.isNotBlank() || hasKey
                        val saved=if(modes!=null)modes.save(endpoint,model,key,mode) else {save(endpoint,model,key);null}
                        key=""
                        message=when{
                            saved==null->if(withKey)"地址、密钥和模型已保存" else "地址和模型已保存"
                            saved.keySaved->"已保存，点下面的「测试」确认能用"
                            saved.keyState==CustomKeyState.None->"地址和模型已保存，还没保存密钥"
                            else->"地址和模型已保存，沿用原密钥"
                        }
                    }
                    catch(e:Exception){error=true;message=e.message ?: "保存失败"}
                }
            }
        }
        if(message.isNotBlank()){Spacer(Modifier.height(8.dp));Text(message,style=RsType.caption,color=if(error)c.critical else c.textSecondary)}
        Spacer(Modifier.height(6.dp))
        Text("密钥仅保存在手机安全区，不回显。模型列表不代表服务已通过测试。",style=RsType.caption,color=c.textTertiary)
    }
}
