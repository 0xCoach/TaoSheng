package app.resound.push

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
        soTimeout = 15_000
        try { connect(InetSocketAddress(host, port), 15_000) } catch (e: Exception) { close(); throw e }
    }
    open fun tls(socket: Socket, host: String, port: Int): Socket =
        ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, host, port, true) as SSLSocket).apply {
            soTimeout = 15_000
            sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            try { startHandshake() } catch (e: Exception) { close(); throw e }
        }
}
/**
 * 同一邮箱的发信排队：同一时间只连一次；[idleMillis] 内接着发的复用已登录的连接，少登录几次。
 * QQ/163 等短时间登录多了会以 535 拒绝（与密码错同码），这是“时好时坏的鉴权失败”的主要来源。
 */
internal class SmtpSender(private val sockets: SmtpSockets = SmtpSockets(), private val idleMillis: Long = 20_000) {
    private class Session(val key: String, var socket: Socket, var reader: BufferedReader, var writer: OutputStreamWriter) {
        var consumed = 0
        var lastUsed = 0L
        var idle: java.util.concurrent.ScheduledFuture<*>? = null
        fun reply(expected: Int, values: Map<String, String>) {
            var first: String? = null
            while (true) {
                val line = StringBuilder()
                while (true) {
                    val c = reader.read()
                    if (c < 0) throw PushFailure("邮件连接已断开：检查网络和 SMTP 服务后重试")
                    if (++consumed >= 65536) throw PushFailure("推送服务响应过大")
                    if (c == 10) break
                    if (c != 13) line.append(c.toChar())
                }
                val text = line.toString()
                val code = text.take(3).toIntOrNull() ?: throw PushFailure(PushErrors.INVALID_RESPONSE)
                if (first == null) first = text.take(3)
                if (text.take(3) != first || text.length < 4 || text[3] !in " -") throw PushFailure(PushErrors.INVALID_RESPONSE)
                if (code != expected) throw PushFailure(SmtpReplies.failure(code, text.drop(4), values))
                if (text[3] == ' ') return
            }
        }
        fun command(command: String, expected: Int, values: Map<String, String>) { writer.write(command + "\r\n"); writer.flush(); reply(expected, values) }
        fun close(quit: Boolean) {
            idle?.cancel(false)
            if (quit) runCatching { writer.write("QUIT\r\n"); writer.flush() }
            runCatching { socket.close() }
        }
    }
    private val sessions = HashMap<String, Session>()
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    fun send(values: Map<String, String>, format: PushFormat, message: PushMessage) {
        val host = values.getValue("host")
        val port = PushFields.default(values, "port", "465").toInt()
        val key = listOf(host, port.toString(), values.getValue("account"), base64(values.getValue("password"))).joinToString("\u0000")
        synchronized(locks.getOrPut(key) { Any() }) {
            val reused = synchronized(sessions) { sessions.remove(key) }?.also { it.idle?.cancel(false) }
            if (reused != null && System.currentTimeMillis() - reused.lastUsed < idleMillis) {
                // 复用的连接可能已被服务器关掉：失败就丢掉它，按新连接再发一次（DATA 前失败不会重复投递）
                val delivered = try { deliver(reused, values, format, message, reset = true); true }
                catch (e: kotlinx.coroutines.CancellationException) { reused.close(false); throw e }
                catch (e: PushDelivered) { reused.close(false); throw e.cause!! }
                catch (_: Exception) { reused.close(false); false }
                if (delivered) { keep(reused); return }
            } else reused?.close(true)
            val session = open(host, port, key, values)
            try { deliver(session, values, format, message, reset = false) }
            catch (e: PushDelivered) { session.close(false); throw e.cause!! }
            catch (e: Exception) { session.close(false); throw e }
            keep(session)
        }
    }
    /** DATA 之后才失败的标记：邮件可能已投出，不能换连接重发 */
    private class PushDelivered(cause: Exception) : Exception(cause)

