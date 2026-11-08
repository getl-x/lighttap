package cn.lighttap.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.Display
import androidx.annotation.RequiresApi
import androidx.core.graphics.scale
import kotlinx.coroutines.*
import kotlin.coroutines.resume

/** No OCR/model/network: inspect a small sample of the known list's white body.
 * Screenshots and pixels stay in memory and are discarded after each check. */
class AndroidReturnVision(private val service: AccessibilityService) : VisitReturnVision {
    override suspend fun inspect(tree: UiTree): ReturnFrame {
        if (Build.VERSION.SDK_INT < 30) throw VisitMismatch("此设备不支持提交后画面核验，已暂停")
        return capture(tree)
    }
    @RequiresApi(30)
    private suspend fun capture(tree: UiTree): ReturnFrame = withTimeoutOrNull(2_000) {
        suspendCancellableCoroutine { continuation ->
            service.takeScreenshot(Display.DEFAULT_DISPLAY, Dispatchers.Default.asExecutor(), object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    var hardware: Bitmap? = null
                    var software: Bitmap? = null
                    var sample: Bitmap? = null
                    val pixels = IntArray(72 * 96)
                    try {
                        if (!continuation.isActive) return
                        hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: error("No screenshot")
                        software = hardware!!.copy(Bitmap.Config.ARGB_8888, false) ?: error("No readable screenshot")
                        val tabBottom = tree.exact("待办", true).singleOrNull()?.bounds?.bottom ?: error("Missing tab")
                        val left = (software!!.width * .04).toInt()
                        val top = maxOf(tabBottom + 8, (software!!.height * .26).toInt())
                        val right = (software!!.width * .96).toInt()
                        val bottom = (software!!.height * .90).toInt()
                        require(top < bottom && left < right)
                        val crop = Bitmap.createBitmap(software!!, left, top, right - left, bottom - top)
                        try { sample = crop.scale(72, 96, false) }
                        finally { if (crop !== sample) crop.recycle() }
                        sample!!.getPixels(pixels, 0, 72, 0, 0, 72, 96)
                        // Screenshot timestamps use uptime; convert to elapsedRealtime
                        // before comparing with persisted monotonic submission times.
                        val age = (SystemClock.uptimeMillis() - result.timestamp).coerceAtLeast(0)
                        val at = SystemClock.elapsedRealtime() - age
                        if (continuation.isActive) continuation.resume(ReturnFrame(classifyReturnPixels(pixels, 72, 96), at))
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resume(ReturnFrame(ReturnVisual.UNKNOWN, SystemClock.elapsedRealtime()))
                    } finally {
                        pixels.fill(0); sample?.recycle(); software?.recycle(); hardware?.recycle(); buffer.close()
                    }
                }
                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(VisitReadPending("提交后画面暂不可读取（系统码 $errorCode）")))
                }
            })
        }
    } ?: ReturnFrame(ReturnVisual.UNKNOWN, SystemClock.elapsedRealtime())
}
