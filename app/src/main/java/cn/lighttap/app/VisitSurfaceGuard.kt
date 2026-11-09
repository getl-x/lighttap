package cn.lighttap.app

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Temporarily conceal our own windows only when they overlap a current target.
 * The owner handles Main-thread view changes; cancellation always restores them. */
class VisitSurfaceGuard(
    private val conceal: suspend (List<UiBox>) -> Boolean = { false },
    private val restore: suspend () -> Unit = {},
    private val settle: suspend () -> Unit = { delay(150) },
) {
    private val mutex = Mutex()
    suspend fun <T> clear(areas: List<UiBox>, operation: suspend () -> T): T = mutex.withLock {
        var changed = false
        var concealed = false
        try {
            // Conceal is a short window update. Deliver its result even if the
            // caller is cancelled during the Main dispatcher hop.
            changed = withContext(NonCancellable) { conceal(areas) }
            concealed = true
            if (changed) settle()
            currentCoroutineContext().ensureActive()
            operation()
        } finally { if (changed || !concealed) withContext(NonCancellable) { restore() } }
    }
}

internal class SurfaceVisitUiAccess(private val delegate: VisitUiAccess, private val surface: VisitSurfaceGuard) : VisitUiAccess {
    override val target get() = delegate.target
    override val lastClickDispatchMs get() = delegate.lastClickDispatchMs
    override suspend fun capture() = delegate.capture()
    override suspend fun prepareSubmit(tree: UiTree, node: UiNode) = delegate.prepareSubmit(tree, node)
    override fun submissionPrepared(tree: UiTree, node: UiNode) = delegate.submissionPrepared(tree, node)
    override fun discardSubmit() = delegate.discardSubmit()
    override suspend fun perform(tree: UiTree, node: UiNode, action: Int, value: String?) =
        surface.clear(listOf(node.bounds)) { delegate.perform(tree, node, action, value) }
    override suspend fun openVisit(tree: UiTree, node: UiNode) =
        surface.clear(listOf(node.bounds)) { delegate.openVisit(tree, node) }
}
