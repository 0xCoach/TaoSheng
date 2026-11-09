package app.resound.push

import app.resound.review.DailyReview
import app.resound.review.ReviewSection
import okhttp3.FormBody
import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PushNewline113Test {
    private val body = "## 内容\n第一行\n**第二行** [链接](https://example.com)\n\n- 甲\n- 乙\n\n尾行 <&>"
    private val plus = "## 内容\n第一行<br/>\n**第二行** [链接](https://example.com)\n\n- 甲<br/>\n- 乙\n\n尾行 <&>"
    private val hard = "## 内容\n第一行  \n**第二行** [链接](https://example.com)\n\n- 甲  \n- 乙\n\n尾行 <&>"
    private val plain = "内容\n第一行\n第二行 链接 (https://example.com)\n\n甲\n乙\n\n尾行 <&>"
    private val message = PushMessage("【涛声测试】推送换行", body, PushTopic.NewEvent, null, 1)
    private val transport = PushTransport(now = { 1_700_000_000_000L })
    private fun request(kind: PushKind, format: PushFormat, value: PushMessage = message): Request = transport.request(kind, format, when (kind) {
        PushKind.PushPlus -> mapOf("token" to "fake-token")
        PushKind.WeCom -> mapOf("webhook" to "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=fake")
        PushKind.DingTalk -> mapOf("webhook" to "https://oapi.dingtalk.com/robot/send?access_token=fake", "signSecret" to "secret")
        PushKind.Feishu -> mapOf("webhook" to "https://open.feishu.cn/open-apis/bot/v2/hook/fake", "signSecret" to "secret")
        PushKind.Telegram -> mapOf("token" to "123:fake_token", "chatId" to "123")
        PushKind.ServerChan -> mapOf("sendKey" to "SCTfake-key")
        PushKind.Webhook -> mapOf("url" to "https://example.com/hook", "signSecret" to "secret")
        else -> error("多步通道")
    }, value)
    private fun Request.text() = Buffer().also { body!!.writeTo(it) }.readUtf8()
    private fun Request.json() = JSONObject(text())

    @Test fun pushPlusMarkdownKeepsParagraphsListsAndAllTopics() {
        PushTopic.entries.forEach { topic ->
            val json = request(PushKind.PushPlus, PushFormat.Markdown, message.copy(topic = topic)).json()
            assertEquals("markdown", json.getString("template"))
            assertEquals(message.title, json.getString("title"))
            assertEquals(plus, json.getString("content"))
        }
        // 供人工真实发送复用：仅含假 token，真实密钥绝不进入构建产物。
        val artifact = File("build/113-pushplus-markdown.json")
        artifact.parentFile.mkdirs()
        artifact.writeText(request(PushKind.PushPlus, PushFormat.Markdown).text())
    }

    @Test fun pushPlusTextAndJsonKeepNativeNewlinesAndStructuredData() {
        val text = request(PushKind.PushPlus, PushFormat.Text).json()
        assertEquals("txt", text.getString("template"))
        assertEquals(plain, text.getString("content"))
        File("build/113-pushplus-txt.json").writeText(text.toString())
        val json = request(PushKind.PushPlus, PushFormat.Json).json()
        assertEquals(message.envelope(PushFormat.Json).toString(), json.getString("content"))
        assertEquals(body, JSONObject(json.getString("content")).getString("body"))
        val review = DailyReview(LocalDate.parse("2026-11-09"), "摘要一\n摘要二", listOf(
            ReviewSection("事项", listOf("甲", "乙"), listOf(null, null))), 1)
        val content = request(PushKind.PushPlus, PushFormat.Markdown, message.copy(review = review)).json().getString("content")
        assertTrue(content.contains("摘要一<br/>\n摘要二"))
        assertTrue(content.contains("### 事项\n- 甲<br/>\n- 乙"))
    }

    @Test fun weComSubsetKeepsNativeLineBreaksWithoutHtml() {
        PushFormat.entries.forEach { format ->
            val json = request(PushKind.WeCom, format).json()
            val type = if (format == PushFormat.Text) "text" else "markdown"
            assertEquals(type, json.getString("msgtype"))
            assertEquals("${message.title}\n${if (format == PushFormat.Text) plain else body}", json.getJSONObject(type).getString("content"))
        }
    }

    @Test fun dingTalkMarkdownHardBreaksKeepTitleAndSignature() {
        PushFormat.entries.forEach { format ->
            val req = request(PushKind.DingTalk, format)
            val json = req.json()
            assertEquals("OuzzJR5+xZ4/EYwqtNt6sMYZQMTa/HEGvc9miJe7XzY=", req.url.queryParameter("sign"))
            if (format == PushFormat.Text) assertEquals("${message.title}\n$plain", json.getJSONObject("text").getString("content"))
            else {
                assertEquals(message.title, json.getJSONObject("markdown").getString("title"))
                assertEquals("## ${message.title}\n\n$hard", json.getJSONObject("markdown").getString("text"))
            }
        }
    }

    @Test fun feishuCardKeepsNativeNewlinesAndSignature() {
        PushFormat.entries.forEach { format ->
            val json = request(PushKind.Feishu, format).json()
            assertEquals("fiWS2+gh28DOydAv7hzONH/mDn9+b1Y4Y5ivXWXy8vA=", json.getString("sign"))
            if (format == PushFormat.Text) assertEquals("${message.title}\n$plain", json.getJSONObject("content").getString("text"))
            else {
                assertEquals("interactive", json.getString("msg_type"))
                assertEquals(message.title, json.getJSONObject("card").getJSONObject("header").getJSONObject("title").getString("content"))
                assertEquals(body, json.getJSONObject("card").getJSONArray("elements").getJSONObject(0).getString("content"))
            }
        }
    }

    @Test fun telegramKeepsNativeNewlinesEscapesAndTruncatesBeforeHtml() {
        PushFormat.entries.forEach { format ->
            val json = request(PushKind.Telegram, format).json()
            if (format == PushFormat.Text) {
                assertFalse(json.has("parse_mode"))
                assertEquals("${message.title}\n$plain", json.getString("text"))
            } else {
                assertEquals("HTML", json.getString("parse_mode"))
                assertEquals("<b>${message.title}</b>\n${body.replace("<&>", "&lt;&amp;&gt;")}", json.getString("text"))
            }
        }
        val long = message.copy(body = "<&😀\n".repeat(1200))
        val content = request(PushKind.Telegram, PushFormat.Markdown, long).json().getString("text")
        assertTrue(content.endsWith("\n（已截断）"))
        val visible = content.removePrefix("<b>${message.title}</b>\n").replace("&lt;", "<").replace("&amp;", "&")
        assertTrue(visible.length + message.title.length + 1 <= 4000)
        assertFalse(visible.dropLast("\n（已截断）".length).last().isHighSurrogate())
    }

    @Test fun serverChanUsesMarkdownEvenForTextAndJsonSelections() {
        PushFormat.entries.forEach { format ->
            val form = request(PushKind.ServerChan, format).body as FormBody
            assertEquals(message.title, form.value(0))
            assertEquals(if (format == PushFormat.Text) "内容  \n第一行  \n第二行 链接 (https://example.com)\n\n甲  \n乙\n\n尾行 <&>" else hard, form.value(1))
        }
    }

    @Test fun webhookSignsFinalBytesInEveryFormat() {
        PushFormat.entries.forEach { format ->
            val req = request(PushKind.Webhook, format)
            val content = req.text()
            assertEquals(when (format) {
                PushFormat.Markdown -> "# ${message.title}\n\n$hard"
                PushFormat.Text -> "${message.title}\n$plain"
                PushFormat.Json -> message.envelope(format).toString()
            }, content)
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("secret".toByteArray(), "HmacSHA256")) }
            val hash = mac.doFinal("${req.header("X-Resound-Timestamp")}.$content".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals("sha256=$hash", req.header("X-Resound-Signature"))
        }
    }

    @Test fun emailAndDocumentsRetainTheirOwnRenderingAndSnapshots() {
        val values = mapOf("account" to "from@example.com", "to" to "to@example.com")
        PushFormat.entries.forEach { format ->
            for (kind in listOf(PushKind.Email, PushKind.Notion, PushKind.MarkdownFolder)) assertEquals(body, PushBodyLayout.convert(kind, format, body))
            val mime = SmtpSender.mime(values, format, message)
            val decoded = String(Base64.getMimeDecoder().decode(mime.substringAfter("Content-Transfer-Encoding: base64\r\n\r\n").trim()), Charsets.UTF_8)
            if (format == PushFormat.Text) assertEquals(plain, decoded)
            else {
                assertTrue(decoded.contains("第一行<br>\n<strong>第二行</strong>"))
                assertTrue(decoded.contains("<br>\n<ul><li>甲</li></ul>\n<ul><li>乙</li></ul>"))
            }
        }
        assertEquals(body, PushMessage.from(message.json()).body)
        assertEquals(body, message.rendered(PushFormat.Markdown))
    }

    @Test fun markdownBlocksAndExistingHardBreaksAreNotDamaged() {
        val blocks = "# 标题\n正文  \n已换行<br/>\n\n```md\n一\n二\n```\n\n| 列 |\n| --- |\n| 值 |\n\n- 甲\n  - 子项\n- 乙"
        for (kind in listOf(PushKind.PushPlus, PushKind.DingTalk, PushKind.ServerChan, PushKind.Webhook)) {
            val converted = PushBodyLayout.convert(kind, PushFormat.Markdown, blocks)
            assertTrue(converted.startsWith("# 标题\n正文  \n已换行<br/>\n\n```md\n一\n二\n```"))
            assertTrue(converted.contains("| 列 |\n| --- |\n| 值 |"))
            assertEquals(converted, PushBodyLayout.convert(kind, PushFormat.Markdown, converted))
        }
    }
}
