package cn.lighttap.app

/** Locate only the control needed now. Business identities, choice styles and
 * unrelated page content do not participate in execution. */
internal object DirectVisitControls {
    fun button(tree: UiTree, label: String): UiNode? {
        val text = tree.exact(label, true).filter { it.enabled }.minByOrNull { it.bounds.top } ?: return null
        return tree.ancestors(text).firstOrNull { it.visible && it.enabled && it.clickable && it.bounds.nonEmpty } ?: text
    }
    fun field(tree: UiTree, label: String): UiNode? {
        val associated = try { VisitContract(tree.packageName).field(tree, label) } catch (_: VisitMismatch) { null }
        if (associated != null && associated.enabled) return associated
        return tree.nodes.firstOrNull { it.editable && it.enabled &&
            (it.description.contains(label) || it.showingHint && it.text.contains(label)) }
    }
    fun pendingPage(tree: UiTree): Boolean = tree.exact("待办", true).isNotEmpty() && !tree.has("新增") &&
        !VisitContract(tree.packageName).loading(tree)
    fun empty(tree: UiTree): Boolean = tree.nodes.any { it.visible && it.readableText.trim() in setOf("暂无数据", "暂无待办", "暂无待办记录") }
}
