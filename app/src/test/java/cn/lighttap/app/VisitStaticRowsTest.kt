package cn.lighttap.app

import org.junit.Assert.*
import org.junit.Test

/** Synthetic WebView layouts. Business values and geometry come from fixtures. */
class VisitStaticRowsTest {
    private val contract = VisitContract("fixture")
    private fun list(): UiTree = UiTree("fixture", 1, listOf(
        UiNode("r"), UiNode("r/tab", text = "待办", selected = true), UiNode("r/done", text = "已完成"),
        UiNode("r/body", bounds = UiBox(0, 90, 720, 360)),
        UiNode("r/body/0", text = "样例甲", bounds = UiBox(20, 100, 100, 140)),
        UiNode("r/body/1", text = "身份证", bounds = UiBox(20, 150, 220, 190)),
        UiNode("r/body/2", text = "示例凭证", bounds = UiBox(200, 150, 500, 190)),
        // Label bounds may describe the entire CSS column, overlapping the value.
        UiNode("r/body/3", text = "居住地址", bounds = UiBox(20, 200, 240, 280)),
        UiNode("r/body/4", text = "测试地址", bounds = UiBox(200, 230, 680, 270)),
        UiNode("r/body/4/0", text = "测试地址", bounds = UiBox(200, 232, 680, 268)),
        UiNode("r/body/5", text = "0/1"), UiNode("r/body/6"),
        UiNode("r/body/6/0", text = "走访记录", clickable = true, bounds = UiBox(300, 300, 480, 350)),
        UiNode("r/body/6/1", text = "走访", clickable = true, bounds = UiBox(500, 300, 680, 350))))
    private fun UiTree.change(path: String, update: (UiNode) -> UiNode) =
        copy(nodes = nodes.map { if (it.path == path) update(it) else it })

    @Test fun boundedLabelValueRowsDoNotDependOnCssColumnBounds() {
        val tree = list()
        val record = contract.top(tree)
        assertEquals("测试地址", record.address)
        assertEquals("r/body/6/1", record.actionPath)
        val descriptions = tree.copy(nodes = tree.nodes.map { it.copy(text = "", description = it.text) })
        assertEquals(record.fingerprint, contract.top(descriptions).fingerprint)
    }

    @Test fun wrappedValueAndClippedGeometryUseTheSameBoundedAssociation() {
        val tree = list().change("r/body/4") { it.copy(text = "") }
        assertEquals("测试地址", contract.top(tree).address)
        val clipped = tree.copy(nodes = tree.nodes.map {
            if (it.path in setOf("r/body/3", "r/body/4", "r/body/4/0"))
                it.copy(visible = false, bounds = UiBox(0, 192, 0, 192)) else it
        })
        assertEquals("测试地址", contract.top(clipped).address)
    }

    @Test fun ambiguousMissingInteractiveAndControlValuesAreRejected() {
        val tree = list()
        val emptyWrapper = tree.change("r/body/4") { it.copy(text = "") }
        val invalid = listOf(
            tree.copy(nodes = tree.nodes.filterNot { it.path.startsWith("r/body/4") }),
            tree.copy(nodes = tree.nodes + UiNode("r/body/4/other", text = "另一地址")),
            emptyWrapper.copy(nodes = emptyWrapper.nodes + UiNode("r/body/4/other", text = "测试地址")),
            tree.change("r/body/4") { it.copy(clickable = true) },
            tree.change("r/body/4/0") { it.copy(editable = true) },
            tree.copy(nodes = tree.nodes.map { if (it.path.startsWith("r/body/4")) it.copy(text = "正常") else it }),
            tree.copy(nodes = tree.nodes + UiNode("r/body/duplicate", text = "居住地址")))
        for (sample in invalid) assertThrows(VisitMismatch::class.java) { contract.top(sample) }
    }

    @Test fun unboundedUnalignedTextIsNotMistakenForAField() {
        val tree = UiTree("fixture", 2, listOf(UiNode("r"), UiNode("r/title", text = "新增"),
            UiNode("r/heading", text = "走访记录"), UiNode("r/address", text = "居住地址：测试地址"),
            UiNode("r/name", text = "姓名", bounds = UiBox(20, 200, 240, 240)),
            UiNode("r/unknown", text = "无关文字", bounds = UiBox(0, 400, 100, 440))))
        val error = assertThrows(VisitMismatch::class.java) { contract.formFingerprint(tree) }
        assertEquals("无法确定记录核对字段：姓名", error.reason)
    }

    @Test fun adjacentRecordsKeepTheirOwnValueAndCurrentIdentity() {
        val tree = list()
        val second = tree.nodes.filter { it.path.startsWith("r/body/") }.map {
            it.copy(path = it.path.replace("r/body/", "r/second/"),
                text = when (it.text) { "样例甲" -> "样例乙"; "测试地址" -> "测试地址乙"; else -> it.text },
                bounds = it.bounds.copy(top = it.bounds.top + 500, bottom = it.bounds.bottom + 500))
        }
        val page = tree.copy(nodes = tree.nodes + UiNode("r/second", bounds = UiBox(0, 590, 720, 860)) + second)
        assertEquals(listOf("测试地址", "测试地址乙"), contract.records(page).map { it.address })
        val changed = page.copy(nodes = page.nodes.map {
            if (it.path.startsWith("r/body/4")) it.copy(visible = false, text = "其他地址") else it
        })
        assertNotEquals(contract.top(page).fingerprint, contract.top(changed).fingerprint)
        assertFalse(sameVisitPage(page, changed))
    }

    @Test fun fieldFailureIsSpecificAndSafeToExport() {
        val tree = list().copy(nodes = list().nodes.filterNot { it.path.startsWith("r/body/4") })
        val error = assertThrows(VisitMismatch::class.java) { contract.top(tree) }
        assertTrue(error.reason.contains("居住地址"))
        assertEquals(error.reason, safeVisitDebugReason(error.reason))
        assertEquals("未分类原因（原文未记录）", safeVisitDebugReason("无法确定记录核对字段：私人地址"))
    }
}
