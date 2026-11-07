package cn.lighttap.app

import java.security.MessageDigest

data class UiBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val nonEmpty get() = right > left && bottom > top
    fun intersects(other: UiBox) = left < other.right && right > other.left && top < other.bottom && bottom > other.top
}
data class UiNode(
    val path: String,
    val text: String = "",
    val description: String = "",
    val className: String = "",
    val viewId: String = "",
    val visible: Boolean = true,
    val enabled: Boolean = true,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val selected: Boolean = false,
    val checked: Boolean = false,
    val scrollable: Boolean = false,
    val showingHint: Boolean = false,
    val bounds: UiBox = UiBox(0, 0, 1, 1),
)
data class UiTree(val packageName: String, val windowId: Int, val nodes: List<UiNode>) {
    private val byPath by lazy { nodes.associateBy { it.path } }
    private val subtrees = mutableMapOf<String, List<UiNode>>()
    private val labels = mutableMapOf<Pair<String, Boolean>, List<UiNode>>()
    fun under(path: String) = subtrees.getOrPut(path) { nodes.filter { it.path == path || it.path.startsWith("$path/") } }
    fun ancestors(node: UiNode): List<UiNode> {
        val result = mutableListOf<UiNode>()
        var path = node.path
        while (true) {
            byPath[path]?.let { result += it }
            if (!path.contains('/')) break
            path = path.substringBeforeLast('/')
        }
        return result
    }
    fun exact(label: String, visibleOnly: Boolean = false): List<UiNode> = labels.getOrPut(label to visibleOnly) {
        val candidates = nodes.filter { canonicalLabel(it.text) == label && (!visibleOnly || (it.visible && it.bounds.nonEmpty)) }
        candidates.filter { node -> candidates.none { other -> other.path.startsWith(node.path + "/") } }
    }
    fun has(label: String) = exact(label).isNotEmpty()
    fun node(path: String) = byPath[path] ?: throw VisitMismatch("页面控件已变化")
}
/** No record object overrides toString: error/status paths must never format business values. */
class VisitRecord(val name: String, val address: String, val cardPath: String, val actionPath: String) {
    val fingerprint: String = hashText(name + "\u0000" + address)
    override fun toString() = "VisitRecord([private])"
}
fun canonicalLabel(text: String) = text.filterNot { it.isWhitespace() }
fun hashText(text: String): String {
    val alphabet = "0123456789abcdef"
    val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
    return buildString(bytes.size * 2) { bytes.forEach { byte ->
        val value = byte.toInt() and 255
        append(alphabet[value ushr 4]); append(alphabet[value and 15])
    } }
}
/** WebView lazily exposes inline text leaves; they repeat their parent's text
 * and carry no independent control state. Keep the parent and its real bounds. */
