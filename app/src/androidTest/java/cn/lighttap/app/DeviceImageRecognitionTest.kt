package cn.lighttap.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Only generated text in memory; never navigates or submits in another app. */
@RunWith(AndroidJUnit4::class)
class DeviceImageRecognitionTest {
    @Test fun wideGamutPixelsNormalizeWithoutChangingGeometry() {
        val original = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888, true, android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
        val wide = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888, true, android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.DISPLAY_P3))
        val expected = Color.rgb(25, 137, 250)
        original.eraseColor(expected)
        wide.density = original.density
        Canvas(wide).drawBitmap(original, 0f, 0f, null)
        val normalized = screenshotSrgb(wide)
        try {
            assertTrue(normalized.colorSpace!!.isSrgb)
            assertEquals(original.width, normalized.width); assertEquals(original.height, normalized.height)
            val actual = normalized.getPixel(64, 32)
            assertEquals(Color.red(expected).toFloat(), Color.red(actual).toFloat(), 2f)
            assertEquals(Color.green(expected).toFloat(), Color.green(actual).toFloat(), 2f)
            assertEquals(Color.blue(expected).toFloat(), Color.blue(actual).toFloat(), 2f)
        } finally { original.recycle(); wide.recycle(); normalized.recycle() }
    }
    @Test fun bundledChineseModelsRecognizeGeneratedButtonsOffline() = runBlocking {
        System.loadLibrary("opencv_java4")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED, context.checkSelfPermission(android.Manifest.permission.INTERNET))
        val bitmap = Bitmap.createBitmap(720, 640, Bitmap.Config.ARGB_8888)
        val ocr = PaddleOCR.create(context, PaddleOCRConfig(detLimitSideLen = 960, detLimitType = "max", recBatchSize = 4), EngineConfig(2))
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 48f }
            listOf("走访", "提交").forEachIndexed { index, text -> canvas.drawText(text, 100f, 110f + index * 135f, paint) }
            val result = ocr.recognize(bitmap)
            for (label in listOf("走访", "提交")) {
                assertTrue("Expected generated label was not recognized confidently", result.results.any { canonicalLabel(it.text) == label && it.confidence >= .90f })
            }
        } finally { bitmap.eraseColor(Color.TRANSPARENT); bitmap.recycle(); ocr.release() }
    }
}
