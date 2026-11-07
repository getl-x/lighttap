package cn.lighttap.app

import org.junit.Assert.*
import org.junit.Test

class VisitContractTest {
    private val contract = VisitContract("fixture.app")
    private fun listTree(): UiTree = UiTree("fixture.app", 1, listOf(
        UiNode("r"), UiNode("r/0", text = "待办", selected = true), UiNode("r/1", text = "已完成"),
        UiNode("r/2", bounds = UiBox(0, 100, 700, 500)),
        UiNode("r/2/0", text = "样例甲", bounds = UiBox(20, 120, 120, 160)),
        UiNode("r/2/1", text = "身份证：示例", bounds = UiBox(20, 170, 300, 200)),
        UiNode("r/2/2", text = "居住地址：测试地址", bounds = UiBox(20, 220, 500, 260)),
        UiNode("r/2/3", text = "0/1"), UiNode("r/2/4", text = "走访记录", clickable = true),
        UiNode("r/2/5", text = "走访", clickable = true),
    ))
    private fun formTree(): UiTree = UiTree("fixture.app", 2, listOf(
        UiNode("r"), UiNode("r/0", text = "新增"), UiNode("r/1", text = "走访记录"),
        UiNode("r/2"), UiNode("r/2/0", text = "姓名"), UiNode("r/2/1", text = "样例甲"),
        UiNode("r/3"), UiNode("r/3/0", text = "居住地址"), UiNode("r/3/1", text = "测试地址"),
        UiNode("r/4"), UiNode("r/4/0", text = "检查地址"), UiNode("r/4/1", text = "", editable = true),
        UiNode("r/5"), UiNode("r/5/0", text = "走访描述"), UiNode("r/5/1", text = "", editable = true),
        UiNode("r/6", text = "正常", selected = true), UiNode("r/7", text = "异常"), UiNode("r/8", text = "提 交", clickable = true),
    ))
    private fun UiTree.replace(path: String, change: (UiNode) -> UiNode) = copy(nodes = nodes.map { if (it.path == path) change(it) else it })
    @Test fun onlyExactVisitButtonWithinCardIsChosen() {
        val tree = listTree(); assertEquals("r/2/5", contract.top(tree).actionPath)
        assertThrows(VisitMismatch::class.java) { contract.top(tree.replace("r/2/5") { it.copy(text = "走访记录") }) }
    }
    @Test fun wrongPackageAndNonPendingTabAreRejected() {
        assertThrows(VisitMismatch::class.java) { contract.top(listTree().copy(packageName = "other.app")) }
        assertThrows(VisitMismatch::class.java) { contract.top(listTree().replace("r/0") { it.copy(selected = false) }) }
    }
    @Test fun emptyWebContainerPausesInsteadOfCoordinates() {
        val tree = UiTree("fixture.app", 1, listOf(UiNode("r", className = "android.webkit.WebView")))
        assertThrows(VisitMismatch::class.java) { contract.top(tree) }
    }
    @Test fun emptyListRequiresExplicitEmptyState() {
        val tree = UiTree("fixture.app", 1, listOf(UiNode("r"), UiNode("r/0", text = "待办", selected = true), UiNode("r/1", text = "已完成")))
        assertThrows(VisitMismatch::class.java) { contract.records(tree) }
        assertTrue(contract.records(tree.copy(nodes = tree.nodes + UiNode("r/2", text = "暂无待办"))).isEmpty())
    }
    @Test fun fieldsAreAssociatedWithLabelsNotResidenceText() {
        val tree = formTree(); contract.form(tree, contract.top(listTree()))
        assertEquals("r/4/1", contract.field(tree, "检查地址").path)
        assertEquals("r/5/1", contract.field(tree, "走访描述").path)
        assertFalse(contract.field(tree, "检查地址").path.startsWith("r/3/"))
    }
    @Test fun duplicateFieldsAndLabelsAreRejected() {
        val tree = formTree()
        assertThrows(VisitMismatch::class.java) { contract.field(tree.copy(nodes = tree.nodes + UiNode("r/4/2", editable = true)), "检查地址") }
        assertThrows(VisitMismatch::class.java) { contract.field(tree.copy(nodes = tree.nodes + UiNode("r/9", text = "检查地址")), "检查地址") }
    }
    @Test fun conflictingValuesAreNeverAccepted() {
        assertThrows(VisitMismatch::class.java) { contract.allowedFields(formTree().replace("r/4/1") { it.copy(text = "其他已有内容") }) }
        assertThrows(VisitMismatch::class.java) { contract.allowedFields(formTree().replace("r/5/1") { it.copy(text = "其他已有内容") }) }
    }
    @Test fun normalMustBeExplicitlySelectedAndAbnormalMustNotBeSelected() {
        assertThrows(VisitMismatch::class.java) { contract.normal(formTree().replace("r/6") { it.copy(selected = false) }) }
        assertThrows(VisitMismatch::class.java) { contract.normal(formTree().replace("r/7") { it.copy(selected = true) }) }
    }
    @Test fun identityMismatchIsRejected() {
        assertThrows(VisitMismatch::class.java) { contract.form(formTree().replace("r/2/1") { it.copy(text = "样例乙") }, contract.top(listTree())) }
    }
    @Test fun bothFieldsMustBeExactBeforeSubmit() {
        val tree = formTree().replace("r/4/1") { it.copy(text = VisitContract.ADDRESS) }.replace("r/5/1") { it.copy(text = VisitContract.DESCRIPTION) }
        contract.verifyValues(tree); assertEquals("r/8", contract.submit(tree).path)
        assertThrows(VisitMismatch::class.java) { contract.verifyValues(tree.replace("r/5/1") { it.copy(text = VisitContract.DESCRIPTION + " ") }) }
    }
    @Test fun hiddenOrDisabledButtonsAreRejected() {
        assertThrows(VisitMismatch::class.java) { contract.submit(formTree().replace("r/8") { it.copy(enabled = false) }) }
        assertThrows(VisitMismatch::class.java) { contract.submit(formTree().replace("r/8") { it.copy(visible = false) }) }
    }
    @Test fun flattenedWebRowsAreAssociatedBySiblingSegmentsNotGuessedCoordinates() {
        val rows = mutableListOf(UiNode("r"), UiNode("r/0", text = "待办", selected = true), UiNode("r/1", text = "已完成"), UiNode("r/2"))
        for (i in 0..2) {
            val offset = i * 10
            val top = 100 + i * 300
            rows += UiNode("r/2/${offset}", text = "样例" + listOf("甲", "乙", "丙")[i], bounds = UiBox(20, top, 100, top + 30))
            rows += UiNode("r/2/${offset+1}", text = "身 份 证 ：", bounds = UiBox(20, top + 40, 200, top + 70))
            rows += UiNode("r/2/${offset+2}", text = "示例凭证", bounds = UiBox(198, top + 40, 400, top + 70))
            rows += UiNode("r/2/${offset+3}", text = "居 住 地 址 ：", bounds = UiBox(20, top + 80, 200, top + 110))
            if (i == 1) rows += UiNode("r/2/${offset+3}/0", text = "居 住 地 址 ：", bounds = UiBox(20, top + 82, 200, top + 108))
            rows += UiNode("r/2/${offset+4}", text = "测试地址" + i, bounds = UiBox(198, top + 80, 500, top + 110))
            rows += UiNode("r/2/${offset+5}", text = "0/1")
            rows += UiNode("r/2/${offset+6}", bounds = UiBox(300, top + 120, 680, top + 160))
            rows += UiNode("r/2/${offset+6}/0", text = " 走访记录 ", clickable = true, bounds = UiBox(300, top + 120, 480, top + 160))
            rows += UiNode("r/2/${offset+6}/1", text = " 走 访 ", clickable = true, bounds = UiBox(500, top + 120, 680, top + 160))
        }
        val tree = UiTree("fixture.app", 1, rows)
        assertEquals(3, contract.records(tree).size)
        assertEquals("样例甲", contract.top(tree).name)
        assertEquals("测试地址0", contract.top(tree).address)
        assertEquals("r/2/6/1", contract.top(tree).actionPath)
        assertEquals(3, tree.exact("走访").size)
        assertEquals(3, tree.exact("走访记录").size)
    }
    @Test fun offscreenClippedActionsAreNotSelected() {
        val tree = listTree().replace("r/2/5") { it.copy(bounds = UiBox(0, 1604, 100, 1604)) }
        assertThrows(VisitMismatch::class.java) { contract.top(tree) }
    }    @Test fun flattenedFormSectionsUseTheirOwnInput() {
        val tree = UiTree("fixture.app", 1, listOf(
            UiNode("r"), UiNode("r/0", text = "新增"), UiNode("r/1", text = "走访记录"),
            UiNode("r/2"), UiNode("r/2/0", text = "检查地址"), UiNode("r/2/1"), UiNode("r/2/1/0", text = "*"),
            UiNode("r/2/2"), UiNode("r/2/2/0", editable = true), UiNode("r/2/3", text = "检查类型"),
            UiNode("r/2/4", text = "走访描述"), UiNode("r/2/5"), UiNode("r/2/5/0", editable = true),
        ))
        assertEquals("r/2/2/0", contract.field(tree, "检查地址").path)
        assertEquals("r/2/5/0", contract.field(tree, "走访描述").path)
    }    @Test fun nestedDuplicateWebLabelsAreOneControlWithParentButtonBounds() {
        val list = listTree().copy(nodes = listTree().nodes + UiNode("r/0/0", text = "待办") + UiNode("r/2/5/0", text = "走访"))
        assertEquals(1,list.exact("待办").size)
        assertEquals("r/2/5",contract.top(list).actionPath)
        val form = formTree().replace("r/6") { it.copy(clickable=true) }.replace("r/7") { it.copy(clickable=true) }
        val duplicated = form.copy(nodes = form.nodes + UiNode("r/6/0",text="正常",bounds=UiBox(10,10,20,20)) + UiNode("r/7/0",text="异常") + UiNode("r/8/0",text="提交"))
        assertEquals("r/6",contract.choiceButton(duplicated,"正常").path)
        assertEquals("r/8",contract.submit(duplicated).path)
    }}
