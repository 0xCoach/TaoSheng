package app.resound.transcribe

/** 仅用于判断配置变化，返回值不包含密钥原文。 */
internal fun keyFingerprint(key: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
