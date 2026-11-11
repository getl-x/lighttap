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
    suspend fun stable(initial: UiTree? = null, validate: (UiTree) -> Unit = {}): UiTree {
        val started = now()
        var attempts = 0
        var lastProblem: String? = null
        val result = withTimeoutOrNull(STABLE_TIMEOUT_MS) {
            var previous: UiTree? = initial?.takeIf {
                try { validate(it); true } catch (error: VisitMismatch) {
                    if (!transientVisitRead(error)) throw error
                    lastProblem = error.reason; false
                }
            }
            // A just-read seed counts as the first observation, never as both.
            if (initial != null) delay(80)
            while (true) {
                attempts++
                val current = try {
                    capture().also { validate(it); lastProblem = null }
                } catch (error: VisitMismatch) {
                    if (!transientVisitRead(error)) throw error
                    lastProblem = error.reason; null
                }
                val same = current != null && previous?.let { equivalent(it, current) } == true
                diagnostics.record(VisitDebugEvent(VisitDebugKind.STABILITY, attempt = attempts,
                    durationMs = now() - started, waitMs = STABLE_TIMEOUT_MS, nodes = current?.nodes?.size,
                    ok = same, reason = lastProblem, delta = if (current != null && previous != null) snapshotDelta(previous, current) else null))
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
            throw VisitMismatch(lastProblem ?: "页面持续变化或暂不可读取，请查看后继续")
        }
    }

    suspend fun read(validate: (UiTree) -> Unit = {}): UiTree = withTimeoutOrNull(3_000) {
        while (true) {
            try { return@withTimeoutOrNull capture().also(validate) } catch (error: VisitMismatch) {
                if (!transientVisitRead(error)) throw error
                delay(80)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    } ?: throw VisitMismatch("当前界面持续不可读取，请查看后继续")
    companion object {
        // Includes full Binder/WebView traversals. On the target phone a single
        // read can exceed 1.2s; one changed sample exhausted the former 3s budget
        // before a third read could finish. Still require two equivalent reads.
        const val STABLE_TIMEOUT_MS = 8_000L
    }
}

/** Only incomplete observations are retried here. Confirmed identity/value
 * conflicts, abnormal choices, foreground changes and action results propagate. */
internal fun transientVisitRead(error: VisitMismatch): Boolean = error is VisitReadPending ||
    error.reason in setOf("未识别到唯一待办页签", "列表页结构不匹配", "无法确认当前选中待办页签",
        "列表仍在加载，请稍后由用户继续", "未读到走访控件，无法可靠识别网页内容", "卡片核对结构缺失",
        "表单仍在加载", "不是预期的新增表单", "无法唯一识别正常选项", "户籍状态结构不匹配",
        "状态控件缺失或不唯一", "提交按钮缺失或不唯一", "按钮不可见", "找不到明确可点击的按钮") ||
    listOf("检查地址", "走访描述", "姓名", "居住地址", "身份证").any { label ->
        error.reason == "字段标签缺失或不唯一：$label" || error.reason == "不能唯一关联输入框：$label" ||
            error.reason == "缺少或无法唯一确定记录核对字段：$label" || error.reason == "无法确定记录核对字段：$label"
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
