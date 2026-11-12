package app.resound.net

/** URL 语法校验由平台边界完成；版本与代理路径选择是共用业务规则。 */
object EndpointRules {
    fun bases(value: String, decodedPath: String): List<String> {
        val suffix = listOf("/audio/transcriptions", "/chat/completions", "/models").firstOrNull { value.endsWith(it) }
        val base = if(suffix != null) value.removeSuffix(suffix) else value
        val path = if(suffix != null) decodedPath.removeSuffix(suffix) else decodedPath
        return if(Regex("/v[0-9]+(?:beta[0-9]*)?$").containsMatchIn(path)) listOf(base)
        else if(suffix != null) listOf(base,"$base/v1") else listOf("$base/v1",base)
    }
    fun isLocalHost(host: String): Boolean {
        if(host=="localhost" || host.endsWith(".local") || host.endsWith(".lan") || host=="::1") return true
        if(':' in host) return host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe8") || host.startsWith("fe9") || host.startsWith("fea") || host.startsWith("feb")
        val p=host.split('.').mapNotNull{it.toIntOrNull()}.takeIf{it.size==4} ?: return false
        return p[0]==127 || p[0]==10 || (p[0]==172 && p[1] in 16..31) || (p[0]==192 && p[1]==168) || (p[0]==169 && p[1]==254) || (p[0]==100 && p[1] in 64..127)
    }
}
