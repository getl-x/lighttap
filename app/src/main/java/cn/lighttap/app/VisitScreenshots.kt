package cn.lighttap.app

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import java.util.WeakHashMap

internal object VisitScreenshots {
    private val readers = WeakHashMap<AccessibilityService, ScreenshotGate>()
    private fun reader(service: AccessibilityService): ScreenshotGate = synchronized(readers) {
        readers.getOrPut(service) { ScreenshotGate({ SystemClock.elapsedRealtime() }) }
    }
    suspend fun awaitReady(service: AccessibilityService) = reader(service).awaitReady()
    suspend fun <T> capture(service: AccessibilityService, operation: suspend () -> T): T {
        return reader(service).capture(operation)
    }
}
