package app.resound.push

import app.resound.appDisplayName
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** TLS 接口仅供本地假服务器注入；生产始终校验证书和服务器名称。 */
internal open class SmtpSockets {
    open fun connect(host: String, port: Int): Socket = Socket().apply {
        soTimeout = 10_000
        try { connect(InetSocketAddress(host, port), 10_000) } catch (e: Exception) { close(); throw e }
    }
    open fun tls(socket: Socket, host: String, port: Int): Socket =
        ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, host, port, true) as SSLSocket).apply {
            soTimeout = 10_000
            sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            try { startHandshake() } catch (e: Exception) { close(); throw e }
        }
}
internal class SmtpSender(private val sockets: SmtpSockets = SmtpSockets()) {
    fun send(values: Map<String, String>, format: PushFormat, message: PushMessage) {
        val host = values.getValue("host")
        val port = PushFields.default(values, "port", "465").toInt()
        val socket = AtomicReference<Socket?>()
        val deadline = timer.schedule({ runCatching { socket.get()?.close() } }, 10, TimeUnit.SECONDS)
        try {
            socket.set(sockets.connect(host, port))
            if (port == 465) socket.set(sockets.tls(socket.get()!!, host, port))
            var reader = BufferedReader(InputStreamReader(socket.get()!!.getInputStream(), Charsets.US_ASCII))
            var writer = OutputStreamWriter(socket.get()!!.getOutputStream(), Charsets.UTF_8)
            var consumed = 0
            fun reply(expected: Int) {
                var first: String? = null
                while (true) {
                    val line = StringBuilder()
                    while (true) {
                        val c = reader.read()
                        if (c < 0) throw PushFailure("网络连接失败或超时，请稍后再试")
                        if (++consumed >= 65536) throw PushFailure("推送服务响应过大")
                        if (c == 10) break
                        if (c != 13) line.append(c.toChar())
                    }
                    val text = line.toString()
                    val code = text.take(3).toIntOrNull() ?: throw PushFailure("推送服务返回格式异常")
                    if (first == null) first = text.take(3)
                    if (text.take(3) != first || text.length < 4 || text[3] !in " -") throw PushFailure("推送服务返回格式异常")
                    if (code != expected) throw PushFailure(when (code) {
                        530, 534, 535, 538 -> "认证失败，请检查密钥和权限"
                        421, 450, 451, 452 -> "发送过于频繁，请稍后再试"
                        else -> "推送服务拒绝消息，请检查密钥、额度和通道配置"
                    })
                    if (text[3] == ' ') return
                }
            }
            fun command(command: String, expected: Int) { writer.write(command + "\r\n"); writer.flush(); reply(expected) }
            reply(220)
            command("EHLO resound.local", 250)
            if (port != 465) {
                command("STARTTLS", 220)
                socket.set(sockets.tls(socket.get()!!, host, port))
                reader = BufferedReader(InputStreamReader(socket.get()!!.getInputStream(), Charsets.US_ASCII))
                writer = OutputStreamWriter(socket.get()!!.getOutputStream(), Charsets.UTF_8)
                command("EHLO resound.local", 250)
            }
            command("AUTH LOGIN", 334)
            command(base64(values.getValue("account")), 334)
            command(base64(values.getValue("password")), 235)
            command("MAIL FROM:<${values.getValue("account")}>", 250)
            command("RCPT TO:<${values.getValue("to")}>", 250)
            command("DATA", 354)
            val mime = mime(values, format, message)
            writer.write(mime.lineSequence().joinToString("\r\n") { if (it.startsWith('.')) ".$it" else it })
            writer.write("\r\n.\r\n"); writer.flush(); reply(250)
            // DATA 的 250 即已接收；QUIT 失败不能把已发送邮件变为失败并重复投递。
            runCatching { writer.write("QUIT\r\n"); writer.flush() }
        } finally { deadline.cancel(false); runCatching { socket.get()?.close() } }
    }
    companion object {
        private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "push-smtp-timeout").apply { isDaemon = true } }
        private fun base64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        private fun encoded(text: String) = Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(text.toByteArray(Charsets.UTF_8))
        internal fun subject(text: String): String = text.codePoints().toArray().toList().chunked(12).joinToString("\r\n ") { chars ->
            "=?UTF-8?B?${base64(String(chars.toIntArray(), 0, chars.size))}?="
        }
        internal fun markdownHtml(text: String): String = text.lines().joinToString("\n") { line ->
            fun inline(s: String) = html(s).replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
            val hashes = line.takeWhile { it == '#' }.length
            when {
                hashes in 1..6 && line.getOrNull(hashes) == ' ' -> "<h$hashes>${inline(line.drop(hashes + 1))}</h$hashes>"
                line.startsWith("- ") || line.startsWith("* ") -> "<ul><li>${inline(line.drop(2))}</li></ul>"
                line.startsWith("> ") -> "<blockquote>${inline(line.drop(2))}</blockquote>"
                else -> inline(line) + "<br>"
            }
        }
        internal fun mime(values: Map<String, String>, format: PushFormat, message: PushMessage): String {
            val headers = "From: ${values.getValue("account")}\r\nTo: ${values.getValue("to")}\r\nSubject: ${subject(message.title)}\r\nMIME-Version: 1.0\r\n"
            fun part(type: String, text: String) = "Content-Type: $type; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n${encoded(text)}\r\n"
            if (message.memory == null) return headers + if (format == PushFormat.Text) part("text/plain", message.rendered(format)) else part("text/html", markdownHtml(message.rendered(PushFormat.Markdown)))
            val boundary = "resound-${UUID.randomUUID()}"
            val filename = "${appDisplayName}-${message.memory.getString("date")}.md"
            val encodedFilename = java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
            return headers + "Content-Type: multipart/mixed; boundary=\"$boundary\"\r\n\r\n--$boundary\r\n" +
                part("text/plain", "${message.body}\n\n完整记忆见附件") + "--$boundary\r\n" +
                "Content-Type: text/markdown; charset=UTF-8\r\nContent-Disposition: attachment; filename*=UTF-8''$encodedFilename\r\n" +
                "Content-Transfer-Encoding: base64\r\n\r\n${encoded(message.memory.getString("markdown"))}\r\n--$boundary--\r\n"
        }
    }
}
