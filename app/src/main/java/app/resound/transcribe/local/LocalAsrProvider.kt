package app.resound.transcribe.local

import android.content.*
import android.os.*
import app.resound.transcribe.*
import kotlinx.coroutines.*

class LocalAsrProvider(private val context:Context,private val settings:AndroidTranscribeSettings):AsrProvider {
    override suspend fun recognize(audio:AsrAudio,key:String,beforeAudio:suspend()->Unit):AsrResult {
        val requested=settings.backend.value
        // 检查结果按“App 版本 + 模型文件”记住：通过过就直接用，没通过过就直接走 CPU；重新选 NPU 或升级后才再查
        val key=LocalModels.npuCheckKey(context)
        val checked=settings.npuCheck(key)
        val useNpu=requested==LocalBackend.Npu && LocalModels.npuSupported() && checked!=false
        var fallback=when {
            requested!=LocalBackend.Npu || useNpu -> ""
            checked==false -> "NPU 识别暂时不可用，已自动改用 CPU（重新选一次 NPU 识别可再试）"
            else -> "您的手机暂不支持 NPU 识别，已使用 CPU 识别"
        }
        if(useNpu){
            if(!LocalModels.ready(context,LocalModels.npu))throw AsrException(AsrError.ModelMissing)
            settings.setLocalStatus(if(checked==true) "正在使用：NPU 识别" else "正在试用 NPU 识别…")
            try {return call(audio,LocalBackend.Npu).also{if(checked!=true)settings.recordNpuCheck(key,true);settings.setLocalStatus("正在使用：NPU 识别")}}
            catch(e:CancellationException){throw e}
            catch(e:Exception){
                if(e is AsrException && e.type==AsrError.Speakers)throw e
                // 通过过的偶发失败只回退这一次，不记成“不可用”
                if(checked!=true)settings.recordNpuCheck(key,false)
                fallback="NPU 识别暂时不可用，已自动改用 CPU"
            }
        }
        if(!LocalModels.ready(context,LocalModels.cpu)){
            settings.setLocalStatus(if(fallback.isNotBlank()) "NPU 识别暂时不可用，请先下载 CPU 模型" else "请先下载 CPU 模型")
            throw AsrException(AsrError.ModelMissing)
        }
        settings.setLocalStatus(if(fallback.isNotBlank()) fallback else "正在使用：CPU 识别")
        return call(audio,LocalBackend.Cpu).also{settings.setLocalStatus(if(fallback.isNotBlank())fallback else "正在使用：CPU 识别")}
    }
    private suspend fun call(audio:AsrAudio,backend:LocalBackend):AsrResult =
        LocalAsrConnection.get(context).recognize(audio,backend){settings.setLocalStatus(it);app.resound.PipelineProgress.detail(it)}
}