    private fun open(host: String, port: Int, key: String, values: Map<String, String>): Session {
        val socket = AtomicReference<Socket?>()
        // 连接、握手、登录合计 60 秒；单次读写 15 秒（见 SmtpSockets）
        val deadline = timer.schedule({ runCatching { socket.get()?.close() } }, 60, TimeUnit.SECONDS)
        try {
            socket.set(sockets.connect(host, port))
            if (port == 465) socket.set(sockets.tls(socket.get()!!, host, port))
            val session = Session(key, socket.get()!!, BufferedReader(InputStreamReader(socket.get()!!.getInputStream(), Charsets.US_ASCII)),
                OutputStreamWriter(socket.get()!!.getOutputStream(), Charsets.UTF_8))
            session.reply(220, values)
            session.command("EHLO resound.local", 250, values)
            if (port != 465) {
                session.command("STARTTLS", 220, values)
                session.socket = sockets.tls(session.socket, host, port); socket.set(session.socket)
                session.reader = BufferedReader(InputStreamReader(session.socket.getInputStream(), Charsets.US_ASCII))
                session.writer = OutputStreamWriter(session.socket.getOutputStream(), Charsets.UTF_8)
                session.command("EHLO resound.local", 250, values)
            }
            session.command("AUTH LOGIN", 334, values)
            session.command(base64(values.getValue("account")), 334, values)
            session.command(base64(values.getValue("password")), 235, values)
            return session
        } catch (e: kotlinx.coroutines.CancellationException) { runCatching { socket.get()?.close() }; throw e }
        catch (e: Exception) {
            runCatching { socket.get()?.close() }
            throw PushErrors.failure(if (deadline.isDone) java.net.SocketTimeoutException() else e, values)
        } finally { deadline.cancel(false) }
    }

    private fun deliver(session: Session, values: Map<String, String>, format: PushFormat, message: PushMessage, reset: Boolean) {
        val deadline = timer.schedule({ runCatching { session.socket.close() } }, 60, TimeUnit.SECONDS)
        var dataSent = false
        try {
            session.consumed = 0
            if (reset) session.command("RSET", 250, values)
            session.command("MAIL FROM:<${values.getValue("account")}>", 250, values)
            session.command("RCPT TO:<${values.getValue("to")}>", 250, values)
            session.command("DATA", 354, values)
            val mime = mime(values, format, message)
            dataSent = true
            session.writer.write(mime.lineSequence().joinToString("\r\n") { if (it.startsWith('.')) ".$it" else it })
            session.writer.write("\r\n.\r\n"); session.writer.flush(); session.reply(250, values)
            session.lastUsed = System.currentTimeMillis()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            val failure = PushErrors.failure(if (deadline.isDone) java.net.SocketTimeoutException() else e, values)
            throw if (dataSent) PushDelivered(failure) else failure
        } finally { deadline.cancel(false) }
    }

    /** 留着等下一封；空闲到期发 QUIT 断开。idleMillis=0 时立即断开（测试与旧行为） */
    private fun keep(session: Session) {
        if (idleMillis <= 0) { session.close(true); return }
        synchronized(sessions) {
            sessions.put(session.key, session)?.takeIf { it !== session }?.close(true)
            session.idle = timer.schedule({
                synchronized(sessions) { if (sessions[session.key] === session) sessions.remove(session.key) else return@schedule }
                session.close(true)
            }, idleMillis, TimeUnit.MILLISECONDS)
        }
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
            if (message.memory == null) return headers + if (format == PushFormat.Text) part("text/plain", message.rendered(format)) else part("text/html",
                markdownHtml(message.rendered(PushFormat.Markdown)))
            val boundary = "resound-${UUID.randomUUID()}"
            val filename = "记忆-${message.memory.getString("date")}.md"
            val encodedFilename = java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
            return headers + "Content-Type: multipart/mixed; boundary=\"$boundary\"\r\n\r\n--$boundary\r\n" +
                part("text/plain", "${message.body}\n\n完整记忆见附件") + "--$boundary\r\n" +
                "Content-Type: text/markdown; charset=UTF-8\r\nContent-Disposition: attachment; filename*=UTF-8''$encodedFilename\r\n" +
                "Content-Transfer-Encoding: base64\r\n\r\n${encoded(message.memory.getString("markdown"))}\r\n--$boundary--\r\n"
        }
    }
}

/** 把 SMTP 的拒绝码翻成人话；带上服务器原话（去掉账号密码），限流和密码错分开说 */
internal object SmtpReplies {
    private val limited = Regex("frequen|too many|limit|rate|频繁|频率|稍后", RegexOption.IGNORE_CASE)
    fun failure(code: Int, server: String, values: Map<String, String>): String {
        val reason = server.replace(Regex("\\s+"), " ").trim().take(80).let { r ->
            listOfNotNull(values["account"], values["password"]).filter { it.isNotBlank() }.fold(r) { acc, v -> acc.replace(v, "***") } }
        val said = if (reason.isEmpty()) "" else "：$reason"
        return when (code) {
            535, 534, 530, 538 -> if (limited.containsMatchIn(reason))
                "邮箱服务器限制登录频率（$code）$said。稍后会自动重试，不用改设置"
                else "邮件登录被拒（$code）$said。QQ/163 要用授权码，Gmail 要用应用专用密码；以前能发、偶尔这样多半是登录太频繁，会自动重试"
            421, 450, 451, 452 -> "邮件服务暂时无法接收（$code）$said。稍后自动重试，或检查当天发信额度"
            else -> PushErrors.refused(PushKind.Email, org.json.JSONObject().put("message", server), values)
        }
    }
}
