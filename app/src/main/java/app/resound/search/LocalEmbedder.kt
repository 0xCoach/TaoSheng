package app.resound.search

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import app.resound.transcribe.local.*
import java.io.File
import java.nio.LongBuffer

object SearchModels {
    const val revision = "75c43b069aac4d136ba6bc1122f995fedcfd2781"
    const val modelId = "bge-small-zh-v1.5-int8-$revision"
    private const val base = "https://huggingface.co/Xenova/bge-small-zh-v1.5/resolve/$revision"
    private fun spec(id: String, path: String, size: Long, sha: String) =
        ModelSpec(id, "$base/$path", size, sha, path.substringAfterLast('/'), size, sha, false)
    val weights = spec("weights", "onnx/model_quantized.onnx", 24010842, "15b717c382bcb518ba457b93ea6850ede7f4f1cd8937454aa06972366cd19bcc")
    val vocab = spec("vocab", "vocab.txt", 109540, "45bbac6b341c319adc98a532532882e91a9cefc0329aa57bac9ae761c27b291c")
    fun directory(context: Context, spec: ModelSpec) = File(context.noBackupFilesDir, "search-models/$modelId/${spec.id}")
    fun file(context: Context, spec: ModelSpec) = File(directory(context, spec), spec.modelName)
    fun ready(context: Context) = listOf(weights, vocab).all { ModelVerification.matches(directory(context, it), it) }
    suspend fun download(context: Context) {
        for (spec in listOf(weights, vocab)) {
            val dir = directory(context, spec)
            if (ModelVerification.matches(dir, spec)) continue
            check(dir.mkdirs() || dir.isDirectory)
            val part = File(dir, "download.part")
            downloadModelFile(spec, part)
            if (part.length() != spec.modelBytes || LocalModels.hash(part) != spec.modelHash) {
                part.delete(); error("模型校验失败")
            }
            check(part.renameTo(file(context, spec)))
            ModelVerification.record(dir, spec)
        }
    }
}

/** 与模型附带的 BertTokenizer 一致：保留大小写/重音，中文与标点独立，WordPiece 最长匹配。 */
class BertTokens(vocabulary: List<String>) {
    private val vocab = vocabulary.withIndex().associate { it.value to it.index.toLong() }
    fun encode(text: String): LongArray {
        val basic = mutableListOf<String>(); val word = StringBuilder()
        fun flush() { if (word.isNotEmpty()) { basic += word.toString(); word.clear() } }
        text.codePoints().forEach { cp ->
            val type = Character.getType(cp)
            val han = cp in 0x4e00..0x9fff || cp in 0x3400..0x4dbf || cp in 0x20000..0x2a6df ||
                cp in 0x2a700..0x2b73f || cp in 0x2b740..0x2b81f || cp in 0x2b820..0x2ceaf || cp in 0xf900..0xfaff || cp in 0x2f800..0x2fa1f
            val punctuation = cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126 || type in setOf(20,21,22,23,24,29,30)
            when {
                cp == 0 || cp == 0xfffd -> Unit
                Character.isWhitespace(cp) || type == Character.SPACE_SEPARATOR.toInt() -> flush()
                type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() -> Unit
                han || punctuation -> { flush(); basic += String(Character.toChars(cp)) }
                else -> word.appendCodePoint(cp)
            }
        }
        flush()
        val ids = mutableListOf(vocab.getValue("[CLS]"))
        for (token in basic) {
            val pieces = mutableListOf<Long>(); var start = 0
            if (token.codePointCount(0, token.length) > 100) pieces += vocab.getValue("[UNK]")
            else while (start < token.length) {
                var end = token.length; var found: Long? = null
                while (start < end) {
                    found = vocab[(if (start == 0) "" else "##") + token.substring(start, end)]
                    if (found != null) break
                    end = token.offsetByCodePoints(end, -1)
                }
                if (found == null) { pieces.clear(); pieces += vocab.getValue("[UNK]"); break }
                pieces += found; start = end
            }
            ids += pieces.take(511 - ids.size)
            if (ids.size >= 511) break
        }
        ids += vocab.getValue("[SEP]")
        return ids.toLongArray()
    }
}

/** 会话按使用方复用，close 释放本轮 native 内存；查询没模型时无网络、无报错。 */
class LocalEmbedder(private val context: Context) : Embedder, AutoCloseable {
    override val model = SearchModels.modelId
    override fun ready() = SearchModels.ready(context)
    private var session: OrtSession? = null
    private var tokenizer: BertTokens? = null
    @Synchronized override fun embed(text: String, query: Boolean): FloatArray {
        check(ready())
        val env = OrtEnvironment.getEnvironment()
        val active = session ?: OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            env.createSession(SearchModels.file(context, SearchModels.weights).absolutePath, options)
        }.also { session = it }
        val tokens = tokenizer ?: BertTokens(SearchModels.file(context, SearchModels.vocab).readLines()).also { tokenizer = it }
        val ids = tokens.encode((if (query) "为这个句子生成表示以用于检索相关文章：" else "") + text)
        val shape = longArrayOf(1, ids.size.toLong())
        val inputs = mutableMapOf<String, OnnxTensor>()
        try {
            inputs["input_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape)
            inputs["attention_mask"] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { 1 }), shape)
            if ("token_type_ids" in active.inputNames)
                inputs["token_type_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size)), shape)
            active.run(inputs).use { output ->
                val tensor = output.get("last_hidden_state").orElseThrow() as OnnxTensor
                // BGE 使用第一 token (CLS)，不是 mean pooling。
                val cls = FloatArray(512)
                tensor.floatBuffer.get(cls)
                return Vectors.normalize(cls)
            }
        } finally { inputs.values.forEach { it.close() } }
    }
    @Synchronized override fun close() { session?.close(); session = null; tokenizer = null }
}
