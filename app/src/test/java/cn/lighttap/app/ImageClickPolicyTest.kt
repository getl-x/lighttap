package cn.lighttap.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImageClickPolicyTest {
    private val button = UiBox(500, 400, 680, 460)
    private fun page(label: String = "走访") = UiTree("fixture", 1, listOf(UiNode("r", bounds = UiBox(0, 0, 720, 1600)),
        UiNode("r/button", text = label, clickable = true, bounds = button), UiNode("r/watermark", text = "浅灰色水印")))
    private class Native(var tree: UiTree) : VisitUiAccess {
        override val target = "fixture"
        var reads = 0
        override suspend fun capture(): UiTree { reads++; return tree }
        override suspend fun perform(tree: UiTree, node: UiNode, action: Int, value: String?) { fail("Unexpected native action") }
    }
    @Test fun readableButtonUsesCurrentControlWithoutOcrOrPageRechecks() = runTest {
        val ui = Native(page()); var taps = 0
        val flow = ImageClickFlow(ui, { _, _, _ -> fail("Readable button must not use OCR"); error("unreachable") },
            { _, box -> assertEquals(button, box); taps++ }, { 0 })
        flow.click(ui.tree, ui.tree.nodes[1], true)
        assertEquals(1, taps); assertEquals(0, ui.reads)
    }
    @Test fun watermarkAndNotificationTextChangesDoNotBlockPreparedClick() = runTest {
        val ui = Native(page("提交")); var taps = 0
        val flow = ImageClickFlow(ui, { _, _, _ -> error("OCR unnecessary") }, { _, _ -> taps++ }, { 0 })
        val prepared = flow.prepare(ui.tree, ui.tree.nodes[1], false)
        ui.tree = ui.tree.copy(nodes = ui.tree.nodes + UiNode("r/message", text = "新消息", clickable = true))
        flow.dispatch(prepared)
        assertEquals(1, taps); assertEquals(0, ui.reads)
        assertThrows(IllegalStateException::class.java) { prepared.consume() }
    }
    @Test fun unreadableButtonCanUseOcrWithoutWhiteOrNormalStyleGates() = runTest {
        val ui = Native(page("")); var taps = 0
        val flow = ImageClickFlow(ui, { _, _, _ -> ImageClickEvidence(button, 0, 720, 1600, false, false) }, { _, _ -> taps++ }, { 0 })
        flow.click(ui.tree, ui.tree.nodes[1], false)
        assertEquals(1, taps)
    }
    @Test fun expiredImageIsReReadBeforeAnyGesture() = runTest {
        val ui = Native(page()); var taps = 0
        val flow = ImageClickFlow(ui, { _, _, _ -> error("unused") }, { _, _ -> taps++ }, { testScheduler.currentTime })
        val prepared = flow.prepare(ui.tree, ui.tree.nodes[1], true)
        advanceTimeBy(5_001)
        try { flow.dispatch(prepared); fail("Expired coordinates used") } catch (_: VisitPreActionMismatch) { }
        assertEquals(0, taps)
    }
    @Test fun cancellationDuringOcrNeverDispatches() = runTest {
        val ui = Native(page("")); var taps = 0
        val flow = ImageClickFlow(ui, { _, _, _ -> delay(1_000); ImageClickEvidence(button, 0, 720, 1600, true, true) }, { _, _ -> taps++ }, { 0 })
        val job = launch { flow.click(ui.tree, ui.tree.nodes[1], true) }; runCurrent(); job.cancelAndJoin()
        advanceUntilIdle(); assertEquals(0, taps)
    }
    @Test fun acceptedButUnconfirmedGestureHasNoAutomaticSecondTap() = runTest {
        val ui = Native(page()); var taps = 0
        val flow = ImageClickFlow(ui, { _, _, _ -> error("unused") }, { _, _ -> taps++; throw VisitMismatch("uncertain") }, { 0 })
        try { flow.click(ui.tree, ui.tree.nodes[1], true); fail("Uncertainty hidden") }
        catch (error: VisitMismatch) { assertFalse(error is VisitPreActionMismatch) }
        assertEquals(1, taps)
    }
    @Test fun ocrFindsRequestedTextInsideCurrentButton() {
        val box = UiBox(540, 410, 630, 445)
        assertEquals(box, ImageClickLocator.locate(listOf(ImageText("走 访", .98f, box),
            ImageText("走访记录", .99f, button)), "走访", button, 720, 1600))
        assertThrows(VisitMismatch::class.java) { ImageClickLocator.locate(emptyList(), "走访", button, 720, 1600) }
    }
    @Test fun cropIsBoundedToCurrentButtonAndScreen() {
        val region = imageOcrRegion(button, 720, 1600)
        assertTrue(region.left <= button.left && region.right >= button.right)
        assertThrows(VisitMismatch::class.java) { imageOcrRegion(button.copy(right = 800), 720, 1600) }
    }
}
