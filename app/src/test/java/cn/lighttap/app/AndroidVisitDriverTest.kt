package cn.lighttap.app

import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidVisitDriverTest {
    private val bounds = UiBox(0, 0, 720, 1604)
    private fun listTree() = UiTree("fixture", 1, listOf(
        UiNode("r", bounds = bounds), UiNode("r/0", text = "待办", selected = true), UiNode("r/1", text = "已完成"),
        UiNode("r/2", bounds = UiBox(0, 100, 700, 500)),
        UiNode("r/2/0", text = "样例甲", bounds = UiBox(20, 120, 120, 160)),
        UiNode("r/2/1", text = "身份证：示例", bounds = UiBox(20, 170, 300, 200)),
        UiNode("r/2/2", text = "居住地址：测试地址", bounds = UiBox(20, 220, 500, 260)),
        UiNode("r/2/3", text = "0/1"), UiNode("r/2/4", text = "走访记录", clickable = true),
        UiNode("r/2/5", text = "走访", clickable = true),
    ))
    private fun formTree() = UiTree("fixture", 2, listOf(
        UiNode("r", bounds = bounds), UiNode("r/0", text = "新增"), UiNode("r/1", text = "走访记录"),
        UiNode("r/2", bounds = bounds), UiNode("r/2/0", text = "姓名"), UiNode("r/2/1", text = "样例甲"),
        UiNode("r/3", bounds = bounds), UiNode("r/3/0", text = "居住地址"), UiNode("r/3/1", text = "测试地址"),
        UiNode("r/4", bounds = bounds), UiNode("r/4/0", text = "检查地址"), UiNode("r/4/1", editable = true),
        UiNode("r/5", bounds = bounds), UiNode("r/5/0", text = "走访描述"), UiNode("r/5/1", editable = true),
        UiNode("r/6", text = "正常", selected = true, clickable = true), UiNode("r/7", text = "异常", clickable = true),
        UiNode("r/8", text = "提交", clickable = true), UiNode("r/9", text = "decorative"),
    ))
    private class Fence(private val now: () -> Long = { 0 }) : VisitSubmissionFence {
        override var hash: String? = null
        override var target: String? = null
        override var pendingClickMs: Long? = null
        override var pendingAnchorMs: Long? = null
        var lastStamp: Long? = null
        var onClear: () -> Unit = {}
        override fun mark(hash: String, target: String): Boolean { this.hash = hash; this.target = target; lastStamp = now(); return true }
        override fun clear(): Boolean { hash = null; target = null; pendingClickMs = null; pendingAnchorMs = null; onClear(); return true }
        override fun recordSubmission(): Boolean { lastStamp = now(); return true }
        override fun recordClick(at: Long): Boolean { pendingClickMs = at; lastStamp = at; return true }
        override fun confirmSubmission(at: Long): Boolean { pendingAnchorMs = pendingAnchorMs ?: at; lastStamp = pendingAnchorMs; return true }
        override fun submissionWaitMs(intervalMs: Long): Long = lastStamp?.let { (intervalMs - (now() - it)).coerceAtLeast(0) } ?: 0
    }
    private inner class Ui(private val now: () -> Long) : VisitUiAccess {
        override val target = "fixture"
        override var lastClickDispatchMs: Long? = null
        var form = formTree()
        var opened = false
        var submitted = false
        var publicationDelay = 0L
        var refreshDelay = 0L
        private var readyAt = 0L
        val actions = mutableListOf<String>()
        var failAfterSubmit = false
        var listOverride: UiTree? = null
        override suspend fun capture(): UiTree {
            listOverride?.let { return it }
            if (submitted) return if (now() < readyAt) listTree() else listTree().copy(nodes = listTree().nodes.take(3) + UiNode("r/empty", text = "暂无待办"))
            if (opened) return if (now() < readyAt) form.copy(nodes = form.nodes.take(3)) else form
            return listTree()
        }
        override suspend fun perform(tree: UiTree, node: UiNode, action: Int, value: String?) {
            currentCoroutineContext().ensureActive()
            actions += if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) node.path else node.text
            when {
                value != null -> form = form.copy(nodes = form.nodes.map { if (it.path == node.path) it.copy(text = value) else it })
                node.text == "走访" -> { listOverride = null; opened = true; readyAt = now() + publicationDelay }
                node.text == "提交" -> { lastClickDispatchMs = now(); submitted = true; readyAt = now() + refreshDelay; if (failAfterSubmit) throw VisitMismatch("accepted_but_callback_lost") }
            }
        }
    }
    private fun TestScope.runner(driver: VisitDriver) = VisitRunner(backgroundScope, driver, { testScheduler.currentTime })
    private fun TestScope.returnVision() = VisitReturnVision { ReturnFrame(ReturnVisual.CLEAR, testScheduler.currentTime) }
    private val plan = VisitSettings(targetPackage = "fixture")

    private fun expandedList(): UiTree {
        val first = listTree()
        val extra = first.nodes.filter { it.path == "r/2" || it.path.startsWith("r/2/") }.map {
            it.copy(path = it.path.replaceFirst("r/2", "r/3"), text = if (it.text == "样例甲") "样例乙" else it.text,
                bounds = it.bounds.copy(top = it.bounds.top + 500, bottom = it.bounds.bottom + 500))
        }
        return first.copy(nodes = first.nodes + extra)
    }

    @Test fun changedBottomRowsAfterConfirmationStartFreshListSelection() = runTest {
        val ui = Ui { testScheduler.currentTime }.apply { listOverride = listTree() }
        val fence = Fence().apply { mark("previous-record", "fixture") }
        val events = mutableListOf<VisitDebugEvent>()
        val driver = AndroidVisitDriver(ui, VisitChoiceVerifier { _, _ -> }, fence, returnVision(),
            { testScheduler.currentTime }, diagnostics = VisitDiagnostics { events += it })
        driver.execute(VisitStep.WAIT_LIST)
        ui.listOverride = expandedList()
        driver.validate(VisitStep.GAP)
        driver.execute(VisitStep.FIND_LIST)
        val list = events.last { it.kind == VisitDebugKind.LIST }
        assertEquals(1, list.previousRecords); assertEquals(2, list.records)
        assertEquals(true, list.listChanged); assertEquals(true, list.topUnchanged)
        driver.execute(VisitStep.OPEN_FORM)
        assertEquals(listOf("走访"), ui.actions)
        assertFalse(driver.pendingSubmission)
    }

    @Test fun changedTopAfterFreshSelectionStillRejectsClick() = runTest {
        val ui = Ui { testScheduler.currentTime }.apply { listOverride = listTree() }
        val driver = AndroidVisitDriver(ui, VisitChoiceVerifier { _, _ -> }, Fence(), returnVision(), { testScheduler.currentTime })
        driver.execute(VisitStep.FIND_LIST)
        ui.listOverride = listTree().copy(nodes = listTree().nodes.map { if (it.path == "r/2/0") it.copy(text = "样例乙") else it })
        try { driver.execute(VisitStep.OPEN_FORM); fail("Selected record changed") }
        catch (e: VisitMismatch) { assertEquals("顶部记录变化，禁止点击", e.reason) }
        assertTrue(ui.actions.isEmpty())
    }

    @Test fun runnerContinuesPastChangedBottomRowsAndCountsOnlyConfirmedResults() = runTest {
        val ui = Ui { testScheduler.currentTime }.apply { listOverride = listTree() }
        val fence = Fence { testScheduler.currentTime }.apply {
            mark("previous-record", "fixture")
            onClear = { if (!ui.submitted) ui.listOverride = expandedList() }
        }
        val driver = AndroidVisitDriver(ui, VisitChoiceVerifier { _, _ -> }, fence, returnVision(), { testScheduler.currentTime })
        val runner = runner(driver)
        runner.start(plan.copy(mode = VisitRunMode.COUNT, countText = "2"), VisitExecution.FORMAL)
        advanceTimeBy(65_000); runCurrent()
        assertEquals(VisitStatus.COMPLETED, runner.state.value.status)
        assertEquals(2L, runner.state.value.rounds)
        assertEquals(1, ui.actions.count { it == "提交" })
        assertEquals(1, ui.actions.count { it == "走访" })
        assertFalse(driver.pendingSubmission)
    }

    @Test fun gapWithoutConfirmationOrWithPendingSubmitStillRejects() = runTest {
        val ui = Ui { testScheduler.currentTime }
        val fence = Fence()
        val driver = AndroidVisitDriver(ui, VisitChoiceVerifier { _, _ -> }, fence, returnVision(), { testScheduler.currentTime })
        try { driver.validate(VisitStep.GAP); fail("No previous confirmation") }
        catch (e: VisitMismatch) { assertEquals("缺少上一轮结果确认，不能开始下一轮", e.reason) }
        fence.mark("pending", "fixture")
        try { driver.validate(VisitStep.GAP); fail("Pending result must be observed first") }
        catch (e: VisitMismatch) { assertEquals("仍有未确认提交，禁止开始下一轮", e.reason) }
        assertTrue(ui.actions.isEmpty()); assertTrue(driver.pendingSubmission)
    }

    @Test fun suppressedReturnParsingReasonIsRecordedWithoutRepeatingSubmit() = runTest {
        val ui = Ui { testScheduler.currentTime }.apply {
            listOverride = listTree().copy(nodes = listTree().nodes.map { if (it.text == "待办") it.copy(selected = false) else it })
        }
        val fence = Fence().apply { mark("previous-record", "fixture") }
        val events = mutableListOf<VisitDebugEvent>()
        val driver = AndroidVisitDriver(ui, VisitChoiceVerifier { _, _ -> }, fence, returnVision(),
            { testScheduler.currentTime }, diagnostics = VisitDiagnostics { events += it })
        val job = backgroundScope.launch { driver.execute(VisitStep.WAIT_LIST) }
        runCurrent(); advanceTimeBy(2_000); runCurrent()
        assertTrue(events.any { it.kind == VisitDebugKind.RETURN_CHECK && it.reason == "无法确认当前选中待办页签" })
        assertTrue(job.isActive); assertTrue(driver.pendingSubmission); assertTrue(ui.actions.isEmpty())
        job.cancelAndJoin()
    }

    @Test fun abnormalWithChangedFieldsReportsStateBeforeFieldMismatch() = runTest {
        val ui = Ui { testScheduler.currentTime }
        var visual = false
        val driver = AndroidVisitDriver(ui, VisitChoiceVerifier { _, _ ->
            visual = true
            throw VisitMismatch("视觉核验发现异常被选中，禁止继续")
        }, Fence(), returnVision(), { testScheduler.currentTime })
        driver.execute(VisitStep.FIND_LIST); driver.execute(VisitStep.OPEN_FORM)
        ui.form = ui.form.copy(nodes = ui.form.nodes.filterNot { it.path == "r/4/1" }.map { if (it.path == "r/6") it.copy(selected = false) else it })
        for (step in listOf(VisitStep.WAIT_FORM, VisitStep.VERIFY_FORM, VisitStep.LOCATE_DESCRIPTION, VisitStep.FILL_ADDRESS)) {
            try { driver.execute(step); fail("Abnormal must stop") } catch (e: VisitMismatch) { assertTrue(e.reason.contains("异常被选中")) }
        }
        try { driver.probe(); fail("Probe must report abnormal") } catch (e: VisitMismatch) { assertTrue(e.reason.contains("异常被选中")) }
        assertTrue(visual); assertEquals(listOf("走访"), ui.actions)
    }

    @Test fun uncertainVisualRetriesFreshReadsWithoutRepeatingActions() = runTest {
        val ui = Ui { testScheduler.currentTime }
        ui.form = ui.form.copy(nodes = ui.form.nodes.map { if (it.path == "r/6") it.copy(selected = false) else it })
        var attempts = 0
        val driver = AndroidVisitDriver(ui, VisitChoiceVerifier { _, _ -> if (++attempts < 3) throw VisitChoiceUncertain() }, Fence(), returnVision(), { testScheduler.currentTime })
        driver.execute(VisitStep.FIND_LIST); driver.execute(VisitStep.OPEN_FORM); driver.execute(VisitStep.WAIT_FORM)
        assertEquals(3, attempts); assertEquals(listOf("走访"), ui.actions)
    }

    @Test fun persistentVisualUncertaintyStopsAfterThreeObservations() = runTest {
        val ui = Ui { testScheduler.currentTime }.apply { opened = true }
        ui.form = ui.form.copy(nodes = ui.form.nodes.map { if (it.path == "r/6") it.copy(selected = false) else it })
        var attempts = 0
        val driver = AndroidVisitDriver(ui, VisitChoiceVerifier { _, _ -> attempts++; throw VisitChoiceUncertain() }, Fence(), returnVision(), { testScheduler.currentTime })
        try { driver.probe(); fail("Must remain uncertain") } catch (_: VisitChoiceUncertain) { }
        assertEquals(3, attempts); assertTrue(ui.actions.isEmpty())
    }

    @Test fun recordChangeDuringVisualRetryIsRejectedBeforeAnotherScreenshot() = runTest {
        val ui = Ui { testScheduler.currentTime }
        ui.form = ui.form.copy(nodes = ui.form.nodes.map { if (it.path == "r/6") it.copy(selected = false) else it })
        var attempts = 0
        val driver = AndroidVisitDriver(ui, VisitChoiceVerifier { _, _ ->
            attempts++
            ui.form = ui.form.copy(nodes = ui.form.nodes.map { if (it.path == "r/2/1") it.copy(text = "另一记录") else it })
            throw VisitChoiceUncertain()
        }, Fence(), returnVision(), { testScheduler.currentTime })
        driver.execute(VisitStep.FIND_LIST); driver.execute(VisitStep.OPEN_FORM)
        try { driver.execute(VisitStep.WAIT_FORM); fail("Record changed") } catch (e: VisitMismatch) { assertTrue(e.reason.contains("记录")) }
        assertEquals(1, attempts); assertEquals(listOf("走访"), ui.actions)
    }

    @Test fun confirmedEmptyListCompletesWithoutAnyActionOrTestCertification() = runTest {
        val ui = Ui { testScheduler.currentTime }.apply { submitted = true }
        val driver = AndroidVisitDriver(ui, VisitChoiceVerifier { _, _ -> }, Fence(), returnVision(), { testScheduler.currentTime })
        var certified = false
        val runner = VisitRunner(backgroundScope, driver, { testScheduler.currentTime }, onTestConfirmed = { certified = true })
        runner.start(plan, VisitExecution.STEP_TEST)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(VisitStatus.COMPLETED, runner.state.value.status)
        assertEquals("待办已处理完", runner.state.value.message)
        assertEquals(0L, runner.state.value.rounds)
        assertTrue(ui.actions.isEmpty()); assertFalse(certified)
    }
    @Test fun emptyListWithGrayOverlayCannotFinish() = runTest {
        val ui = Ui { testScheduler.currentTime }.apply { submitted = true }
        val blocked = VisitReturnVision { ReturnFrame(ReturnVisual.BLOCKED, testScheduler.currentTime) }
        val driver = AndroidVisitDriver(ui, VisitChoiceVerifier { _, _ -> }, Fence(), blocked, { testScheduler.currentTime })
        val runner = runner(driver)
        runner.start(plan, VisitExecution.FORMAL)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(VisitStatus.ERROR, runner.state.value.status)
        assertTrue(ui.actions.isEmpty()); assertEquals(0L, runner.state.value.rounds)
    }

    @Test fun formalRunWaitsForPublishedFormAndDelayedRemovalWithoutRepeatingActions() = runTest {
        val ui = Ui { testScheduler.currentTime }.apply { publicationDelay = 400; refreshDelay = 800 }
        val fence = Fence()
        val driver = AndroidVisitDriver(ui, VisitChoiceVerifier { _, _ -> fail("Explicit accessibility state needs no screenshot") }, fence, returnVision(), { testScheduler.currentTime })
        val runner = runner(driver)
        runner.start(plan, VisitExecution.FORMAL)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(VisitStatus.COMPLETED, runner.state.value.status)
        assertEquals(1L, runner.state.value.rounds)
        assertEquals(listOf("走访", "r/4/1", "r/5/1", "提交"), ui.actions)
        assertFalse(driver.pendingSubmission)
    }
    @Test fun visualReadCanPublishUnrelatedTextWithoutRejectingValidForm() = runTest {
        val ui = Ui { testScheduler.currentTime }.apply { form = form.copy(nodes = form.nodes.map { if (it.path == "r/6") it.copy(selected = false) else it }) }
        var screenshots = 0
        val driver = AndroidVisitDriver(ui, VisitChoiceVerifier { _, _ ->
            screenshots++
            ui.form = ui.form.copy(nodes = ui.form.nodes.map { if (it.path == "r/9") it.copy(text = "frame $screenshots") else it })
        }, Fence(), returnVision(), { testScheduler.currentTime })
        val runner = runner(driver); runner.start(plan, VisitExecution.FORMAL)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(VisitStatus.COMPLETED, runner.state.value.status)
        assertEquals(1, ui.actions.count { it == "提交" })
        assertTrue(screenshots > 0)
    }
    @Test fun oldRowDoesNotCountAsFailureOrSuccessBeforeRefreshAndPauseKeepsFence() = runTest {
        val ui = Ui { testScheduler.currentTime }.apply { refreshDelay = 60_000 }
        val fence = Fence()
        val driver = AndroidVisitDriver(ui, VisitChoiceVerifier { _, _ -> }, fence, returnVision(), { testScheduler.currentTime })
        val runner = runner(driver); runner.start(plan, VisitExecution.FORMAL)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(VisitStep.WAIT_LIST, runner.state.value.step)
        assertEquals(VisitStatus.RUNNING, runner.state.value.status)
        assertEquals(0L, runner.state.value.rounds); assertTrue(driver.pendingSubmission)
        runner.pause(); advanceTimeBy(60_000); runCurrent()
        assertEquals(1, ui.actions.count { it == "提交" })
        runner.resume(); advanceTimeBy(1_000); runCurrent()
        assertEquals(VisitStatus.COMPLETED, runner.state.value.status)
        assertEquals(1, ui.actions.count { it == "提交" })
    }
    @Test fun acceptedSubmissionWithLostCallbackIsOnlyObservedOnResume() = runTest {
        val ui = Ui { testScheduler.currentTime }.apply { failAfterSubmit = true }
        val driver = AndroidVisitDriver(ui, VisitChoiceVerifier { _, _ -> }, Fence(), returnVision(), { testScheduler.currentTime })
        val runner = runner(driver); runner.start(plan, VisitExecution.FORMAL)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(VisitStatus.ERROR, runner.state.value.status)
        assertEquals(VisitStep.WAIT_LIST, runner.state.value.step)
        runner.resume(); advanceTimeBy(1_000); runCurrent()
        assertEquals(VisitStatus.COMPLETED, runner.state.value.status)
        assertEquals(1, ui.actions.count { it == "提交" })
    }
    @Test fun conflictingFieldIsNeverOverwrittenOrSubmitted() = runTest {
        val ui = Ui { testScheduler.currentTime }.apply { form = form.copy(nodes = form.nodes.map { if (it.path == "r/4/1") it.copy(text = "existing") else it }) }
        val driver = AndroidVisitDriver(ui, VisitChoiceVerifier { _, _ -> }, Fence(), returnVision(), { testScheduler.currentTime })
        val runner = runner(driver); runner.start(plan, VisitExecution.FORMAL)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(VisitStatus.ERROR, runner.state.value.status)
        assertEquals(listOf("走访"), ui.actions)
        assertFalse(driver.pendingSubmission)
    }
    @Test fun loadingOverridesSuccessHintAndCountsFromFirstClearFrame() = runTest {
        val ui = Ui { testScheduler.currentTime }
        val fence = Fence { testScheduler.currentTime }
        var firstClear: Long? = null
        val driver = AndroidVisitDriver(ui, VisitChoiceVerifier { _, _ -> }, fence,
            VisitReturnVision {
                val at = testScheduler.currentTime
                val state = if (at < 8_000) ReturnVisual.BLOCKED else ReturnVisual.CLEAR.also { if (firstClear == null) firstClear = at }
                ReturnFrame(state, at)
            },
            { testScheduler.currentTime }, { _, _ -> true })
        val runner = runner(driver); runner.start(plan, VisitExecution.FORMAL)
        advanceTimeBy(7_000); runCurrent()
        assertEquals(0L, runner.state.value.rounds)
        assertTrue(driver.pendingSubmission)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(VisitStatus.COMPLETED, runner.state.value.status)
        assertTrue(firstClear!! in 8_000..8_999)
        assertEquals(firstClear, driver.lastConfirmation!!.anchorMs)
        assertEquals(61_000 - (testScheduler.currentTime - firstClear!!), driver.submissionWaitMs(61_000))
        assertEquals(1, ui.actions.count { it == "提交" })
    }
    @Test fun resultCanWaitBeyondThirtySecondsAndTimeoutResumeNeverResubmits() = runTest {
        val ui = Ui { testScheduler.currentTime }.apply { refreshDelay = 130_000 }
        val fence = Fence { testScheduler.currentTime }
        val driver = AndroidVisitDriver(ui, VisitChoiceVerifier { _, _ -> }, fence, returnVision(), { testScheduler.currentTime })
        val runner = runner(driver); runner.start(plan, VisitExecution.FORMAL)
        advanceTimeBy(35_000); runCurrent()
        assertEquals(VisitStatus.RUNNING, runner.state.value.status)
        assertTrue(driver.pendingSubmission)
        advanceTimeBy(90_000); runCurrent()
        assertEquals(VisitStatus.ERROR, runner.state.value.status)
        assertTrue(driver.pendingSubmission)
        advanceTimeBy(10_000); runCurrent()
        runner.resume(); advanceTimeBy(2_000); runCurrent()
        assertEquals(VisitStatus.COMPLETED, runner.state.value.status)
        assertEquals(1, ui.actions.count { it == "提交" })
        assertEquals(135_000L, driver.lastConfirmation!!.anchorMs)
        assertEquals(59_000L, driver.submissionWaitMs(61_000))
    }
}
