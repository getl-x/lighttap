package cn.lighttap.app

import org.junit.Assert.*
import org.junit.Test

/** Synthetic UI rewrites, not evidence that an unreleased target version works. */
class VisitUpdateCompatibilityTest {
    private val contract = VisitContract("fixture.app")
    private fun control(path: String, label: String, selected: Boolean = false, clickable: Boolean = false): List<UiNode> {
        val nodes = mutableListOf(UiNode(path, selected = selected, clickable = clickable))
        var child = path
        repeat(7) { child += "/0"; nodes += UiNode(child) }
        nodes += UiNode("$child/0", text = label)
        return nodes
    }
    private fun field(path: String, label: String, value: String = ""): List<UiNode> =
        listOf(UiNode(path)) + control("$path/0", label) + control("$path/1", "").let { wrappers ->
            wrappers.dropLast(1) + wrappers.last().copy(text = value, editable = true)
        }
    private fun form(): UiTree = UiTree("fixture.app", 2,
        listOf(UiNode("r"), UiNode("r/0", text = "新增"), UiNode("r/1", text = "走访记录"),
            UiNode("r/2"), UiNode("r/2/0", text = "姓名"), UiNode("r/2/1", text = "样例甲"),
            UiNode("r/3"), UiNode("r/3/0", text = "居住地址"), UiNode("r/3/1", text = "测试地址")) +
            field("r/4", "检查地址", VisitContract.ADDRESS) + field("r/5", "走访描述", VisitContract.DESCRIPTION) +
            control("r/6", "正常", selected = true, clickable = true) + control("r/7", "异常", clickable = true) +
            control("r/8", "提交", clickable = true))
    private fun list(): UiTree = UiTree("fixture.app", 1,
        listOf(UiNode("r")) + control("r/0", "待办", selected = true) + listOf(UiNode("r/1", text = "已完成"),
            UiNode("r/2", bounds = UiBox(0, 100, 700, 500)),
            UiNode("r/2/0", text = "样例甲", bounds = UiBox(20, 120, 120, 160)),
            UiNode("r/2/1", text = "身份证：示例", bounds = UiBox(20, 170, 300, 200)),
            UiNode("r/2/2", text = "居住地址：测试地址", bounds = UiBox(20, 220, 500, 260)),
            UiNode("r/2/3", text = "0/1"), UiNode("r/2/4", text = "走访记录", clickable = true)) +
            control("r/2/5", "走访", clickable = true))

    @Test fun extraWrapperLayersKeepRecordIdentityFieldsAndActions() {
        val record = contract.top(list())
        assertEquals("r/2/5", record.actionPath)
        val form = form()
        contract.form(form, record)
        contract.verifyValues(form)
        assertTrue(contract.field(form, "检查地址").path.startsWith("r/4/1/"))
        assertTrue(contract.field(form, "走访描述").path.startsWith("r/5/1/"))
        assertEquals("r/6", contract.choiceButton(form, "正常").path)
        assertEquals("r/8", contract.submit(form).path)
    }
    @Test fun accessibilityDescriptionsCanReplaceNonEditableLabels() {
        fun UiTree.described() = copy(nodes = nodes.map { if (!it.editable) it.copy(description = it.text, text = "") else it })
        val record = contract.top(list().described())
        val tree = form().described()
        contract.form(tree, record)
        contract.verifyValues(tree)
        assertEquals("r/8", contract.submit(tree).path)
    }
    @Test fun requiredMarkersAndReorderedSectionsDoNotChangeInputAssociation() {
        val tree = form().copy(nodes = form().nodes.filterNot { it.path.startsWith("r/4") || it.path.startsWith("r/5") } +
            field("r/4", "＊走访描述：", VisitContract.DESCRIPTION) + field("r/5", "检查地址：*", VisitContract.ADDRESS))
        contract.form(tree, contract.top(list()))
        contract.verifyValues(tree)
        assertTrue(contract.field(tree, "走访描述").path.startsWith("r/4/1/"))
        assertTrue(contract.field(tree, "检查地址").path.startsWith("r/5/1/"))
    }
    @Test fun flattenedIdentityRowsAllowSmallVerticalAlignmentDifferences() {
        val tree = form().copy(nodes = form().nodes.filterNot { it.path.startsWith("r/2") || it.path.startsWith("r/3") } + listOf(
            UiNode("r/2", text = "姓名：", bounds = UiBox(0, 100, 90, 130)),
            UiNode("r/3", text = "样例甲", bounds = UiBox(91, 102, 300, 132)),
            UiNode("r/9", text = "居住地址：", bounds = UiBox(0, 140, 90, 170)),
            UiNode("r/10", text = "测试地址", bounds = UiBox(91, 143, 500, 173))))
        contract.formIdentity(tree, contract.top(list()))
    }
    @Test fun descriptionsNeverOverrideConflictingVisibleTextOrEnteredValues() {
        val tree = form().copy(nodes = form().nodes.map {
            if (it.readableText == "提交") it.copy(text = "删除", description = "提交")
            else if (it.editable) it.copy(description = "检查地址") else it
        })
        assertThrows(VisitMismatch::class.java) { contract.submit(tree) }
        contract.verifyValues(tree)
        assertThrows(VisitMismatch::class.java) { contract.verifyValues(tree.copy(nodes = tree.nodes.map {
            if (it.editable) it.copy(text = "", description = VisitContract.ADDRESS) else it
        })) }
    }
    @Test fun selectedGroupContainingBothOptionsDoesNotSelectNormal() {
        val tree = form().copy(nodes = form().nodes.map { it.copy(selected = it.path == "r") })
        assertEquals(ChoiceResult.UNKNOWN, contract.normalState(tree))
        assertThrows(VisitMismatch::class.java) { contract.normal(tree) }
        val list = list().copy(nodes = list().nodes.map { it.copy(selected = it.path == "r") })
        assertThrows(VisitMismatch::class.java) { contract.records(list) }
    }
    @Test fun selectedSectionContainingAnInputIsNotASelectedOption() {
        val tree = UiTree("fixture.app", 2, listOf(UiNode("r"), UiNode("r/0", selected = true),
            UiNode("r/0/0", text = "正常"), UiNode("r/0/1", editable = true), UiNode("r/1", text = "异常")))
        assertEquals(ChoiceResult.UNKNOWN, contract.normalState(tree))
    }
    @Test fun sharedClickableContainerAndDuplicateSubmitRemainAmbiguous() {
        val tree = form().copy(nodes = form().nodes.map { it.copy(clickable = it.path == "r") })
        assertThrows(VisitMismatch::class.java) { contract.submit(tree) }
        val duplicate = form().copy(nodes = form().nodes + UiNode("r/9", description = "提交", clickable = true))
        assertThrows(VisitMismatch::class.java) { contract.submit(duplicate) }
    }
    @Test fun unknownMeaningAndExplicitAbnormalRemainRejected() {
        val unknown = form().copy(nodes = form().nodes.map { if (it.readableText == "检查地址") it.copy(text = "其他地址") else it })
        assertThrows(VisitMismatch::class.java) { contract.form(unknown, contract.top(list())) }
        val abnormal = form().copy(nodes = form().nodes.map { it.copy(selected = it.path == "r/7") })
        assertEquals(ChoiceResult.ABNORMAL, contract.normalState(abnormal))
        assertThrows(VisitMismatch::class.java) { contract.form(abnormal, contract.top(list())) }
        val wrong = form().copy(nodes = form().nodes.map { if (it.text == "样例甲") it.copy(text = "样例乙") else it })
        assertThrows(VisitMismatch::class.java) { contract.formIdentity(wrong, contract.top(list())) }
    }
}
