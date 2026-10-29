package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

data class OfflineZipformerAudioTaggingModelConfig(
    var model: String = "",
)

data class AudioTaggingModelConfig(
    var zipformer: OfflineZipformerAudioTaggingModelConfig = OfflineZipformerAudioTaggingModelConfig(),
    var ced: String = "",
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
)

data class AudioTaggingConfig(
    var model: AudioTaggingModelConfig = AudioTaggingModelConfig(),
    var labels: String = "",
    var topK: Int = 5,
)

data class AudioEvent(
    val name: String,
    val index: Int,
    val prob: Float,
)

class AudioTagging(
    assetManager: AssetManager? = null,
    config: AudioTaggingConfig,
) {
    private var ptr: Long

    init {
        ptr = if (assetManager != null) {
            newFromAsset(assetManager, config)
        } else {
            newFromFile(config)
        }
        require(ptr != 0L) {
            "Invalid AudioTaggingConfig: failed to create native AudioTagging"
        }
    }

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    fun createStream(): OfflineStream {
        val p = createStream(ptr)
        return OfflineStream(p)
    }

    @Suppress("UNCHECKED_CAST")
    fun compute(stream: OfflineStream, topK: Int = -1): Array<AudioEvent> {
        return compute(ptr, stream.ptr, topK)
    }

    private external fun newFromAsset(
        assetManager: AssetManager,
        config: AudioTaggingConfig,
    ): Long

    private external fun newFromFile(
        config: AudioTaggingConfig,
    ): Long

    private external fun delete(ptr: Long)

    private external fun createStream(ptr: Long): Long

    private external fun compute(ptr: Long, streamPtr: Long, topK: Int): Array<AudioEvent>

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}

