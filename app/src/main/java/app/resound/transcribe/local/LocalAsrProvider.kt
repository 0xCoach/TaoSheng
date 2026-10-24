package app.resound.transcribe.local

import android.content.*
import android.os.*
import app.resound.transcribe.*
import kotlinx.coroutines.*

class LocalAsrProvider(private val context:Context,private val settings:AndroidTranscribeSettings):AsrProvider {
    override suspend fun recognize(audio:AsrAudio,key:String,beforeAudio:suspend()->Unit):AsrResult {
        val requested=settings.backend.value
        val useNpu=requested==LocalBackend.Npu && LocalModels.npuSupported() && !settings.npuFailed
        var fallback=if(requested==LocalBackend.Npu && !useNpu) "NPU 未适配或检查失败，已回退 CPU" else ""
        if(useNpu){
            if(!LocalModels.ready(context,LocalModels.npu))throw AsrException(AsrError.ModelMissing)
            settings.setLocalStatus("正在检查并使用 NPU…")
            try {return call(audio,LocalBackend.Npu).also{settings.setLocalStatus("实际使用：NPU · 检查通过")}}
            catch(e:CancellationException){throw e}
            catch(e:Exception){if(e is AsrException && e.type==AsrError.Speakers)throw e;settings.npuFailed=true;fallback="NPU 初始化或识别失败，已回退 CPU"}
        }
        if(!LocalModels.ready(context,LocalModels.cpu)){
            settings.setLocalStatus(if(fallback.isNotBlank()) "NPU 不可用，尚未运行 CPU；请先下载 CPU 模型" else "请先下载 CPU 模型")
            throw AsrException(AsrError.ModelMissing)
        }
        settings.setLocalStatus(if(fallback.isNotBlank()) fallback else "实际使用：CPU")
        return call(audio,LocalBackend.Cpu).also{settings.setLocalStatus(if(fallback.isNotBlank())fallback else "实际使用：CPU")}
    }
    private suspend fun call(audio:AsrAudio,backend:LocalBackend):AsrResult =
        LocalAsrConnection.get(context).recognize(audio,backend,settings::setLocalStatus)
}
