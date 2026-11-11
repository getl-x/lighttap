package cn.lighttap.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Only observations are retried. Actions (especially submit) must never be retried here. */
class VisitObservation(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val diagnostics: VisitDiagnostics = VisitDiagnostics.NONE,
    private val equivalent: (UiTree, UiTree) -> Boolean = { a, b -> a == b },
    private val capture: suspend () -> UiTree,
) {
    suspend fun stable(initial: UiTree? = null): UiTree {
        val started = now()
        var attempts = 0
        val result = withTimeoutOrNull(STABLE_TIMEOUT_MS) {
            var previous: UiTree? = initial
            // A just-read seed counts as the first observation, never as both.
            if (initial != null) delay(80)
            while (true) {
                attempts++
                val current = try { capture() } catch (_: VisitReadPending) { null }
                val same = current != null && previous?.let { equivalent(it, current) } == true
                diagnostics.record(VisitDebugEvent(VisitDebugKind.STABILITY, attempt = attempts,
                    durationMs = now() - started, waitMs = STABLE_TIMEOUT_MS, nodes = current?.nodes?.size,
                    ok = same, delta = if (current != null && previous != null) snapshotDelta(previous, current) else null))
                if (same) return@withTimeoutOrNull current
                previous = current
                delay(80)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
        return result ?: run {
            diagnostics.record(VisitDebugEvent(VisitDebugKind.STABILITY, attempt = attempts,
                durationMs = now() - started, waitMs = STABLE_TIMEOUT_MS, ok = false,
                reason = "页面持续变化或暂不可读取，请查看后继续"))
            throw VisitMismatch("页面持续变化或暂不可读取，请查看后继续")
        }
    }

    suspend fun read(): UiTree = withTimeoutOrNull(3_000) {
        while (true) {
            try { return@withTimeoutOrNull capture() } catch (_: VisitReadPending) { delay(80) }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    } ?: throw VisitMismatch("当前界面持续不可读取，请查看后继续")
    companion object {
        // Includes full Binder/WebView traversals. On the target phone a single
        // read can exceed 1.2s; one changed sample exhausted the former 3s budget
        // before a third read could finish. Still require two identical trees.
        const val STABLE_TIMEOUT_MS = 8_000L
    }
}

/** Counts only: paths, text, bounds and business values never enter the log. */
data class VisitSnapshotDelta(val added: Int, val removed: Int, val text: Int, val description: Int,
    val geometry: Int, val controls: Int, val structure: Int, val windowChanged: Boolean)

internal fun snapshotDelta(before: UiTree, after: UiTree): VisitSnapshotDelta {
    val old = before.nodes.associateBy { it.path }
    val current = after.nodes.associateBy { it.path }
    val common = old.keys.intersect(current.keys)
    fun count(change: (UiNode, UiNode) -> Boolean) = common.count { change(old.getValue(it), current.getValue(it)) }
    return VisitSnapshotDelta(current.keys.count { it !in old }, old.keys.count { it !in current },
        count { a, b -> a.text != b.text }, count { a, b -> a.description != b.description },
        count { a, b -> a.bounds != b.bounds },
        count { a, b -> a.visible != b.visible || a.enabled != b.enabled || a.clickable != b.clickable ||
            a.editable != b.editable || a.selected != b.selected || a.checked != b.checked ||
            a.scrollable != b.scrollable || a.showingHint != b.showingHint },
        count { a, b -> a.className != b.className || a.viewId != b.viewId },
        before.windowId != after.windowId || before.packageName != after.packageName)
}
