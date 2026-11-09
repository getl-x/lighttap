package cn.lighttap.app

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun <T : Any> screenshotWithin(timeoutMs: Long, operation: suspend () -> T): T =
    withTimeoutOrNull(timeoutMs) { operation() }
        ?: throw VisitMismatch("本机截图等待超时，请查看后继续")

/** All screen readers on one accessibility service share the same cooldown.
 * Only timing is retained; every request still acquires a fresh screenshot. */
internal class ScreenshotGate(private val now: () -> Long, private val cooldownMs: Long = 500) {
    private val mutex = Mutex()
    private var nextAt: Long? = null

    /** A caller may wait before reading its UI tree, so that a cooldown does
     * not insert a variable delay between that tree and the matching image. */
    suspend fun awaitReady() = mutex.withLock { awaitCooldown() }

    private suspend fun awaitCooldown() {
        val remaining = nextAt?.let { (it - now()).coerceAtLeast(0) } ?: 0
        if (remaining > 0) delay(remaining)
        currentCoroutineContext().ensureActive()
    }

    suspend fun <T> capture(operation: suspend () -> T): T = mutex.withLock {
        awaitCooldown()
        try { operation() }
        finally { nextAt = now() + cooldownMs }
    }
}
