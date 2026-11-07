package cn.lighttap.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Only observations are retried. Actions (especially submit) must never be retried here. */
class VisitObservation(private val capture: suspend () -> UiTree) {
    suspend fun stable(): UiTree = withTimeoutOrNull(3_000) {
        var previous: UiTree? = null
        while (true) {
            val current = try { capture() } catch (_: VisitReadPending) { null }
            if (current != null && current == previous) return@withTimeoutOrNull current
            previous = current
            delay(80)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    } ?: throw VisitMismatch("页面持续变化或暂不可读取，请查看后继续")

    suspend fun read(): UiTree = withTimeoutOrNull(3_000) {
        while (true) {
            try { return@withTimeoutOrNull capture() } catch (_: VisitReadPending) { delay(80) }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    } ?: throw VisitMismatch("当前界面持续不可读取，请查看后继续")
}
