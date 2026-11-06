package cn.lighttap.app

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Rect
import android.os.Bundle
import android.os.PowerManager
import android.view.accessibility.AccessibilityNodeInfo

class AndroidUiBridge(private val service: AccessibilityService, val target: String, private val overlayBounds: () -> UiBox?) {
    private fun guard() {
        if (service.getSystemService(KeyguardManager::class.java).isKeyguardLocked || !service.getSystemService(PowerManager::class.java).isInteractive) throw VisitMismatch("屏幕锁定或关闭，任务已暂停")
    }
    @Suppress("DEPRECATION")
    fun capture(): UiTree {
        guard()
        val root = service.rootInActiveWindow ?: throw VisitMismatch("当前界面不可读取")
        try {
            if (!root.refresh()) throw VisitMismatch("当前窗口快照已失效")
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg != target) throw VisitMismatch("当前前台不是已绑定的目标应用")
            val nodes = mutableListOf<UiNode>()
            fun walk(node: AccessibilityNodeInfo, path: String, depth: Int) {
                if (depth > 60 || nodes.size >= 6000) throw VisitMismatch("界面结构过于复杂，无法完整核验")
                val rect = Rect(); node.getBoundsInScreen(rect)
                nodes += UiNode(path, node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty(), node.className?.toString().orEmpty(), node.viewIdResourceName.orEmpty(), node.isVisibleToUser, node.isEnabled, node.isClickable, node.isEditable, node.isSelected, node.isChecked, node.isScrollable, node.isShowingHintText, UiBox(rect.left, rect.top, rect.right, rect.bottom))
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: throw VisitMismatch("界面结构正在变化")
                    try { walk(child, "$path/$i", depth + 1) } finally { child.recycle() }
                }
            }
            walk(root, "r", 0)
            return UiTree(pkg, root.windowId, nodes)
        } finally { root.recycle() }
    }
    @Suppress("DEPRECATION")
    suspend fun perform(tree: UiTree, node: UiNode, action: Int, value: String? = null) {
        guard()
        val fresh = capture()
        if (fresh != tree) throw VisitMismatch("执行前页面发生变化，已暂停")
        if (!node.visible || !node.enabled || !node.bounds.nonEmpty) throw VisitMismatch("目标控件不可操作")
        if (action != AccessibilityNodeInfo.ACTION_SCROLL_FORWARD && overlayBounds()?.intersects(node.bounds) == true) throw VisitMismatch("控制条遮挡目标控件，请移动后继续")
        var current = service.rootInActiveWindow ?: throw VisitMismatch("执行前界面不可读")
        try {
            if (current.packageName?.toString() != target || current.windowId != tree.windowId) throw VisitMismatch("执行前目标窗口已变化")
            val indices = node.path.split('/').drop(1).map { it.toInt() }
            for (index in indices) {
                val child = current.getChild(index) ?: throw VisitMismatch("目标控件已失效")
                current.recycle(); current = child
            }
            if (current.text?.toString().orEmpty() != node.text || current.viewIdResourceName.orEmpty() != node.viewId || current.isEditable != node.editable) throw VisitMismatch("执行前控件内容已变化")
            val args = value?.let { Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, it) } }
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
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
                        if (!service.dispatchGesture(gesture, callback, null) && continuation.isActive) continuation.resume(false)
                    }
                } ?: false
                if (!completed) throw VisitMismatch("本次点击未确认完成，已暂停且不会自动重试")
            } else if (!current.performAction(action, args)) throw VisitMismatch("系统未确认本次控件操作，已暂停")
        } finally { current.recycle() }
    }
}