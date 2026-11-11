package cn.lighttap.app

/** Keep business identity/state and every visible/interactive surface. Only
 * known noninteractive countdown text and hidden noninteractive decoration may
 * vary. Unknown pages/labels keep the original exact comparison. */
internal fun sameVisitPage(before: UiTree, after: UiTree): Boolean {
    if (before == after) return true
    if (before.packageName != after.packageName || before.windowId != after.windowId) return false
    val oldKey = businessKey(before) ?: return false
    val newKey = businessKey(after) ?: return false
    return oldKey == newKey && safetyNodes(before) == safetyNodes(after)
}

private fun businessKey(tree: UiTree): List<Any>? = try {
    val contract = VisitContract(tree.packageName)
    if (contract.loading(tree)) null
    else if (tree.has("新增")) {
        contract.formIdentity(tree, null)
        listOf("form", contract.formFingerprint(tree), contract.field(tree, "检查地址"), contract.field(tree, "走访描述"),
            contract.normalState(tree), contract.choiceButton(tree, "正常"), contract.choiceButton(tree, "异常"), contract.submit(tree))
    } else {
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
