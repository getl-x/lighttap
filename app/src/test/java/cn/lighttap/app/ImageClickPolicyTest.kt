package cn.lighttap.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImageClickPolicyTest {
    private val contract = VisitContract("fixture")
    private val screen = UiBox(0, 0, 720, 1600)
    private val button = UiBox(500, 400, 680, 460)
    private val textBox = UiBox(540, 410, 630, 445)
    private fun list(): UiTree = UiTree("fixture", 1, listOf(
        UiNode("r", bounds = screen), UiNode("r/0", text = "待办", selected = true), UiNode("r/1", text = "已完成"),
        UiNode("r/2", bounds = UiBox(0, 100, 700, 500)),
        UiNode("r/2/0", text = "样例甲", bounds = UiBox(20, 120, 120, 160)),
        UiNode("r/2/1", text = "身份证：示例", bounds = UiBox(20, 170, 300, 200)),
        UiNode("r/2/2", text = "居住地址：测试地址", bounds = UiBox(20, 220, 500, 260)),
        UiNode("r/2/3", text = "0/1"), UiNode("r/2/4", text = "走访记录", clickable = true),
        UiNode("r/2/5", text = "走访", clickable = true, bounds = button)))
    private fun form(): UiTree = UiTree("fixture", 2, listOf(
        UiNode("r", bounds = screen), UiNode("r/0", text = "新增"), UiNode("r/1", text = "走访记录"),
        UiNode("r/2"), UiNode("r/2/0", text = "姓名"), UiNode("r/2/1", text = "样例甲"),
        UiNode("r/3"), UiNode("r/3/0", text = "居住地址"), UiNode("r/3/1", text = "测试地址"),
        UiNode("r/4"), UiNode("r/4/0", text = "检查地址"), UiNode("r/4/1", text = VisitContract.ADDRESS, editable = true),
        UiNode("r/5"), UiNode("r/5/0", text = "走访描述"), UiNode("r/5/1", text = VisitContract.DESCRIPTION, editable = true),
        UiNode("r/6", text = "正常", selected = true), UiNode("r/7", text = "异常"),
        UiNode("r/8", text = "提交", clickable = true, bounds = button)))
    private fun UiTree.change(path: String, update: (UiNode) -> UiNode) = copy(nodes = nodes.map { if (it.path == path) update(it) else it })
    private fun evidence(at: Long = 0, normal: Boolean = true, clear: Boolean = true) = ImageClickEvidence(textBox, at, 720, 1600, clear, normal)
    private class Native(var tree: UiTree) : VisitUiAccess {
        override val target = "fixture"
        var reads = 0
        override suspend fun capture(): UiTree { reads++; return tree }
        override suspend fun perform(tree: UiTree, node: UiNode, action: Int, value: String?) { fail("Image flow called a native action") }
    }

    @Test fun exactChineseLabelLocatesCurrentPixelsAndIgnoresOtherRows() {
        assertEquals(textBox, ImageClickLocator.locate(listOf(ImageText(" 走 访 ", .98f, textBox),
            ImageText("走访", .99f, UiBox(540, 800, 630, 840)), ImageText("走访记录", .99f, button)), "走访", button, 720, 1600))
    }
    @Test fun ocrRegionTracksTheCurrentButtonAndClampsToScreen() {
        val region = imageOcrRegion(button, 720, 1600)
        assertTrue(region.left <= button.left && region.top <= button.top)
        assertTrue(region.right >= button.right && region.bottom >= button.bottom)
        assertTrue((region.right-region.left) * (region.bottom-region.top) < 720 * 1600 / 10)
        assertEquals(UiBox(0, 0, 108, 48), imageOcrRegion(UiBox(0, 0, 100, 40), 720, 1600))
        assertThrows(VisitMismatch::class.java) { imageOcrRegion(UiBox(-1, 0, 100, 40), 720, 1600) }
    }
    @Test fun knownCountdownAndHiddenDecorationCanChangeOnARecognizedPage() {
        val before = list().copy(nodes = list().nodes + UiNode("r/timer", text = "倒计时53天") +
            UiNode("r/decoration", text = "hidden before", visible = false))
        val after = before.change("r/timer") { it.copy(text = "倒计时52天") }
            .change("r/decoration") { it.copy(text = "hidden after") }
        assertTrue(sameVisitPage(before, after))
        assertTrue(sameVisitPage(before, after.copy(nodes = after.nodes.filterNot { it.path == "r/decoration" })))
    }
    @Test fun criticalChangesAndUnknownVisibleTextRemainUnstable() {
        val tree = list().copy(nodes = list().nodes + UiNode("r/timer", text = "倒计时53天") + UiNode("r/message", text = "提示甲"))
        for (changed in listOf(tree.change("r/2/0") { it.copy(text = "样例乙") },
            tree.change("r/2/3") { it.copy(text = "1/1") }, tree.change("r/0") { it.copy(selected = false) },
            tree.change("r/message") { it.copy(text = "提示乙") }, tree.change("r/timer") { it.copy(text = "正在加载") },
            tree.copy(nodes = tree.nodes + UiNode("r/popup", text = "确认", clickable = true))))
            assertFalse(sameVisitPage(tree, changed))
    }
    @Test fun hiddenIdentityAndInteractiveCountdownAreNeverIgnored() {
        val tree = list().change("r/2/2") { it.copy(visible = false) }
        assertFalse(sameVisitPage(tree, tree.change("r/2/2") { it.copy(text = "居住地址：其他地址") }))
        val interactive = list().copy(nodes = list().nodes + UiNode("r/timer", text = "倒计时53天", clickable = true))
        assertFalse(sameVisitPage(interactive, interactive.change("r/timer") { it.copy(text = "倒计时52天") }))
    }
    @Test fun unrecognizedPageDoesNotGainRelaxedStability() {
        val a = UiTree("fixture", 1, listOf(UiNode("r"), UiNode("r/timer", text = "倒计时53天")))
        assertFalse(sameVisitPage(a, a.change("r/timer") { it.copy(text = "倒计时52天") }))
    }
    @Test fun wrongLabelLowConfidenceAndDuplicateCandidateAreRejected() {
        for (lines in listOf(emptyList(), listOf(ImageText("走访记录", .99f, textBox)),
            listOf(ImageText("走访", .89f, textBox)), listOf(ImageText("走访", Float.NaN, textBox)),
            listOf(ImageText("走访", .99f, textBox), ImageText("走访", .20f, textBox)))) {
            assertThrows(VisitMismatch::class.java) { ImageClickLocator.locate(lines, "走访", button, 720, 1600) }
        }
    }
    @Test fun clippedButtonAndOutOfBoundsEvidenceNeverProduceAClick() {
        assertThrows(VisitMismatch::class.java) { ImageClickLocator.locate(listOf(ImageText("走访", .99f, textBox)),
            "走访", button.copy(right = 800), 720, 1600) }
        assertThrows(VisitMismatch::class.java) { ImageClickLocator.validate(evidence().copy(box = textBox.copy(left = 499)), button, true, 0) }
    }
    @Test fun oldFutureLoadingAndUnknownNormalFramesAreRejected() {
        for (frame in listOf(evidence(at = -5001), evidence(at = 1), evidence(clear = false)))
            assertThrows(VisitMismatch::class.java) { ImageClickLocator.validate(frame, button, true, 0) }
        assertThrows(VisitMismatch::class.java) { ImageClickLocator.validate(evidence(normal = false), button, false, 0) }
    }
    @Test fun changedIdentityPackageWindowOrFieldIsRejected() {
        val guard = ImageClickGuard("fixture")
        val list = list(); val node = list.node(contract.top(list).actionPath)
        for (changed in listOf(list.change("r/2/0") { it.copy(text = "样例乙") }, list.copy(packageName = "other"), list.copy(windowId = 9)))
            assertThrows(VisitMismatch::class.java) { guard.reacquire(list, node, changed, true) }
        val form = form(); val submit = contract.submit(form)
        for (changed in listOf(form.change("r/2/1") { it.copy(text = "样例乙") }, form.change("r/4/1") { it.copy(text = "不同内容") },
            form.change("r/7") { it.copy(selected = true) }))
            assertThrows(VisitMismatch::class.java) { guard.reacquire(form, submit, changed, false) }
    }
    @Test fun navigationUsesOneImageGestureAndNoNativeClick() = runTest {
        val native = Native(list()); var taps = 0
        val flow = ImageClickFlow(native, { _, _, _ -> evidence() }, { _, box -> assertEquals(textBox, box); taps++ }, { 0 })
        flow.click(native.tree, native.tree.node(contract.top(native.tree).actionPath), true)
        assertEquals(1, taps); assertEquals(2, native.reads)
    }
    @Test fun screenshotRejectionBeforeDispatchIsRecoverableAndSendsNothing() = runTest {
        val native = Native(list()); var taps = 0
        val flow = ImageClickFlow(native, { _, _, _ -> throw VisitMismatch("图像按钮识别不唯一或置信度不足，已暂停") }, { _, _ -> taps++ }, { 0 })
        try { flow.click(native.tree, native.tree.node(contract.top(native.tree).actionPath), true); fail("Expected rejection") }
        catch (_: VisitPreActionMismatch) { }
        assertEquals(0, taps)
    }
    @Test fun movedTargetAfterOcrIsNotClicked() = runTest {
        val native = Native(list()); val request = native.tree; var taps = 0
        val flow = ImageClickFlow(native, { _, _, _ ->
            native.tree = request.change("r/2/5") { it.copy(bounds = button.copy(top = 500, bottom = 560)) }; evidence()
        }, { _, _ -> taps++ }, { 0 })
        try { flow.click(request, request.node(contract.top(request).actionPath), true); fail("Expected rejection") }
        catch (_: VisitPreActionMismatch) { }
        assertEquals(0, taps)
    }
    @Test fun newDialogPublishedDuringOcrPreventsGesture() = runTest {
        val native = Native(list()); val request = native.tree; var taps = 0
        val flow = ImageClickFlow(native, { _, _, _ ->
            native.tree = request.copy(nodes = request.nodes + UiNode("r/dialog", text = "确认", clickable = true)); evidence()
        }, { _, _ -> taps++ }, { 0 })
        try { flow.click(request, request.node(contract.top(request).actionPath), true); fail("Dialog ignored") }
        catch (_: VisitPreActionMismatch) { }
        assertEquals(0, taps)
    }
    @Test fun unrelatedCountdownTextChangeDoesNotChangeRecordOrTarget() {
        val before = list().copy(nodes = list().nodes + UiNode("r/countdown", text = "倒计时53天"))
        val after = before.change("r/countdown") { it.copy(text = "倒计时52天") }
        val button = before.node(contract.top(before).actionPath)
        assertEquals(button, ImageClickGuard("fixture").reacquire(before, button, after, true))
    }
    @Test fun cancellationDuringImageRecognitionCannotDispatchAfterwards() = runTest {
        val native = Native(list()); var taps = 0
        val flow = ImageClickFlow(native, { _, _, _ -> delay(1000); evidence() }, { _, _ -> taps++ }, { testScheduler.currentTime })
        val worker = launch { flow.click(native.tree, native.tree.node(contract.top(native.tree).actionPath), true) }
        runCurrent(); worker.cancelAndJoin(); advanceUntilIdle()
        assertEquals(0, taps)
    }
    @Test fun acceptedButUnconfirmedGestureHasNoAutomaticRetry() = runTest {
        val native = Native(list()); var taps = 0
        val flow = ImageClickFlow(native, { _, _, _ -> evidence() }, { _, _ -> taps++; throw VisitMismatch("accepted but uncertain") }, { 0 })
        try { flow.click(native.tree, native.tree.node(contract.top(native.tree).actionPath), true); fail("Expected uncertainty") }
        catch (error: VisitMismatch) { assertFalse(error is VisitPreActionMismatch) }
        assertEquals(1, taps)
    }
    @Test fun submissionPreparationIsReadOnlyAndPreparedGestureCannotBeReused() = runTest {
        val native = Native(form()); var taps = 0
        val flow = ImageClickFlow(native, { _, _, navigation -> assertFalse(navigation); evidence() }, { _, _ -> taps++ }, { 0 })
        val plan = flow.prepare(native.tree, contract.submit(native.tree), false)
        assertEquals(0, taps)
        flow.dispatch(plan)
        assertEquals(1, taps)
        try { flow.dispatch(plan); fail("Prepared gesture reused") } catch (_: IllegalStateException) { }
        assertEquals(1, taps)
    }
    @Test fun preparedSubmissionExpiresRatherThanReRecognizingAndSubmittingAgain() = runTest {
        val native = Native(form()); var taps = 0
        val flow = ImageClickFlow(native, { _, _, _ -> evidence() }, { _, _ -> taps++ }, { testScheduler.currentTime })
        val plan = flow.prepare(native.tree, contract.submit(native.tree), false)
        advanceTimeBy(5001)
        try { flow.dispatch(plan); fail("Old evidence used") } catch (_: VisitPreActionMismatch) { }
        assertEquals(0, taps)
    }
    @Test fun confirmationKeysKeepOriginalApprovalAndImageApprovalIndependent() {
        assertEquals("test_confirmed", VisitRecognition.CONTROLS.confirmationKey)
        assertNotEquals(VisitRecognition.CONTROLS.confirmationKey, VisitRecognition.IMAGE.confirmationKey)
        assertEquals(VisitRecognition.CONTROLS, VisitSettings().recognition)
    }
}
