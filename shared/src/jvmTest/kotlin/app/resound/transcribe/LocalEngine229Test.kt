package app.resound.transcribe

import app.resound.transcribe.local.LocalBackend
import app.resound.transcribe.local.LocalEngine
import app.resound.transcribe.local.LocalFacts
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalEngine229Test {
    private val supported = LocalFacts(LocalBackend.Npu, npuSupported = true, npuReady = true, cpuReady = true, npuCheck = null)

    @Test fun npuSelectedUsesTrialOrCheckedModel() {
        expect(supported, LocalBackend.Npu, "已选 NPU 识别，下次转写时先试用")
        expect(supported.copy(npuCheck = true), LocalBackend.Npu, "正在使用：NPU 识别")
    }

    @Test fun upgradeAndFailedNpuCheckUseCpu() {
        expect(supported.copy(npuReady = false), LocalBackend.Cpu, "NPU 模型还没下载好，先用 CPU 识别")
        expect(supported.copy(npuCheck = false), LocalBackend.Cpu, "NPU 识别暂时不可用，已自动改用 CPU（重新选一次 NPU 识别可再试）")
    }

    @Test fun cpuSelectedPrefersCpuAndCanUseNpuWhenCpuIsMissing() {
        val cpu = supported.copy(wish = LocalBackend.Cpu)
        expect(cpu, LocalBackend.Cpu, "正在使用：CPU 识别")
        expect(cpu.copy(cpuReady = false), LocalBackend.Npu, "CPU 模型还没下载好，下次转写时先试用 NPU 识别")
        expect(cpu.copy(cpuReady = false, npuCheck = true), LocalBackend.Npu, "CPU 模型还没下载好，先用 NPU 识别")
    }

    @Test fun unsupportedChipUsesCpuEvenWithNpuFilesAndOldCheck() {
        val unsupported = supported.copy(npuSupported = false, npuCheck = true)
        expect(unsupported, LocalBackend.Cpu, "正在使用：CPU 识别")
        expect(unsupported.copy(cpuReady = false), null, "请先下载 CPU 模型")
    }

    @Test fun missingOrFailedModelsGiveDownloadInstructions() {
        val missing = supported.copy(cpuReady = false)
        expect(missing.copy(npuReady = false), null, "请先下载 NPU 或 CPU 模型")
        expect(missing.copy(wish = LocalBackend.Cpu, npuReady = false), null, "请先下载 NPU 或 CPU 模型")
        expect(missing.copy(npuCheck = false), null, "NPU 识别暂时不可用，请先下载 CPU 模型")
    }

    private fun expect(facts: LocalFacts, backend: LocalBackend?, message: String) {
        assertEquals(backend, LocalEngine.choose(facts))
        assertEquals(message, LocalEngine.status(facts))
    }
}
