package cn.lighttap.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

class AndroidTapDispatcher(private val service: AccessibilityService, private val canTap: (List<ClickPoint>) -> Boolean = { true }) : TapDispatcher {
    private val lock = Mutex()
    override suspend fun tap(points: List<ClickPoint>): Boolean = lock.withLock {
        currentCoroutineContext().ensureActive()
        if (!canTap(points)) return@withLock false
        // A stopped run may leave one gesture in flight. Keep the lock until
        // its callback, so an immediate restart cannot overlap that gesture.
        val result = withContext(NonCancellable) {
            withTimeoutOrNull((points.maxOfOrNull { it.gestureDuration } ?: 50) + 2_000) {
                suspendCancellableCoroutine { continuation ->
                    val gesture = GestureDescription.Builder().apply {
                        points.forEach { p ->
                            val path = Path().apply { moveTo(p.x, p.y); if (p.type == ActionType.SWIPE) lineTo(p.endX, p.endY) }
                            if (p.type == ActionType.DOUBLE_TAP) {
                                addStroke(GestureDescription.StrokeDescription(path, 0, p.durationMs))
                                addStroke(GestureDescription.StrokeDescription(path, p.durationMs + 100, p.durationMs))
                            } else addStroke(GestureDescription.StrokeDescription(path, 0, p.gestureDuration))
                        }
                    }.build()
                    val callback = object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(true) }
                        override fun onCancelled(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(false) }
                    }
                    try {
                        if (!service.dispatchGesture(gesture, callback, null) && continuation.isActive) continuation.resume(false)
                    } catch (_: Exception) { if (continuation.isActive) continuation.resume(false) }
                }
            } ?: false
        }
        currentCoroutineContext().ensureActive()
        result
    }
}
