package cn.lighttap.app

import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic flattened WebView rows, including Android's clipped offscreen bounds. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScrolledVisitFormTest {
    private val contract = VisitContract("fixture")
    private val record = VisitRecord("样例甲", "测试地址", "r/card", "r/card/visit")
    private val screen = UiBox(0, 0, 720, 1604)
    private fun form(scrolled: Boolean = true, edge: Int = 192): UiTree {
        fun row(path: String, label: String, value: String, top: Int) = listOf(
            UiNode("r/$path-label", text = label, visible = !scrolled,
                bounds = if (scrolled) UiBox(28, edge, 200, edge) else UiBox(28, top, 200, top + 40)),
            UiNode("r/$path-value", text = value, visible = !scrolled,
                bounds = if (scrolled) UiBox(198, edge, 690, edge) else UiBox(198, top, 690, top + 40)))
        return UiTree("fixture", 2, listOf(
            UiNode("r", bounds = screen, scrollable = true),
            UiNode("r/title", text = "新增"), UiNode("r/heading", text = "走访记录")) +
            row("name", "姓名", record.name, 250) + row("address", "居住地址", record.address, 300) + listOf(
            UiNode("r/check"), UiNode("r/check/label", text = "检查地址"),
            UiNode("r/check/input", editable = true, text = VisitContract.ADDRESS),
            UiNode("r/description", bounds = screen), UiNode("r/description/label", text = "走访描述"),
            UiNode("r/description/input", editable = true,
                bounds = if (scrolled) UiBox(28, 810, 690, 1048) else UiBox(28, 1500, 690, 1800)),
            UiNode("r/normal", text = "正常", selected = true, clickable = true), UiNode("r/abnormal", text = "异常", clickable = true),
            UiNode("r/submit", text = "提交", clickable = true, bounds = UiBox(20, 1510, 700, 1588)),
            UiNode("r/timer", text = "倒计时53天")))
    }
    private fun UiTree.change(path: String, update: (UiNode) -> UiNode) =
        copy(nodes = nodes.map { if (it.path == path) update(it) else it })

    @Test fun clippedIdentityTextIsStillCheckedAtEitherViewportEdge() {
        for (edge in listOf(0, 192, 1604)) {
            val tree = form(edge = edge)
            contract.form(tree, record)
            assertEquals(record.fingerprint, contract.formFingerprint(tree))
            val described = tree.copy(nodes = tree.nodes.map {
                if (!it.editable) it.copy(text = "", description = it.text) else it
            })
            contract.form(described, record)
            assertEquals(record.fingerprint, contract.formFingerprint(described))
        }
    }
    @Test fun changedHiddenIdentityIsRejectedAndNeverIgnoredByPageComparison() {
        val tree = form()
        for (path in listOf("r/name-value", "r/address-value")) {
            val changed = tree.change(path) { it.copy(text = "其他记录") }
            assertThrows(VisitMismatch::class.java) { contract.form(changed, record) }
            assertFalse(sameVisitPage(tree, changed))
        }
        assertTrue(sameVisitPage(tree, tree.change("r/timer") { it.copy(text = "倒计时52天") }))
    }
    @Test fun missingEmptyDuplicateAndControlValuesAreRejected() {
        val tree = form()
        for (invalid in listOf(
            tree.copy(nodes = tree.nodes.filterNot { it.path == "r/name-value" }),
            tree.change("r/name-value") { it.copy(text = "") },
            tree.change("r/name-value") { it.copy(text = "居住地址") },
            tree.change("r/name-value") { it.copy(text = "正常") },
            tree.change("r/name-value") { it.copy(editable = true) },
            tree.copy(nodes = tree.nodes + UiNode("r/duplicate", text = "姓名")))) {
            assertThrows(VisitMismatch::class.java) { contract.formIdentity(invalid, record) }
        }
    }
    @Test fun unrelatedClippedOrVisibleRowsAreNotAssociated() {
        val tree = form()
        for (invalid in listOf(
            tree.change("r/name-value") { it.copy(bounds = UiBox(198, 193, 690, 193)) },
            tree.change("r/name-value") { it.copy(bounds = UiBox(10, 192, 100, 192)) },
            tree.change("r/name-value") { it.copy(visible = true) },
            tree.change("r/name-label") { it.copy(bounds = UiBox(28, 192, 200, 193)) },
            form(false).change("r/name-value") { it.copy(bounds = UiBox(198, 350, 690, 390)) })) {
            assertThrows(VisitMismatch::class.java) { contract.formIdentity(invalid, record) }
        }
    }

    private inner class Ui : VisitUiAccess {
        override val target = "fixture"
        var tree = form(false)
        var opened = false
        var scrolls = 0
        var submits = 0
        val writes = mutableListOf<String>()
        private val list = UiTree(target, 1, listOf(
            UiNode("r", bounds = screen), UiNode("r/tab", text = "待办", selected = true), UiNode("r/done", text = "已完成"),
            UiNode("r/card", bounds = UiBox(0, 100, 700, 500)),
            UiNode("r/card/name", text = record.name, bounds = UiBox(20, 120, 120, 160)),
            UiNode("r/card/id", text = "身份证：示例", bounds = UiBox(20, 170, 300, 200)),
            UiNode("r/card/address", text = "居住地址：${record.address}"), UiNode("r/card/progress", text = "0/1"),
            UiNode("r/card/history", text = "走访记录", clickable = true), UiNode("r/card/visit", text = "走访", clickable = true)))
        override suspend fun capture() = if (opened) tree else list
        override suspend fun perform(tree: UiTree, node: UiNode, action: Int, value: String?) {
            when (action) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { scrolls++; this.tree = form() }
                AccessibilityNodeInfo.ACTION_SET_TEXT -> {
                    writes += node.path
                    this.tree = this.tree.change(node.path) { it.copy(text = value!!) }
                }
                AccessibilityNodeInfo.ACTION_CLICK -> if (node.text == "走访") opened = true else if (node.text == "提交") submits++
            }
        }
    }
    private class Fence : VisitSubmissionFence {
        override var hash: String? = null
        override var target: String? = null
        override fun mark(hash: String, target: String): Boolean { this.hash = hash; this.target = target; return true }
        override fun clear(): Boolean { hash = null; target = null; return true }
    }
    @Test fun continueAfterOneScrollLocatesThenFillsAndVerifiesInBothModes() = runTest {
        for (recognition in VisitRecognition.entries) {
            val ui = Ui()
            val bridge = SelectedVisitUiAccess({ recognition }, ui, ui)
            val driver = AndroidVisitDriver(bridge, VisitChoiceVerifier { _, _ -> }, Fence(),
                VisitReturnVision { ReturnFrame(ReturnVisual.CLEAR, testScheduler.currentTime) }, { testScheduler.currentTime })
            val runner = VisitRunner(backgroundScope, driver, { testScheduler.currentTime })
            runner.start(VisitSettings(targetPackage = "fixture", recognition = recognition), VisitExecution.STEP_TEST)
            advanceTimeBy(1_000); runCurrent()
            repeat(4) { runner.resume(); advanceTimeBy(1_000); runCurrent() }
            assertEquals(VisitStatus.CHECKPOINT, runner.state.value.status)
            assertEquals(VisitStep.LOCATE_DESCRIPTION, runner.state.value.step)
            assertEquals(1, ui.scrolls)
            assertTrue(ui.writes.isEmpty())
            runner.resume(); advanceTimeBy(1_000); runCurrent()
            assertEquals(VisitStep.FILL_DESCRIPTION, runner.state.value.step)
            assertEquals(VisitStatus.CHECKPOINT, runner.state.value.status)
            assertTrue(ui.writes.isEmpty())
            repeat(2) { runner.resume(); advanceTimeBy(1_000); runCurrent() }
            assertEquals(VisitStep.SUBMIT, runner.state.value.step)
            assertEquals(VisitStatus.CHECKPOINT, runner.state.value.status)
            assertEquals(listOf("r/description/input"), ui.writes)
            assertEquals(0, ui.submits)
            assertEquals(1, ui.scrolls)
            runner.stop()
        }
    }
    @Test fun imageSubmissionReacquiresClippedIdentityAndRejectsAChangedRecord() = runTest {
        val ui = Ui().apply {
            opened = true
            tree = form().change("r/description/input") { it.copy(text = VisitContract.DESCRIPTION) }
        }
        var taps = 0
        val flow = ImageClickFlow(ui, { _, node, _ -> ImageClickEvidence(node.bounds, testScheduler.currentTime, 720, 1604, false, true) },
            { _, _ -> taps++ }, { testScheduler.currentTime })
        val plan = flow.prepare(ui.tree, contract.submit(ui.tree), false)
        ui.tree = ui.tree.change("r/timer") { it.copy(text = "倒计时52天") }
        flow.dispatch(plan)
        assertEquals(1, taps)
        val second = flow.prepare(ui.tree, contract.submit(ui.tree), false)
        ui.tree = ui.tree.change("r/name-value") { it.copy(text = "样例乙") }
        try { flow.dispatch(second); fail("Changed hidden identity was submitted") } catch (_: VisitMismatch) { }
        assertEquals(1, taps)
    }
}
