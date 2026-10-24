package app.resound.ui.settings

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
import app.resound.transcribe.RemoteModels
import app.resound.ui.theme.*
import app.resound.ui.verify.Pill
import app.resound.ui.verify.springClick
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 沿用原密钥输入框，不引入另一套 Material 文本框外观。 */
@Composable
internal fun SettingsInput(value:String,onChange:(String)->Unit,hint:String,modifier:Modifier=Modifier,secret:Boolean=false) {
    val c=Rs.colors
    BasicTextField(value=value,onValueChange=onChange,singleLine=true,
        textStyle=RsType.body.copy(color=c.text),cursorBrush=SolidColor(c.accent),
        visualTransformation=if(secret)PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions=KeyboardOptions(keyboardType=if(secret)KeyboardType.Password else KeyboardType.Uri,imeAction=ImeAction.Done),modifier=modifier,
        decorationBox={inner->Box(Modifier.clip(RoundedCornerShape(14.dp)).background(c.background).padding(horizontal=14.dp,vertical=12.dp)) {
            if(value.isEmpty())Text(hint,style=RsType.body,color=c.textTertiary)
            inner()
        }})
}

@Composable
internal fun CustomModelForm(initialEndpoint:String,initialModel:String,hasKey:Boolean,description:String,
    fetch:suspend(String,String)->RemoteModels,save:(String,String,String)->Unit) {
    val c=Rs.colors;val scope=rememberCoroutineScope()
    var endpoint by remember {mutableStateOf(initialEndpoint)}
    var key by remember {mutableStateOf("")}
    var model by remember {mutableStateOf(initialModel)}
    var models by remember {mutableStateOf<List<String>>(emptyList())}
    var menu by remember {mutableStateOf(false)}
    var fetching by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf("")}
    var error by remember {mutableStateOf(false)}
    Column {
        Text(description,style=RsType.caption,color=c.textSecondary)
        Spacer(Modifier.height(14.dp))
        Text("服务地址",style=RsType.body,color=c.text)
        Spacer(Modifier.height(8.dp))
        SettingsInput(endpoint,{endpoint=it.trim();models=emptyList()},"https://…",Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Text("可填根地址或带 /v1 的地址，自动匹配路径。",style=RsType.caption,color=c.textTertiary)
        Spacer(Modifier.height(16.dp))
        Text("API Key",style=RsType.body,color=c.text)
        Spacer(Modifier.height(8.dp))
        SettingsInput(key,{key=it.trim()},if(hasKey)"已保存密钥，留空沿用" else "粘贴 API Key",Modifier.fillMaxWidth(),secret=true)
        Spacer(Modifier.height(16.dp))
        Text("模型",style=RsType.body,color=c.text)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            SettingsInput(model,{model=it.trim()},"拉取后选择，或手动填写",Modifier.weight(1f))
            if(models.isNotEmpty()) Box {
                Text("⌄",style=RsType.headline,color=c.accent,modifier=Modifier.springClick{menu=true}.padding(12.dp))
                DropdownMenu(expanded=menu,onDismissRequest={menu=false},containerColor=c.card,modifier=Modifier.heightIn(max=300.dp)) {
                    models.forEach{id->DropdownMenuItem(text={Text(id,style=RsType.body,color=c.text)},onClick={model=id;menu=false})}
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Pill(if(fetching)"拉取中…" else "拉取模型",filled=false) {
                if(!fetching){fetching=true;error=false;scope.launch {
                    try{val result=fetch(endpoint,key);endpoint=result.base;models=result.ids;menu=true;message="找到 ${models.size} 个模型，请选择后保存"}
                    catch(e:CancellationException){throw e}
                    catch(e:Exception){error=true;message=e.message ?: "拉取失败，可手动填写模型"}
                    finally{fetching=false}
                }}
            }
            Pill("保存",filled=endpoint.isNotBlank() && model.isNotBlank() && (hasKey || key.isNotBlank())) {
                if(!fetching){error=false
                    try{save(endpoint,model,key);key="";message="地址、密钥和模型已保存"}
                    catch(e:Exception){error=true;message=e.message ?: "保存失败"}
                }
            }
        }
        if(message.isNotBlank()){Spacer(Modifier.height(8.dp));Text(message,style=RsType.caption,color=if(error)c.critical else c.textSecondary)}
        Spacer(Modifier.height(6.dp))
        Text("密钥仅保存在手机安全区，不回显。模型列表不代表服务已通过测试。",style=RsType.caption,color=c.textTertiary)
    }
}
