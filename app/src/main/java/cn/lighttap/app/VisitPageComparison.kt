package cn.lighttap.app

/** Allow noninteractive text changes in a known form. Require the same
 * business fields and interactive controls, including any newly shown dialog. */
internal fun sameVisitPage(before: UiTree, after: UiTree): Boolean {
    if (before == after) return true
    if (before.packageName != after.packageName || before.windowId != after.windowId) return false
    if (before.has("新增") || after.has("新增")) return sameVisitForm(before, after)
    val oldKey = listKey(before) ?: return false
    val newKey = listKey(after) ?: return false
    return oldKey == newKey && safetyNodes(before) == safetyNodes(after)
}

private fun sameVisitForm(before: UiTree, after: UiTree): Boolean = try {
    val contract = VisitContract(before.packageName)
    fun controls(tree: UiTree) = tree.nodes.filter {
        it.editable || it.clickable || it.scrollable || it.selected || it.checked
    }
    fun fields(tree: UiTree) = listOf(contract.field(tree, "检查地址"), contract.field(tree, "走访描述"))
    contract.formEvidence(before).matches(contract.formEvidence(after)) &&
        contract.normalState(before) == contract.normalState(after) &&
        fields(before) == fields(after) && controls(before) == controls(after)
} catch (_: VisitMismatch) { false }

private fun listKey(tree: UiTree): List<Any>? = try {
    val contract = VisitContract(tree.packageName)
    if (contract.loading(tree)) null
    else {
        val records = contract.records(tree)
        listOf("list", records.map { listOf(it.fingerprint, it.cardPath, it.actionPath, tree.node(it.actionPath)) })
    }
} catch (_: VisitMismatch) { null }

private val countdownDecoration = Regex("倒计时[0-9]{1,5}天")
private fun safetyNodes(tree: UiTree): List<UiNode> = tree.nodes.asSequence()
    .filter { it.visible || it.editable || it.clickable || it.selected || it.checked || it.scrollable }
    .map { node ->
        if (!node.editable && !node.clickable && !node.selected && !node.checked && !node.scrollable &&
            countdownDecoration.matches(canonicalLabel(node.readableText)) &&
            (node.description.isBlank() || canonicalLabel(node.description) == canonicalLabel(node.text)) &&
            tree.under(node.path).size == 1)
            node.copy(text = "[countdown]", description = "")
        else node
    }.toList()
