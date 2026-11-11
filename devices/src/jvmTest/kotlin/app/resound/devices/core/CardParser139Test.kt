package app.resound.devices.core

import org.junit.Assert.*
import org.junit.Test

/** 换 JSON 库最易退化的三处：转义键、类型强转、资源上限。 */
class CardParser139Test {
    private val parser = CardParser("0.2.21")
    private val base = """{"id":"sample","name":"测试","version":1,"minApp":"0.2.21","status":"trial","transport":"ble","match":{"namePrefix":["Sample"]}}"""

    private fun rejected(json: String, message: String) {
        val error = parser.parse(json).exceptionOrNull()
        assertNotNull(json, error)
        assertTrue(error?.message, error!!.message.orEmpty().contains(message))
    }

    @Test fun escapedKeysAreDecodedBeforeDuplicateCheck() {
        assertEquals("sample", parser.parse(base.replace("\"id\"", "\"\\u0069d\"")).getOrThrow().id)
        rejected(base.replace("\"id\":", "\"\\u0069d\":\"duplicate\",\"id\":"), "字段重复")
        rejected(base.replace("\"namePrefix\":", "\"\\u006eamePrefix\":[],\"namePrefix\":"), "字段重复")
    }

    @Test fun numericBooleanAndNullTypesRemainStrict() {
        for (value in listOf("\"247\"", "247.0", "2.47e2", "true", "9223372036854775808")) {
            rejected(base.dropLast(1) + ",\"mtu\":$value}", "必须为整数")
        }
        rejected(base.dropLast(1) + ",\"mtu\":518}", "必须在 23–517 之间")
        rejected(base.dropLast(1) + ",\"mtu\":null}", "不能为 null")
        rejected(base.dropLast(1) + ",\"bond\":\"false\"}", "必须为布尔值")
        rejected(base.replace("\"name\":\"测试\"", "\"name\":123"), "必须为非空字符串")
        assertFalse(parser.parse(base.dropLast(1) + ",\"bond\":false}").getOrThrow().link.bond)
        assertEquals(65535, parser.parse(base.replace("[\"Sample\"]", "[]").replace("\"match\":{", "\"match\":{\"manufacturerId\":\"0xFFFF\",")).getOrThrow().match.manufacturerId)
    }

    @Test fun syntaxDepthAndSizeLimitsRemainStrict() {
        rejected(base.replace("\"version\":1", "\"version\":01"), "JSON 格式错误")
        rejected(base + " false", "JSON 后有多余内容")
        rejected("[".repeat(33) + "0" + "]".repeat(33), "嵌套不能超过 32 层")
        rejected(" ".repeat(256 * 1024) + base, "超过 256 KiB")
    }
}
