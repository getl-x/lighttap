package cn.lighttap.app

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** OCR text stays in memory and never enters diagnostics or an exception. */
internal class ImageText(val text: String, val confidence: Float, val bounds: UiBox) {
    override fun toString() = "ImageText([private])"
}
internal data class ImageClickEvidence(val box: UiBox, val capturedAt: Long, val width: Int, val height: Int,
                                       val clearList: Boolean, val normal: Boolean)
internal class PreparedImageClick(val request: UiTree, val requestedNode: UiNode,
    val observed: UiTree, val observedNode: UiNode, val evidence: ImageClickEvidence, val navigation: Boolean) {
    var consumed = false
        private set
    fun consume() { check(!consumed) { "Image plan already consumed" }; consumed = true }
}

internal object ImageClickLocator {
    const val MAX_AGE_MS = 5_000L
    fun locate(lines: List<ImageText>, label: String, button: UiBox, width: Int, height: Int): UiBox {
        if (label !in setOf("走访", "提交") || !button.nonEmpty || button.left < 0 || button.top < 0 ||
            button.right > width || button.bottom > height) throw VisitMismatch("图像目标控件不完整可见，已暂停")
        fun inside(box: UiBox) = box.nonEmpty && box.left >= button.left && box.top >= button.top &&
            box.right <= button.right && box.bottom <= button.bottom
        val candidates = lines.filter { inside(it.bounds) && canonicalLabel(it.text) == label }
        // Even a low-confidence second candidate is ambiguous, rather than an
        // excuse to choose the first high-confidence match.
        if (candidates.size != 1 || !candidates.single().confidence.isFinite() || candidates.single().confidence < .90f)
            throw VisitMismatch("图像按钮识别不唯一或置信度不足，已暂停")
        return candidates.single().bounds
    }
    fun validate(evidence: ImageClickEvidence, button: UiBox, navigation: Boolean, now: Long) {
        val box = evidence.box
        if (now < evidence.capturedAt || now - evidence.capturedAt > MAX_AGE_MS)
            throw VisitMismatch("图像采样已过期，请重新识别")
        if (!box.nonEmpty || box.left < 0 || box.top < 0 || box.right > evidence.width || box.bottom > evidence.height ||
            box.left < button.left || box.top < button.top || box.right > button.right || box.bottom > button.bottom)
            throw VisitMismatch("图像定位与当前按钮不一致，已暂停")
        if (navigation && !evidence.clearList || !navigation && !evidence.normal)
            throw VisitMismatch("图像页面状态无法确认，已暂停")
    }
}

/** Reacquire by meaning, with the existing exact record/value rules. Unrelated
 * countdown text may change during OCR; identity, window and target may not. */
internal class ImageClickGuard(target: String) {
    private val contract = VisitContract(target)
    fun reacquire(before: UiTree, expected: UiNode, after: UiTree, navigation: Boolean): UiNode {
        contract.target(before); contract.target(after)
        if (before.windowId != after.windowId) throw VisitMismatch("图像识别期间目标窗口变化，已暂停")
        // A newly published dialog/overlay or changed control geometry is not
        // an unrelated text refresh. Never click through such a transition.
        if (before.nodes.map { it.copy(text = "", description = "") } != after.nodes.map { it.copy(text = "", description = "") })
            throw VisitMismatch("图像识别期间页面结构变化，已暂停")
        val original: UiNode
        val current: UiNode
        if (navigation) {
            val old = contract.top(before)
            val fresh = contract.top(after)
            if (old.fingerprint != fresh.fingerprint) throw VisitMismatch("顶部记录变化，禁止点击")
            original = before.node(old.actionPath); current = after.node(fresh.actionPath)
        } else {
            contract.form(before, null, visualFallback = true); contract.verifyValues(before)
            contract.form(after, null, visualFallback = true); contract.verifyValues(after)
            if (contract.formFingerprint(before) != contract.formFingerprint(after))
                throw VisitMismatch("表单记录与所选卡片不一致")
            original = contract.submit(before); current = contract.submit(after)
        }
        if (expected != original || !current.visible || !current.enabled || !current.bounds.nonEmpty)
            throw VisitMismatch("图像定位与当前按钮不一致，已暂停")
        return current
    }
}

/** One OCR attempt, one possible gesture. No accepted/uncertain action ever
 * falls back to a node click, another engine, or a second gesture. */
internal class ImageClickFlow(
    private val native: VisitUiAccess,
    private val read: suspend (UiTree, UiNode, Boolean) -> ImageClickEvidence,
    private val tap: suspend (UiTree, UiBox) -> Unit,
    private val now: () -> Long,
) {
    private val guard = ImageClickGuard(native.target)
    suspend fun prepare(tree: UiTree, node: UiNode, navigation: Boolean): PreparedImageClick {
        try {
            val before = native.capture()
            val current = guard.reacquire(tree, node, before, navigation)
            val evidence = read(before, current, navigation)
            ImageClickLocator.validate(evidence, current.bounds, navigation, now())
            currentCoroutineContext().ensureActive()
            return PreparedImageClick(tree, node, before, current, evidence, navigation)
        } catch (error: VisitMismatch) { throw VisitPreActionMismatch(error.reason) }
    }
    suspend fun dispatch(plan: PreparedImageClick) {
        plan.consume()
        var dispatched = false
        try {
            currentCoroutineContext().ensureActive()
            val after = native.capture()
            val final = guard.reacquire(plan.observed, plan.observedNode, after, plan.navigation)
            if (final.bounds != plan.observedNode.bounds) throw VisitMismatch("图像识别期间按钮移动，已暂停")
            ImageClickLocator.validate(plan.evidence, final.bounds, plan.navigation, now())
            currentCoroutineContext().ensureActive()
            dispatched = true
            tap(after, plan.evidence.box)
        } catch (error: VisitMismatch) {
            if (!dispatched) throw VisitPreActionMismatch(error.reason)
            throw error
        }
    }
    suspend fun click(tree: UiTree, node: UiNode, navigation: Boolean) = dispatch(prepare(tree, node, navigation))
}

/** The setting is locked while a task is active; switching engines requires a
 * stopped task. Both delegates share the same runner and submission fence. */
internal class SelectedVisitUiAccess(private val selected: () -> VisitRecognition,
    private val controls: VisitUiAccess, private val images: VisitUiAccess) : VisitUiAccess {
    private val active get() = if (selected() == VisitRecognition.IMAGE) images else controls
    override val target get() = controls.target
    override val lastClickDispatchMs get() = active.lastClickDispatchMs
    override suspend fun capture() = active.capture()
    override suspend fun prepareSubmit(tree: UiTree, node: UiNode) = active.prepareSubmit(tree, node)
    override suspend fun perform(tree: UiTree, node: UiNode, action: Int, value: String?) = active.perform(tree, node, action, value)
    override suspend fun openVisit(tree: UiTree, node: UiNode) = active.openVisit(tree, node)
}
