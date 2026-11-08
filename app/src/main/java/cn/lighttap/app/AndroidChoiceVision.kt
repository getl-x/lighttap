package cn.lighttap.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.Display
import androidx.annotation.RequiresApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

fun interface VisitChoiceVerifier { suspend fun requireNormal(normal: UiNode, abnormal: UiNode) }

class AndroidChoiceVision(private val service: AccessibilityService) : VisitChoiceVerifier {
    private var lastCapture = 0L
    override suspend fun requireNormal(normal: UiNode, abnormal: UiNode) {
        if (Build.VERSION.SDK_INT < 30) throw VisitMismatch("此设备不支持本机视觉核验，已暂停")
        if (!normal.visible || !abnormal.visible || !normal.bounds.nonEmpty || !abnormal.bounds.nonEmpty) throw VisitMismatch("正常/异常按钮不完整可见，不能视觉核验")
        val remaining = 450 - (SystemClock.elapsedRealtime() - lastCapture)
        if (remaining > 0) delay(remaining)
        lastCapture = SystemClock.elapsedRealtime()
        val frame = capture(normal.bounds, abnormal.bounds)
        try {
            when (withContext(Dispatchers.Default) { OpenCvChoiceBackend.classify(frame, normal.bounds, abnormal.bounds) }) {
                ChoiceResult.NORMAL -> Unit
                ChoiceResult.ABNORMAL -> throw VisitMismatch("视觉核验发现异常被选中，禁止继续")
                ChoiceResult.UNKNOWN -> throw VisitChoiceUncertain()
            }
        } finally { frame.erase() }
    }
    @RequiresApi(30)
    private suspend fun capture(normal: UiBox, abnormal: UiBox): ChoiceFrame = withTimeout(2_000) {
        suspendCancellableCoroutine { continuation ->
            service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    var hardware: Bitmap? = null
                    var crop: Bitmap? = null
                    var software: Bitmap? = null
                    try {
                        if (!continuation.isActive) return
                        val image = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: throw IllegalStateException()
                        hardware = image
                        val left = minOf(normal.left, abnormal.left)-2; val top = minOf(normal.top, abnormal.top)-2
                        val right = maxOf(normal.right, abnormal.right)+2; val bottom = maxOf(normal.bottom, abnormal.bottom)+2
                        if (left < 0 || top < 0 || right > image.width || bottom > image.height) throw IllegalStateException()
                        crop = Bitmap.createBitmap(image, left, top, right-left, bottom-top)
                        software = crop!!.copy(Bitmap.Config.ARGB_8888, false) ?: throw IllegalStateException()
                        val pixels = IntArray((right-left)*(bottom-top))
                        software!!.getPixels(pixels, 0, right-left, 0, 0, right-left, bottom-top)
                        if (continuation.isActive) continuation.resume(ChoiceFrame(left, top, right-left, bottom-top, pixels)) else pixels.fill(0)
                    } catch (_: Exception) { if (continuation.isActive) continuation.resumeWith(Result.failure(VisitMismatch("系统图像无法用于核验，已暂停"))) }
                    finally {
                        software?.recycle()
                        if (crop !== hardware) crop?.recycle()
                        hardware?.recycle(); buffer.close()
                    }
                }
                override fun onFailure(errorCode: Int) { if (continuation.isActive) continuation.resumeWith(Result.failure(VisitMismatch("本机视觉核验不可用，已暂停（系统码 $errorCode）"))) }
            })
        }
    }
}
