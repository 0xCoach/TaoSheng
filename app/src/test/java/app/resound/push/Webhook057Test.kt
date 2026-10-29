package app.resound.push

import app.resound.review.DailyReview
import app.resound.review.ReviewSection
import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder
import java.time.LocalDate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class Webhook057Test {
    private val message = PushMessage("中文标题", "## 内容\n**重点**\n- [ ] [查看](https://example.com)", null, null, 1)
    private val transport = PushTransport(now = { 1793232000000L })
    private fun request(format: PushFormat, value: PushMessage = message) =
        transport.request(PushKind.Webhook, format, mapOf("url" to "https://example.com/hook", "signSecret" to "secret"), value)
    private fun Request.text() = Buffer().also { body!!.writeTo(it) }.readUtf8()

    @Test fun markdownSignsActualBodyAndEncodesChineseTitle() {
        val r = request(PushFormat.Markdown)
        assertEquals("text/markdown; charset=utf-8", r.body!!.contentType().toString())
        assertEquals("# 中文标题\n\n${message.body}", r.text())
        assertEquals(message.title, URLDecoder.decode(r.header("X-Resound-Title"), "UTF-8"))
        assertEquals("test", r.header("X-Resound-Type"))
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("secret".toByteArray(), "HmacSHA256")) }
        val expected = mac.doFinal("${r.header("X-Resound-Timestamp")}.${r.text()}".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals("sha256=$expected", r.header("X-Resound-Signature"))
    }

    @Test fun memoryUsesFullMarkdownAndOwnDate() {
        val memory = JSONObject().put("type", "daily_memory").put("date", "2026-10-28").put("markdown", "# 记忆\n\n完整内容")
        val r = request(PushFormat.Markdown, message.copy(memory = memory))
        assertEquals(memory.getString("markdown"), r.text())
        assertEquals("2026-10-28", r.header("X-Resound-Date"))
        assertEquals("daily_memory", r.header("X-Resound-Type"))
    }

    @Test fun jsonKeepsExistingEnvelopeAndSnapshot() {
        val expected = message.json().put("body", message.body).put("type", "test").put("version", 1).put("app", "resound")
        assertEquals(expected.toString(), request(PushFormat.Json).text())
        assertEquals("application/json; charset=utf-8", request(PushFormat.Json).body!!.contentType().toString())
        val memory = JSONObject().put("type", "daily_memory").put("date", "2026-10-28").put("markdown", "# 记忆")
        assertEquals(memory.toString(), request(PushFormat.Json, message.copy(memory = memory)).text())
        assertEquals(message.json().toString(), PushMessage.from(message.json()).json().toString())
    }

    @Test fun plainRemovesMarkupAndKeepsLinkAddress() {
        val r = request(PushFormat.Text)
        assertEquals("text/plain; charset=utf-8", r.body!!.contentType().toString())
        assertEquals("中文标题\n内容\n重点\n查看 (https://example.com)", r.text())
    }

    @Test fun reviewUsesReviewDateAndMarkdownBody() {
        val review = DailyReview(LocalDate.parse("2026-10-28"), "摘要",
            listOf(ReviewSection("要紧的", listOf("一件事"), listOf(null))), 1)
        val r = request(PushFormat.Markdown, message.copy(review = review, topic = PushTopic.DailyReview))
        assertEquals("# 中文标题\n\n${review.pushBody(PushFormat.Markdown)}", r.text())
        assertEquals("2026-10-28", r.header("X-Resound-Date"))
        assertEquals("daily_review", r.header("X-Resound-Type"))
        val json = JSONObject(request(PushFormat.Json, message.copy(review = review)).text())
        assertEquals("10月28日 星期三 · 回顾\n\n摘要\n\n【要紧的】\n- 一件事", json.getString("body"))
    }

    @Test fun otherChannelsKeepMarkdownAndConvertPlain() {
        for (kind in listOf(PushKind.Feishu, PushKind.DingTalk, PushKind.Telegram)) {
            val host = if (kind == PushKind.Feishu) "open.feishu.cn" else "oapi.dingtalk.com"
            val values = mapOf("webhook" to "https://$host/hook", "token" to "123:abc", "chatId" to "123")
            val markdown = transport.request(kind, PushFormat.Markdown, values, message).text()
            assertTrue(markdown.contains("**重点**"))
            val plain = transport.request(kind, PushFormat.Text, values, message).text()
            assertFalse(plain.contains("**"))
            assertTrue(plain.contains("https://example.com"))
        }
    }
}
