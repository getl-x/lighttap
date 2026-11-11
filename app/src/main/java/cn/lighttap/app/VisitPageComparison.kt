package cn.lighttap.app

/** Compare the current task, not every piece of text published by the WebView.
 * Unknown pages still require equality. Target geometry remains current, and
 * new interactive surfaces (such as dialog buttons) invalidate an action. */
internal fun sameVisitPage(before: UiTree, after: UiTree): Boolean {
    if (before == after) return true
    if (before.packageName != after.packageName || before.windowId != after.windowId) return false
    val oldKey = businessKey(before) ?: return false
    val newKey = businessKey(after) ?: return false
    return oldKey == newKey
}

/** Class/view IDs, descriptions and wrapper rectangles can be republished
 * independently. Preserve the facts used to operate this control. */
private fun controlKey(node: UiNode) = listOf(node.path, node.bounds, node.visible, node.enabled,
    node.clickable, node.editable, node.selected, node.checked)

private fun businessKey(tree: UiTree): List<Any>? = try {
    val contract = VisitContract(tree.packageName)
    if (contract.loading(tree)) null
    else if (tree.has("新增")) {
        contract.formIdentity(tree, null)
        val address = contract.field(tree, "检查地址")
        val description = contract.field(tree, "走访描述")
        val normal = contract.choiceButton(tree, "正常")
        val abnormal = contract.choiceButton(tree, "异常")
        val submit = contract.submit(tree)
        val controls = listOf(address, description, normal, abnormal, submit)
        listOf("form", contract.formFingerprint(tree), contract.value(address), contract.value(description),
            contract.normalState(tree), controls.map(::controlKey), otherControls(tree, controls.map { it.path }))
    } else {
        // Bottom rows may refresh or be partially exposed. Validate them when
        // selected, rather than invalidating the current top record's click.
        val records = contract.topRecords(tree)
        val known = listOf("待办", "已完成", "走访", "走访记录").flatMap { label ->
            tree.exact(label).flatMap { tree.controlAncestors(it, setOf("待办", "已完成", "走访", "走访记录")) }
        }.map { it.path }
        listOf("list", records.map { listOf(it.fingerprint, controlKey(tree.node(it.actionPath))) }, otherControls(tree, known))
    }
} catch (_: VisitMismatch) { null }

private fun otherControls(tree: UiTree, known: List<String>): List<Any> = tree.nodes.asSequence()
    .filter { node -> node.visible && (node.clickable || node.editable || node.selected || node.checked ||
        node.className.endsWith("Dialog")) && known.none { node.path == it || node.path.startsWith("$it/") } }
    .map { listOf(controlKey(it), canonicalLabel(it.readableText)) }.toList()
