package app.resound

import app.resound.PipelineProgress.Stage
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = android.app.Application::class)
class PipelineProgressTest {
    @After fun reset() = PipelineProgress.finish()

    @Test fun queuedNeverHidesARunningStepAndEachStepStartsFresh() {
        PipelineProgress.queued("等 WiFi")
        assertEquals(Stage.Queued, PipelineProgress.state.value.stage)
        PipelineProgress.stage(Stage.Voiceprint); PipelineProgress.progress(3, 10)
        PipelineProgress.queued("马上开始…") // 正在跑时再点，只追加一轮，不能把进度盖成“排队”
        assertEquals(Stage.Voiceprint, PipelineProgress.state.value.stage)
        assertEquals(3 to 10, PipelineProgress.state.value.let { it.done to it.total })
        PipelineProgress.stage(Stage.Digest)
        assertEquals(0 to 0, PipelineProgress.state.value.let { it.done to it.total })
        PipelineProgress.finish()
        assertNull(PipelineProgress.state.value.stage)
    }
}
