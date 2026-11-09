package cn.lighttap.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace

/** Hardware screenshots may carry Display P3 samples. Normalize to sRGB before
 * comparing channel values or supplying RGB-trained OCR models. */
internal fun screenshotSrgb(source: Bitmap): Bitmap {
    val readable = source.copy(Bitmap.Config.ARGB_8888, true) ?: error("No readable screenshot")
    if (readable.colorSpace?.isSrgb == true) return readable
    var normalized: Bitmap? = null
    try {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888, true, ColorSpace.get(ColorSpace.Named.SRGB))
        output.density = readable.density
        normalized = output
        Canvas(output).drawBitmap(readable, 0f, 0f, null)
        return output
    } catch (error: Throwable) {
        normalized?.recycle()
        throw error
    } finally { readable.eraseColor(android.graphics.Color.TRANSPARENT); readable.recycle() }
}
