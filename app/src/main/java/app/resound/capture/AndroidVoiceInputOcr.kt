package app.resound.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/** 每次请求只有一次系统截图；位图只活到本地识字任务完成，不创建文件、不传输图片。 */
internal class AndroidVoiceInputOcr(private val service: AccessibilityService) {
    // 三张截图各自独立识字（0.5 / 1 / 3 秒），不能因上一张还在识字就拒绝下一张
    fun capture(layout: OcrLayout, pkg: String, valid: () -> Boolean, complete: (OcrInput?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30 || !valid()) { complete(null); return }
        fun finish(result: OcrInput?) { complete(if (valid()) result else null) }
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onFailure(errorCode: Int) { dbg("截屏失败：代码=$errorCode"); finish(null) }
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    val bitmap = try {
                        if (!valid()) null else Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)?.let { hardware ->
                            try { hardware.copy(Bitmap.Config.ARGB_8888, false) } finally { hardware.recycle() }
                        }
                    } catch (_: Exception) { null } finally { buffer.close() }
                    if (bitmap == null) { finish(null); return }
                    // 旋转或分辨率改变时旧坐标不可靠，丢弃这一张，不补截。
                    if (bitmap.width != layout.width || bitmap.height != layout.height ||
                        VoiceInputOcr.black(bitmap.width, bitmap.height, layout.contentTop,
                            minOf(layout.contentBottom, layout.imeTop ?: (layout.height * .6).toInt()), bitmap::getPixel)) {
                        bitmap.recycle(); dbg("截屏跳过：尺寸变化或黑屏"); finish(null); return
                    }
                    val recognizer = try { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
                    catch (_: Exception) { bitmap.recycle(); finish(null); return }
                    try {
                        recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener(service.mainExecutor) { task ->
                            val result = try {
                                if (!task.isSuccessful || !valid()) null else {
                                    val blocks = task.result.textBlocks
                                    val raw = blocks.flatMapIndexed { index, block -> block.lines.mapNotNull { line ->
                                        line.boundingBox?.let { OcrLine(line.text, it.left, it.top, it.right, it.bottom, index) }
                                    } }
                                    // 识字偶尔出繁体（測、輸），统一转简体
                                    val hans = runCatching { android.icu.text.Transliterator.getInstance("Hant-Hans") }.getOrNull()
                                    val hot = if (app.resound.AppGraph.isHotwordsReady) app.resound.AppGraph.hotwords.let { it.words.value + it.automatic.value } else emptyList()
                                    // 繁转简，再按热词纠错
                                    val lines = raw.map { l -> l.copy(text = VoiceInputHotwords.correct(hans?.transliterate(l.text) ?: l.text, hot)) }
                                    val bubble = VoiceInputOcr.lastRightBubble(lines, layout)
                                    val input = VoiceInputOcr.extract(lines, layout, pkg)?.copy(bubble = bubble)
                                        ?: bubble?.let { OcrInput("", emptyList(), null, it) }
                                    dbg("识字完成：块数=${blocks.size} 输入框字数=${input?.text?.length ?: 0} 右侧气泡字数=${bubble?.length ?: 0}")
                                    input
                                }
                            } catch (_: Exception) { null }
                            finally { bitmap.recycle(); recognizer.close() }
                            finish(result)
                        }
                    } catch (_: Exception) { bitmap.recycle(); recognizer.close(); finish(null) }
                }
            })
        } catch (_: Exception) { dbg("截屏失败：调用异常"); finish(null) }
    }
}
