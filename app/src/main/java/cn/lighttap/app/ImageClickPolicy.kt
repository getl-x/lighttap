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
    }
}

/** OCR sees only the current button and a little surrounding context. Full
 * screenshots remain available for independent loading/selection checks. */
internal fun imageOcrRegion(button: UiBox, width: Int, height: Int): UiBox {
    if (!button.nonEmpty || button.left < 0 || button.top < 0 || button.right > width || button.bottom > height)
        throw VisitMismatch("图像目标控件不完整可见，已暂停")
    val padding = maxOf(8, minOf(button.right - button.left, button.bottom - button.top) / 8)
    return UiBox((button.left - padding).coerceAtLeast(0), (button.top - padding).coerceAtLeast(0),
        (button.right + padding).coerceAtMost(width), (button.bottom + padding).coerceAtMost(height))
}

/** One OCR attempt, one possible gesture. No accepted/uncertain action ever
 * falls back to a node click, another engine, or a second gesture. */
internal class ImageClickFlow(
    private val native: VisitUiAccess,
    private val read: suspend (UiTree, UiNode, Boolean) -> ImageClickEvidence,
    private val tap: suspend (UiTree, UiBox) -> Unit,
    private val now: () -> Long,
) {
    suspend fun prepare(tree: UiTree, node: UiNode, navigation: Boolean): PreparedImageClick {
        try {
            val label = if (navigation) "走访" else "提交"
            val readable = tree.under(node.path).any { !it.editable && canonicalLabel(it.readableText) == label }
            val screen = tree.nodes.firstOrNull()?.bounds
            val evidence = if (readable && screen != null && screen.right >= node.bounds.right && screen.bottom >= node.bounds.bottom)
                ImageClickEvidence(node.bounds, now(), screen.right, screen.bottom, true, true)
            else read(tree, node, navigation)
            ImageClickLocator.validate(evidence, node.bounds, navigation, now())
            currentCoroutineContext().ensureActive()
            return PreparedImageClick(tree, node, tree, node, evidence, navigation)
        } catch (error: VisitMismatch) { throw VisitPreActionMismatch(error.reason) }
    }
    suspend fun dispatch(plan: PreparedImageClick) {
        plan.consume()
        var dispatched = false
        try {
            currentCoroutineContext().ensureActive()
            ImageClickLocator.validate(plan.evidence, plan.observedNode.bounds, plan.navigation, now())
            currentCoroutineContext().ensureActive()
            dispatched = true
            tap(plan.observed, plan.evidence.box)
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
    override fun submissionPrepared(tree: UiTree, node: UiNode) = active.submissionPrepared(tree, node)
    override fun discardSubmit() { controls.discardSubmit(); images.discardSubmit() }
    override suspend fun perform(tree: UiTree, node: UiNode, action: Int, value: String?) = active.perform(tree, node, action, value)
    override suspend fun openVisit(tree: UiTree, node: UiNode) = active.openVisit(tree, node)
}
