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
    fun submissionPrepared(tree: UiTree, node: UiNode): Boolean = true
    fun discardSubmit() = Unit
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
            if (pkg != target) throw VisitReadPending("等待目标应用页面恢复")
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
            var current = service.rootInActiveWindow ?: throw VisitPreActionMismatch("等待目标应用页面恢复")
            try {
                if (current.packageName?.toString() != target) throw VisitPreActionMismatch("等待目标应用页面恢复")
                for (index in node.path.split('/').drop(1).map { it.toInt() }) {
                    val child = current.getChild(index) ?: throw VisitPreActionMismatch("等待目标控件恢复")
                    current.recycle(); current = child
                }
                val rect = Rect().also { current.getBoundsInScreen(it) }
                if (!current.isVisibleToUser || !current.isEnabled || rect.isEmpty ||
                    action == AccessibilityNodeInfo.ACTION_SET_TEXT && !current.isEditable)
                    throw VisitPreActionMismatch("等待目标控件恢复")
                currentCoroutineContext().ensureActive()
                if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                    dispatchVisitClick(navigation, {
                        val accepted = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (accepted) dispatched = true
                        accepted
                    }) {
                        val complete = withTimeoutOrNull(2_000) {
                            suspendCancellableCoroutine<Boolean> { continuation ->
                                val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(
                                    Path().apply { moveTo(rect.exactCenterX(), rect.exactCenterY()) }, 0, 50)).build()
                                val callback = object : AccessibilityService.GestureResultCallback() {
                                    override fun onCompleted(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(true) }
                                    override fun onCancelled(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(false) }
                                }
                                val at = SystemClock.elapsedRealtime()
                                val accepted = service.dispatchGesture(gesture, callback, null)
                                dispatched = accepted
                                lastClickDispatchMs = if (accepted) at else null
                                if (!accepted && continuation.isActive) continuation.resume(false)
                            }
                        } ?: false
                        if (!complete) {
                            if (!dispatched) throw VisitPreActionMismatch("等待目标控件恢复")
                            throw VisitMismatch("本次点击未确认完成，已暂停且不会自动重试")
                        }
                    }
                } else {
                    val args = value?.let { Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, it) } }
                    if (!current.performAction(action, args)) throw VisitPreActionMismatch("等待目标控件恢复")
                    dispatched = true
                }
            } finally { current.recycle() }
        } catch (error: VisitMismatch) {
            if (!dispatched) throw VisitPreActionMismatch(error.reason)
            throw error
        }
    }

}

/** Never fall back after an accepted click, especially not for submission. */
internal suspend fun dispatchVisitClick(navigation: Boolean, nodeClick: () -> Boolean, gesture: suspend () -> Unit) {
    if (navigation) {
        if (!nodeClick()) gesture()
    } else gesture()
}