fun withoutRedundantTextLeaves(nodes: List<UiNode>): List<UiNode> {
    val byPath = nodes.associateBy { it.path }
    val parents = nodes.map { it.path.substringBeforeLast('/', "") }.toSet()
    return nodes.filterNot { node ->
        val parent = byPath[node.path.substringBeforeLast('/', "")]
        parent != null && node.path !in parents && node.className == "android.widget.TextView" &&
            !node.clickable && !node.editable && !node.selected && !node.checked && !node.scrollable &&
            node.enabled == parent.enabled && node.visible == parent.visible && node.viewId.isEmpty() &&
            node.description.isEmpty() && node.text.isNotBlank() && canonicalLabel(node.text) == canonicalLabel(parent.text)
    }
}
class VisitContract(val target: String) {
    fun target(tree: UiTree) {
        if (tree.packageName != target) throw VisitMismatch("当前前台不是已绑定的目标应用")
        if (tree.nodes.isEmpty()) throw VisitMismatch("无法读取当前界面")
    }
    fun loading(tree: UiTree): Boolean = tree.nodes.any { it.visible && (it.className.endsWith("ProgressBar") || it.text.trim() in setOf("加载中", "加载中...", "加载中…", "正在加载")) }
    private fun leafTexts(tree: UiTree, path: String): List<UiNode> = tree.under(path).filter { n -> n.text.isNotBlank() && !n.editable && tree.nodes.none { it.path.startsWith(n.path + "/") && it.text.trim() == n.text.trim() } }
    private fun isLabel(text: String, label: String): Boolean = canonicalLabel(text).removeSuffix("：").removeSuffix(":") == label
    private fun labels(tree: UiTree, label: String): List<UiNode> {
        val nodes = tree.nodes.filter { isLabel(it.text, label) }
        return nodes.filter { node -> nodes.none { other -> other.path.startsWith(node.path + "/") } }
    }
    fun field(tree: UiTree, label: String): UiNode {
        val labelNode = labels(tree, label).singleOrNull() ?: throw VisitMismatch("字段标签缺失或不唯一：$label")
        val fieldLabels = setOf("检查地址", "走访描述", "居住地址", "姓名", "身份证")
        for (ancestor in tree.ancestors(labelNode).take(5)) {
            val nodes = tree.under(ancestor.path)
            val inputs = nodes.filter { it.editable }
            if (inputs.size == 1 && nodes.none { n -> fieldLabels.any { other -> other != label && isLabel(n.text, other) } }) return inputs.single()
            if (inputs.size > 1) {
                // The observed WebView flattens section headings and their inputs.
                val marker = nodes.indexOfFirst { it.path == labelNode.path }
                if (marker >= 0) {
                    val boundary = ((marker + 1) until nodes.size).firstOrNull { i -> fieldLabels.any { other -> other != label && isLabel(nodes[i].text, other) } } ?: nodes.size
                    val candidates = nodes.subList(marker + 1, boundary).filter { it.editable }
                    if (candidates.size == 1) return candidates.single()
                }
                break
            }
        }
        throw VisitMismatch("不能唯一关联输入框：$label")
    }
    fun value(field: UiNode) = if (field.showingHint) "" else field.text
    fun fieldValue(tree: UiTree, label: String) = value(field(tree, label))
    private fun staticValue(tree: UiTree, path: String, label: String): String {
        val nodes = tree.under(path)
        val prefix = Regex("^" + label.map { Regex.escape(it.toString()) }.joinToString("\\s*") + "\\s*[：:]\\s*(.+)$", RegexOption.DOT_MATCHES_ALL)
        val embedded = nodes.mapNotNull { prefix.matchEntire(it.text.trim())?.groupValues?.get(1)?.trim() }.distinct()
        if (embedded.size == 1) return embedded.single()
        if (embedded.size > 1) throw VisitMismatch("记录字段有歧义")
        val markers = nodes.filter { isLabel(it.text, label) }
        // Match the outer row label, not its optional inline-text child. The
        // adjacent value is a sibling of that outer label in flattened WebViews.
        val marker = markers.filter { node -> markers.none { other -> node.path.startsWith(other.path + "/") } }.singleOrNull()
            ?: throw VisitMismatch("缺少或无法唯一确定记录核对字段")
        // Web accessibility often flattens each label/value row into adjacent siblings.
        val siblingValues = nodes.filter { it.path.substringBeforeLast('/', "") == marker.path.substringBeforeLast('/', "") }
        val markerIndex = siblingValues.indexOfFirst { it.path == marker.path }
        if (markerIndex >= 0 && markerIndex + 1 < siblingValues.size) {
            val next = siblingValues[markerIndex + 1]
            val supportedLabels = setOf("姓名", "身份证", "居住地址", "检查地址", "走访描述")
            if (next.text.isNotBlank() && !next.editable && supportedLabels.none { isLabel(next.text, it) } && next.bounds.top == marker.bounds.top && next.bounds.left >= marker.bounds.right - 8) return next.text.trim()
        }
        for (ancestor in tree.ancestors(marker).take(4)) {
            if (!ancestor.path.startsWith(path)) break
            val leaves = leafTexts(tree, ancestor.path).filter { it.path != marker.path && !isLabel(it.text, label) }
            if (leaves.size == 1) return leaves.single().text.trim()
            if (leaves.size > 1) break
        }
        throw VisitMismatch("无法确定记录核对字段")
    }
    fun listSignature(tree: UiTree): String {
        val records = records(tree)
        return if (records.isEmpty()) "empty" else records.joinToString("|") { it.fingerprint }
    }
    fun records(tree: UiTree): List<VisitRecord> {
        target(tree)
        val pending = tree.exact("待办", true).singleOrNull() ?: throw VisitMismatch("未识别到唯一待办页签")
        if (!tree.has("已完成")) throw VisitMismatch("列表页结构不匹配")
        val selected = tree.ancestors(pending).take(3).any { it.selected || it.checked }
        if (!selected) throw VisitMismatch("无法确认当前选中待办页签")
        if (loading(tree)) throw VisitMismatch("列表仍在加载，请稍后由用户继续")
        val actions = tree.exact("走访", true)
        if (actions.isEmpty()) {
            if (tree.nodes.any { it.visible && it.text.trim() in setOf("暂无数据", "暂无待办", "暂无待办记录") }) return emptyList()
            throw VisitMismatch("未读到走访控件，无法可靠识别网页内容")
        }
        val result = actions.map { action ->
            fun visitCount(parent: UiNode) = tree.exact("走访").count { it.path == parent.path || it.path.startsWith(parent.path + "/") }
            fun historyCount(parent: UiNode) = tree.exact("走访记录").count { it.path == parent.path || it.path.startsWith(parent.path + "/") }
            val card = tree.ancestors(action).firstOrNull { parent ->
                val nodes = tree.under(parent.path)
                visitCount(parent) == 1 && historyCount(parent) == 1 && nodes.any { isLabel(it.text, "居住地址") || canonicalLabel(it.text).startsWith("居住地址：") || canonicalLabel(it.text).startsWith("居住地址:") }
            }
            val actionRow = tree.ancestors(action).firstOrNull { visitCount(it) == 1 && historyCount(it) == 1 }
                ?: throw VisitMismatch("走访按钮与记录按钮不能唯一关联")
            val scope: UiTree
            val cardPath: String
            val segmentFirst: UiNode?
            if (card != null) {
                scope = tree.copy(nodes = tree.under(card.path))
                cardPath = card.path
                segmentFirst = null
            } else {
                val parentPath = actionRow.path.substringBeforeLast('/', "")
                val parent = tree.node(parentPath)
                val siblings = tree.nodes.filter { it.path.substringBeforeLast('/', "") == parentPath && it.path != parentPath }
                val rowIndex = siblings.indexOfFirst { it.path == actionRow.path }
                if (rowIndex < 0) throw VisitMismatch("列表顺序结构不能确定")
                val previous = (0 until rowIndex).lastOrNull { index -> visitCount(siblings[index]) == 1 && historyCount(siblings[index]) == 1 } ?: -1
                val segment = siblings.subList(previous + 1, rowIndex + 1)
                val segmentNodes = segment.flatMap { tree.under(it.path) }
                if (tree.copy(nodes = segmentNodes).exact("走访").size != 1 || tree.copy(nodes = segmentNodes).exact("走访记录").size != 1) throw VisitMismatch("平铺记录边界有歧义")
                scope = tree.copy(nodes = listOf(parent) + segmentNodes)
                cardPath = actionRow.path
                segmentFirst = segment.firstOrNull()
            }
            val leaves = leafTexts(scope, scope.nodes.first().path)
            val progress = leaves.filter { canonicalLabel(it.text).matches(Regex("[0-9]+/[0-9]+")) }
            if (progress.size != 1 || canonicalLabel(progress.single().text) != "0/1") throw VisitMismatch("记录完成状态不是明确的 0/1")
            val address = staticValue(scope, scope.nodes.first().path, "居住地址")
            val label = leaves.filter { isLabel(it.text, "身份证") || canonicalLabel(it.text).startsWith("身份证：") || canonicalLabel(it.text).startsWith("身份证:") }.minByOrNull { it.bounds.top }
                ?: throw VisitMismatch("卡片核对结构缺失")
            val nameCandidates = leaves.filter { it.bounds.top < label.bounds.top && it.text.trim().matches(Regex("[\\p{L}·•]{1,40}")) && canonicalLabel(it.text) !in setOf("走访", "走访记录", "姓名") }
            val name = if (scope.nodes.any { isLabel(it.text, "姓名") }) staticValue(scope, scope.nodes.first().path, "姓名")
                else if (segmentFirst != null) {
                    val firstTexts = leafTexts(scope, segmentFirst.path)
                    val first = firstTexts.singleOrNull() ?: throw VisitMismatch("平铺卡片姓名不唯一")
                    if (first !in nameCandidates) throw VisitMismatch("平铺卡片姓名位置不匹配")
                    first.text.trim()
                } else nameCandidates.singleOrNull()?.text?.trim() ?: throw VisitMismatch("无法唯一读取记录姓名")
            VisitRecord(name, address, cardPath, clickable(tree, action, "走访").path)
        }.sortedBy { tree.node(it.cardPath).bounds.top }
        if (result.map { it.cardPath }.distinct().size != result.size || result.map { it.fingerprint }.distinct().size != result.size) throw VisitMismatch("列表记录存在歧义，不能选择")
        if (result.size > 1 && tree.node(result[0].cardPath).bounds.top == tree.node(result[1].cardPath).bounds.top) throw VisitMismatch("不能确定最上方记录")
        return result
    }
    fun top(tree: UiTree): VisitRecord = records(tree).firstOrNull() ?: throw VisitMismatch("暂无待办，请用户查看")
    fun form(tree: UiTree, record: VisitRecord?, visualFallback: Boolean = false) {
        target(tree)
        if (loading(tree)) throw VisitMismatch("表单仍在加载")
        if (!tree.has("新增") || !tree.has("走访记录")) throw VisitMismatch("不是预期的新增表单")
        field(tree, "检查地址"); field(tree, "走访描述")
        if (record != null) {
            if (staticValue(tree, "r", "姓名") != record.name || staticValue(tree, "r", "居住地址") != record.address) throw VisitMismatch("表单记录与所选卡片不一致")
        }
        if (visualFallback) {
            if (normalState(tree) == ChoiceResult.ABNORMAL) throw VisitMismatch("户籍状态明确异常，已暂停")
        } else normal(tree)
    }
    fun formFingerprint(tree: UiTree): String = hashText(staticValue(tree, "r", "姓名") + "\u0000" + staticValue(tree, "r", "居住地址"))
    fun normalState(tree: UiTree): ChoiceResult {
        val normal = tree.exact("正常").singleOrNull() ?: throw VisitMismatch("无法唯一识别正常选项")
        val abnormal = tree.exact("异常").singleOrNull() ?: throw VisitMismatch("户籍状态结构不匹配")
        fun chosen(n: UiNode) = tree.ancestors(n).take(2).any { it.selected || it.checked }
        if (chosen(normal) && chosen(abnormal)) throw VisitMismatch("状态选中标记矛盾，已暂停")
        return when {
            chosen(normal) && !chosen(abnormal) -> ChoiceResult.NORMAL
            chosen(abnormal) && !chosen(normal) -> ChoiceResult.ABNORMAL
            else -> ChoiceResult.UNKNOWN
        }
    }
    fun choiceButton(tree: UiTree, label: String): UiNode = clickable(tree, tree.exact(label).singleOrNull() ?: throw VisitMismatch("状态控件缺失或不唯一"), label)
    fun normal(tree: UiTree) { if (normalState(tree) != ChoiceResult.NORMAL) throw VisitMismatch("户籍状态异常或无法确认选中正常") }
    fun allowedFields(tree: UiTree) {
        val address = fieldValue(tree, "检查地址")
        val description = fieldValue(tree, "走访描述")
        if (address.isNotEmpty() && address != ADDRESS || description.isNotEmpty() && description != DESCRIPTION) throw VisitMismatch("已有字段内容冲突，已暂停且未覆盖")
    }
    fun verifyValues(tree: UiTree) {
        if (fieldValue(tree, "检查地址") != ADDRESS || fieldValue(tree, "走访描述") != DESCRIPTION) throw VisitMismatch("两字段回读校验不一致，禁止提交")
    }
    fun clickable(tree: UiTree, text: UiNode, expected: String): UiNode {
        if (!text.visible) throw VisitMismatch("按钮不可见")
        return tree.ancestors(text).take(4).firstOrNull { parent ->
            parent.clickable && parent.enabled && parent.visible && parent.bounds.nonEmpty && tree.under(parent.path).none { canonicalLabel(it.text) in setOf("走访", "走访记录", "提交") && canonicalLabel(it.text) != canonicalLabel(expected) }
        } ?: throw VisitMismatch("找不到明确可点击的按钮")
    }
    fun submit(tree: UiTree): UiNode {
        val labels = tree.exact("提交", true)
        val node = labels.singleOrNull() ?: throw VisitMismatch("提交按钮缺失或不唯一")
        return clickable(tree, node, node.text.trim())
    }
    companion object {
        const val ADDRESS = "松州园小区"
        const val DESCRIPTION = "无异常"
    }
}
