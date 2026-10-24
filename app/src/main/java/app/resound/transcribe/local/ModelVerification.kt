package app.resound.transcribe.local

import org.json.JSONObject
import java.io.File

/** 标记仅用于本机下载模型；元数据变化立即失效，旧 SHA 标记首次加载时升级一次。 */
object ModelVerification {
    fun matches(dir: File, spec: ModelSpec): Boolean = runCatching {
        val mark = JSONObject(File(dir,"verified").readText())
        val model = File(dir,spec.modelName)
        mark.getString("sha256") == spec.modelHash && model.length() == spec.modelBytes &&
            mark.getLong("bytes") == model.length() && mark.getLong("modified") == model.lastModified() &&
            (!spec.needsTokens || File(dir,"tokens.txt").let {
                it.length() > 0 && mark.getLong("tokensBytes") == it.length() && mark.getLong("tokensModified") == it.lastModified()
            })
    }.getOrDefault(false)
    fun legacy(dir: File, spec: ModelSpec): Boolean = runCatching {
        File(dir,"verified").readText().trim() == spec.modelHash && File(dir,spec.modelName).length() == spec.modelBytes &&
            (!spec.needsTokens || File(dir,"tokens.txt").length() > 0)
    }.getOrDefault(false)
    /** 调用者已完成 SHA-256 校验后才写入；不可用此方法跳过校验。 */
    fun record(dir: File, spec: ModelSpec) {
        val model=File(dir,spec.modelName);val tokens=File(dir,"tokens.txt")
        val data=JSONObject().put("sha256",spec.modelHash).put("bytes",model.length()).put("modified",model.lastModified())
            .put("tokensBytes",tokens.length()).put("tokensModified",tokens.lastModified())
        val temp=File(dir,"verified.tmp");temp.writeText(data.toString());check(temp.renameTo(File(dir,"verified")))
    }
    fun ensure(dir: File, spec: ModelSpec): Boolean {
        if (matches(dir,spec)) return true
        if (!legacy(dir,spec)) return false
        if (LocalModels.hash(File(dir,spec.modelName)) != spec.modelHash) return false
        record(dir,spec)
        return true
    }
}
