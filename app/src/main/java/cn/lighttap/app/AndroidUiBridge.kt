package cn.lighttap.app

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

interface VisitUiAccess {
    val target: String
    val lastClickDispatchMs: Long? get() = null
    suspend fun capture(): UiTree
    /** Read-only preparation, before the runner writes its submission fence. */
    suspend fun prepareSubmit(tree: UiTree, node: UiNode) = Unit
    suspend fun perform(tree: UiTree, node: UiNode, action: Int, value: String? = null)
    /** Navigation is distinct from submission: it may use the node's own click
     * action without changing the single gesture used to submit. */
    suspend fun openVisit(tree: UiTree, node: UiNode) = perform(tree, node, AccessibilityNodeInfo.ACTION_CLICK)
}

class AndroidUiBridge(private val service: AccessibilityService, override val target: String, private val overlayBounds: () -> UiBox?) : VisitUiAccess {
    override var lastClickDispatchMs: Long? = null
        private set
    private val reader = Dispatchers.IO.limitedParallelism(1)
    private val prefetch = if (Build.VERSION.SDK_INT >= 33) AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST or AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE else 0
    private fun guard() {
        if (service.getSystemService(KeyguardManager::class.java).isKeyguardLocked || !service.getSystemService(PowerManager::class.java).isInteractive) throw VisitMismatch("屏幕锁定或关闭，任务已暂停")
    }
    @Suppress("DEPRECATION")
    override suspend fun capture(): UiTree = withContext(reader) { captureFresh() }
    @Suppress("DEPRECATION")
    private suspend fun captureFresh(): UiTree {
        currentCoroutineContext().ensureActive()
        guard()
        if (Build.VERSION.SDK_INT >= 33) service.clearCache()
        val root = (if (Build.VERSION.SDK_INT >= 33) service.getRootInActiveWindow(prefetch) else service.rootInActiveWindow)
            ?: throw VisitReadPending("当前界面不可读取")
        try {
            if (Build.VERSION.SDK_INT < 33 && !root.refresh()) throw VisitReadPending("当前窗口快照已失效")
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg != target) throw VisitMismatch("当前前台不是已绑定的目标应用")
            val nodes = mutableListOf<UiNode>()
            val job = currentCoroutineContext()
            fun walk(node: AccessibilityNodeInfo, path: String, depth: Int) {
                job.ensureActive()
                if (depth > 60 || nodes.size >= 6000) throw VisitMismatch("界面结构过于复杂，无法完整核验")
                val rect = Rect(); node.getBoundsInScreen(rect)
                nodes += UiNode(path, node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty(), node.className?.toString().orEmpty(), node.viewIdResourceName.orEmpty(), node.isVisibleToUser, node.isEnabled, node.isClickable, node.isEditable, node.isSelected, node.isChecked, node.isScrollable, node.isShowingHintText, UiBox(rect.left, rect.top, rect.right, rect.bottom))
                for (i in 0 until node.childCount) {
                    val child = (if (Build.VERSION.SDK_INT >= 33) node.getChild(i, prefetch) else node.getChild(i))
                        ?: throw VisitReadPending("界面结构正在变化")
                    try { walk(child, "$path/$i", depth + 1) } finally { child.recycle() }
                }
            }
            walk(root, "r", 0)
            return UiTree(pkg, root.windowId, withoutRedundantTextLeaves(nodes))
        } finally { root.recycle() }
    }
    @Suppress("DEPRECATION")
    override suspend fun perform(tree: UiTree, node: UiNode, action: Int, value: String?) = performFresh(tree, node, action, value, false)
    override suspend fun openVisit(tree: UiTree, node: UiNode) = performFresh(tree, node, AccessibilityNodeInfo.ACTION_CLICK, null, true)
    @Suppress("DEPRECATION")
    private suspend fun performFresh(tree: UiTree, node: UiNode, action: Int, value: String?, navigation: Boolean) = withContext(reader) {
        var dispatched = false
        try {
        guard()
        val fresh = captureFresh()
        if (fresh != tree) throw VisitMismatch("执行前页面发生变化，已暂停")
        if (!node.visible || !node.enabled || !node.bounds.nonEmpty) throw VisitMismatch("目标控件不可操作")
        val panel = withContext(Dispatchers.Main.immediate) { overlayBounds() }
        val notice = withContext(Dispatchers.Main.immediate) {
            (service as? ClickAccessibilityService)?.appModel?.privateModel?.controller?.workReminderBounds()
        }
        if (action != AccessibilityNodeInfo.ACTION_SCROLL_FORWARD && (panel?.intersects(node.bounds) == true || notice?.intersects(node.bounds) == true)) throw VisitMismatch("悬浮内容遮挡目标控件，请移开或收起后继续")
        var current = service.rootInActiveWindow ?: throw VisitMismatch("执行前界面不可读")
        try {
            if (current.packageName?.toString() != target || current.windowId != tree.windowId) throw VisitMismatch("执行前目标窗口已变化")
            val indices = node.path.split('/').drop(1).map { it.toInt() }
            for (index in indices) {
                val child = current.getChild(index) ?: throw VisitMismatch("目标控件已失效")
                current.recycle(); current = child
            }
            if (current.text?.toString().orEmpty() != node.text || current.contentDescription?.toString().orEmpty() != node.description || current.viewIdResourceName.orEmpty() != node.viewId || current.isEditable != node.editable) throw VisitMismatch("执行前控件内容已变化")
            val bounds = Rect().also { current.getBoundsInScreen(it) }
            if (!current.isVisibleToUser || !current.isEnabled || UiBox(bounds.left, bounds.top, bounds.right, bounds.bottom) != node.bounds) throw VisitMismatch("执行前控件位置或状态已变化")
            currentCoroutineContext().ensureActive()
            val args = value?.let { Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, it) } }
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                dispatched = true
                dispatchVisitClick(navigation, { current.performAction(AccessibilityNodeInfo.ACTION_CLICK) }) {
                    // Touch the fresh, exactly matched node bounds; never use preset coordinates
                    // and never retry an accepted action with another action type.
                    val x = (node.bounds.left + node.bounds.right) / 2f
                    val y = (node.bounds.top + node.bounds.bottom) / 2f
                    val completed = withTimeoutOrNull(2_000) {
                        suspendCancellableCoroutine<Boolean> { continuation ->
                            val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, 50)).build()
                            val callback = object : AccessibilityService.GestureResultCallback() {
                                override fun onCompleted(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(true) }
                                override fun onCancelled(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(false) }
                            }
                            val dispatchedAt = SystemClock.elapsedRealtime()
                            val accepted = service.dispatchGesture(gesture, callback, null)
                            lastClickDispatchMs = if (accepted) dispatchedAt else null
                            if (!accepted && continuation.isActive) continuation.resume(false)
                        }
                    } ?: false
                    if (!completed) throw VisitMismatch("本次点击未确认完成，已暂停且不会自动重试")
                }
            } else {
                dispatched = true
                if (!current.performAction(action, args)) throw VisitMismatch("系统未确认本次控件操作，已暂停")
            }
        } finally { current.recycle() }
        } catch (e: VisitMismatch) {
            if (!dispatched) throw VisitPreActionMismatch(e.reason)
            throw e
        }
    }
}

/** Never fall back after an accepted click, especially not for submission. */
internal suspend fun dispatchVisitClick(navigation: Boolean, nodeClick: () -> Boolean, gesture: suspend () -> Unit) {
    if (navigation) {
        if (!nodeClick()) throw VisitNavigationRejected()
    } else gesture()
}
