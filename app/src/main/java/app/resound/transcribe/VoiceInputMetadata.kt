package app.resound.transcribe

import app.resound.data.TranscriptLineEntity
import org.json.JSONObject

internal fun TranscriptLineEntity.voiceInputMeta(): VoiceInputMeta? = VoiceInputMetadata.read(voiceInputJson)
internal object VoiceInputMetadata {
    fun read(raw: String?): VoiceInputMeta? = raw?.let { runCatching {
        val j = JSONObject(it)
        fun optional(key: String) = if (j.isNull(key)) null else j.optString(key).takeIf(String::isNotBlank)
        VoiceInputMeta(j.getString("app"), j.getString("package_name"), optional("title"), optional("before"), optional("after"))
    }.getOrNull() }
    fun json(meta: VoiceInputMeta, context: Boolean = true): JSONObject = JSONObject()
        .put("app", meta.app).put("package_name", meta.packageName).put("title", meta.title ?: JSONObject.NULL)
        .apply { if (context) { put("before", meta.before ?: JSONObject.NULL); put("after", meta.after ?: JSONObject.NULL) } }
    fun label(meta: VoiceInputMeta) = listOfNotNull("⌨ 语音输入", meta.app, meta.title).joinToString(" · ")
    fun markdown(raw: String?, context: Boolean = true): String = read(raw)?.let { meta ->
        "\n${label(meta)}\n" + if (context) listOfNotNull(meta.before?.let { "之前：\n$it" }, meta.after?.let { "之后：\n$it" })
            .joinToString("\n").lineSequence().filter(String::isNotBlank).joinToString("\n") { "> $it" } else ""
    }.orEmpty()
}
