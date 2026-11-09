package cn.lighttap.app

import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CancellationException

/** Diagnostic wrapper never receives a serializable representation of a node. */
class DiagnosedVisitUiAccess(
    private val delegate: VisitUiAccess,
    private val diagnostics: VisitDiagnostics,
    private val now: () -> Long,
) : VisitUiAccess {
    override val target get() = delegate.target
    override val lastClickDispatchMs get() = delegate.lastClickDispatchMs
    override suspend fun capture(): UiTree {
        val start = now()
        try {
            val tree = delegate.capture()
            val tabs = tree.exact("待办", true)
            diagnostics.record(VisitDebugEvent(VisitDebugKind.CAPTURE, durationMs = now() - start,
                nodes = tree.nodes.size, window = tree.windowId, targetMatches = tree.packageName == target,
                pendingTabs = tabs.size, pendingSelected = tabs.singleOrNull()?.let { tab -> tree.ancestors(tab).take(3).any { it.selected || it.checked } },
                form = tree.has("新增"), loading = VisitContract(target).loading(tree), ok = true))
            return tree
        } catch (e: Exception) {
            diagnostics.record(VisitDebugEvent(VisitDebugKind.CAPTURE, durationMs = now() - start, ok = false,
                reason = if (e is CancellationException) "操作已取消" else (e as? VisitMismatch)?.reason ?: "当前界面不可读取"))
            throw e
        }
    }
    override suspend fun perform(tree: UiTree, node: UiNode, action: Int, value: String?) {
        val start = now()
        val kind = when (action) {
            AccessibilityNodeInfo.ACTION_CLICK -> VisitDebugAction.CLICK
            AccessibilityNodeInfo.ACTION_SET_TEXT -> VisitDebugAction.FILL
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> VisitDebugAction.SCROLL
            else -> VisitDebugAction.OTHER
        }
        val submit = kind == VisitDebugAction.CLICK && canonicalLabel(node.text) == "提交"
        diagnostics.record(VisitDebugEvent(VisitDebugKind.ACTION, action = kind, submit = submit))
        try {
            delegate.perform(tree, node, action, value)
            diagnostics.record(VisitDebugEvent(VisitDebugKind.ACTION, action = kind, submit = submit, durationMs = now() - start, ok = true))
        } catch (e: Exception) {
            diagnostics.record(VisitDebugEvent(VisitDebugKind.ACTION, action = kind, submit = submit, durationMs = now() - start,
                ok = false, reason = if (e is CancellationException) "操作已取消" else (e as? VisitMismatch)?.reason ?: "当前步骤发生异常，已暂停；请查看页面"))
            throw e
        }
    }
    override suspend fun openVisit(tree: UiTree, node: UiNode) {
        val start = now()
        diagnostics.record(VisitDebugEvent(VisitDebugKind.ACTION, action = VisitDebugAction.CLICK, submit = false, navigation = true))
        try {
            delegate.openVisit(tree, node)
            diagnostics.record(VisitDebugEvent(VisitDebugKind.ACTION, action = VisitDebugAction.CLICK, submit = false,
                navigation = true, durationMs = now() - start, ok = true))
        } catch (e: Exception) {
            diagnostics.record(VisitDebugEvent(VisitDebugKind.ACTION, action = VisitDebugAction.CLICK, submit = false,
                navigation = true, durationMs = now() - start, ok = false,
                reason = if (e is CancellationException) "操作已取消" else (e as? VisitMismatch)?.reason ?: "当前步骤发生异常，已暂停；请查看页面"))
            throw e
        }
    }
}
