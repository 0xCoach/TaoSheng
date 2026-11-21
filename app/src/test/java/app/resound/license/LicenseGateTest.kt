package app.resound.license

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * 授权门禁：上游 FSL 许可与第三方声明不得再被清理删除。
 * 分发改版必须附带许可条款副本或链接、保留原版权声明。
 */
class LicenseGateTest {
    // 单测工作目录是 app/，仓库根在上一级
    private val root = File("..")

    @Test fun upstreamLicenseFileIsPresentAndComplete() {
        val text = File(root, "LICENSE.md").also { assertTrue("LICENSE.md 缺失", it.isFile) }.readText()
        assertTrue(text.contains("Functional Source License, Version 1.1, MIT Future License"))
        assertTrue(text.contains("FSL-1.1-MIT"))
        assertTrue(text.contains("Copyright 2026 0xCoach"))
        assertTrue(text.contains("Redistribution"))
        assertTrue(text.contains("Grant of Future License"))
    }

    @Test fun thirdPartyNoticesRetainUpstreamCopyright() {
        val text = File(root, "THIRD_PARTY_NOTICES.md").also { assertTrue("THIRD_PARTY_NOTICES.md 缺失", it.isFile) }.readText()
        assertTrue("缺少上游版权", text.contains("Copyright 2026 0xCoach"))
        assertTrue("缺少 FSL 标识", text.contains("FSL-1.1-MIT"))
        assertTrue("缺少 MIT 保留声明", text.contains("MIT License"))
        assertTrue("缺少 sherpa-onnx 条目", text.contains("sherpa-onnx"))
        assertTrue("缺少 Silero 条目", text.contains("Silero"))
        assertTrue("缺少上游来源链接", text.contains("github.com/0xCoach/TaoSheng"))
    }

    @Test fun noticesShipInsideTheApkAssets() {
        val text = File(root, "app/src/main/assets/THIRD_PARTY_NOTICES.md")
            .also { assertTrue("assets 内声明缺失", it.isFile) }.readText()
        assertTrue(text.contains("Copyright 2026 0xCoach"))
        assertTrue(text.contains("FSL-1.1-MIT"))
    }

    @Test fun bundledComponentLicenseCopiesArePresent() {
        listOf(
            "app/src/main/assets/SILERO_LICENSE.txt",
            "app/src/main/assets/homophones/TinyPinyin-LICENSE.txt",
            "app/src/main/assets/homophones/jieba-LICENSE.txt",
            "app/src/main/assets/homophones/aho-corasick-LICENSE.md",
            "app/src/main/assets/homophones/pinyin-data-LICENSE.txt",
        ).forEach { assertTrue("$it 缺失", File(root, it).isFile) }
    }
}
