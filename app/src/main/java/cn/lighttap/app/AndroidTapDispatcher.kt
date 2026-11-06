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
        // A stopped run may leave one 50 ms gesture in flight. Keep the lock until
        // its callback, so an immediate restart cannot overlap that gesture.
        val result = withContext(NonCancellable) {
            withTimeoutOrNull(2_000) {
                suspendCancellableCoroutine { continuation ->
                    val gesture = GestureDescription.Builder().apply {
                        points.forEach { p ->
                            addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(p.x, p.y) }, 0, 50))
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