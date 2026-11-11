package cn.lighttap.app

import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Direct execution fixtures deliberately omit record identities and selected
 * state, and include search inputs, watermarks and incomplete bottom cards. */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidVisitDriverTest {
    private val screen = UiBox(0, 0, 720, 1604)
    private class Fence(private val now: () -> Long) : VisitSubmissionFence {
        override var hash: String? = null
        override var target: String? = null
        override var pendingClickMs: Long? = null
        var stamp: Long? = null
        var refuseClear = false
        override fun mark(hash: String, target: String): Boolean { this.hash = hash; this.target = target; return true }
        override fun clear(): Boolean { if (refuseClear) return false; hash = null; target = null; pendingClickMs = null; return true }
        override fun recordClick(at: Long): Boolean { pendingClickMs = at; stamp = at; return true }
        override fun recordSubmission(): Boolean { stamp = now(); return true }
        override fun confirmSubmission(at: Long): Boolean { stamp = at; return true }
        override fun submissionWaitMs(intervalMs: Long) = stamp?.let { (intervalMs - (now() - it)).coerceAtLeast(0) } ?: 0
    }
    private inner class Ui(private val now: () -> Long) : VisitUiAccess {
        override val target = "fixture"
        override var lastClickDispatchMs: Long? = null
        var opened = false
        var pendingUntil: Long? = null
        var replyDelay = 0L
        var popupUntil = 0L
        var rejectOnce = false
        var uncertainSubmit = false
        var callbackDelay = 0L
        var address = "原有内容"
        var description = ""
        var reads = 0
        val actions = mutableListOf<String>()
        fun list() = UiTree(target, 1, listOf(UiNode("r", bounds = screen),
            UiNode("r/tab", text = "待办"), // no selected marker, no completed tab
            UiNode("r/search", editable = true, showingHint = true, text = "请输入姓名"),
            UiNode("r/action", text = "走访", clickable = true, bounds = UiBox(500, 400, 680, 450)),
            UiNode("r/bottom", text = "不完整的底部记录"),
            UiNode("r/watermark", text = "样例水印${reads}")))
        fun form() = UiTree(target, 2, listOf(UiNode("r", bounds = screen), UiNode("r/title", text = "新增"),
            UiNode("r/address"), UiNode("r/address/label", text = "检查地址"),
            UiNode("r/address/input", editable = true, text = address, bounds = UiBox(20, 400, 680, 450)),
            UiNode("r/description"), UiNode("r/description/label", text = "走访描述"),
            UiNode("r/description/input", editable = true, text = description, bounds = UiBox(20, 600, 680, 800)),
            UiNode("r/abnormal", text = "异常", selected = true), // no choice check
            UiNode("r/submit", text = "提 交", clickable = true, bounds = UiBox(20, 1500, 700, 1580))))
        override suspend fun capture(): UiTree {
            reads++
            if (now() < popupUntil) return UiTree("notification", 99, listOf(UiNode("r", text = "通知")))
            if (pendingUntil?.let { now() >= it } == true) { pendingUntil = null; opened = false }
            return if (opened) form() else list()
        }
        override suspend fun perform(tree: UiTree, node: UiNode, action: Int, value: String?) {
            when {
                value != null -> { actions += node.path; if (node.path.contains("address")) address = value else description = value }
                canonicalLabel(node.readableText) == "走访" -> { actions += "走访"; opened = true }
                canonicalLabel(node.readableText) == "提交" -> {
                    if (rejectOnce) { rejectOnce = false; throw VisitPreActionMismatch("等待目标应用页面恢复") }
                    actions += "提交"; lastClickDispatchMs = now(); pendingUntil = now() + replyDelay
                    delay(callbackDelay)
                    if (uncertainSubmit) throw VisitMismatch("本次点击未确认完成，已暂停且不会自动重试")
                }
                else -> fail("Unexpected action")
            }
        }
    }
    private val plan = VisitSettings(targetPackage = "fixture")
    private fun TestScope.driver(ui: Ui, fence: Fence = Fence { testScheduler.currentTime }) =
        AndroidVisitDriver(ui, fence, { testScheduler.currentTime })

    @Test fun directRunDoesNotRequireIdentityChoiceWhitePixelsOrCompleteBottomRecords() = runTest {
        val ui = Ui { testScheduler.currentTime }
        val d = driver(ui)
        val runner = VisitRunner(backgroundScope, d, { testScheduler.currentTime })
        runner.start(plan, VisitExecution.FORMAL); advanceTimeBy(2_000); runCurrent()
        assertEquals(VisitStatus.COMPLETED, runner.state.value.status)
        assertEquals(1L, runner.state.value.rounds)
        assertEquals(listOf("走访", "r/address/input", "r/description/input", "提交"), ui.actions)
        assertEquals(VisitContract.ADDRESS, ui.address); assertEquals(VisitContract.DESCRIPTION, ui.description)
        assertFalse(d.pendingSubmission)
    }
    @Test fun eachInputIsWrittenDirectlyWithoutAnotherReadForVerification() = runTest {
        val ui = Ui { testScheduler.currentTime }.apply { opened = true }
        val d = driver(ui)
        d.execute(VisitStep.FILL_ADDRESS)
        assertEquals(1, ui.reads); assertEquals(VisitContract.ADDRESS, ui.address)
        d.execute(VisitStep.FILL_DESCRIPTION)
        assertEquals(2, ui.reads); assertEquals(VisitContract.DESCRIPTION, ui.description)
    }
    @Test fun stepTestLastStepFinishesOnFirstReadablePendingPage() = runTest {
        val ui = Ui { testScheduler.currentTime }; val d = driver(ui)
        val runner = VisitRunner(backgroundScope, d, { testScheduler.currentTime })
        runner.start(plan, VisitExecution.STEP_TEST); runCurrent()
        repeat(7) { runner.resume(); runCurrent() }
        assertEquals(VisitStep.WAIT_LIST, runner.state.value.step)
        assertEquals(1, ui.actions.count { it == "提交" })
        val before = ui.reads
        runner.resume(); runCurrent()
        assertEquals(VisitStatus.FINISH_CONFIRM, runner.state.value.status)
        assertEquals(1, ui.reads - before)
        assertEquals(1L, runner.state.value.rounds)
    }
    @Test fun longNotificationWaitsWithoutPausingAndShowsElapsedTimeThenContinues() = runTest {
        val ui = Ui { testScheduler.currentTime }.apply { popupUntil = 150_000 }
        val d = driver(ui); val runner = VisitRunner(backgroundScope, d, { testScheduler.currentTime })
        runner.start(plan, VisitExecution.FORMAL); advanceTimeBy(125_000); runCurrent()
        assertEquals(VisitStatus.RUNNING, runner.state.value.status)
        assertTrue(runner.state.value.message.contains("已等待 125 秒"))
        assertTrue(ui.actions.isEmpty())
        advanceTimeBy(28_000); runCurrent()
        assertEquals(VisitStatus.COMPLETED, runner.state.value.status)
        assertEquals(1, ui.actions.count { it == "提交" })
    }
    @Test fun finalReturnCanWaitThroughPopupWithoutTheOldTwoMinuteError() = runTest {
        val ui = Ui { testScheduler.currentTime }.apply { replyDelay = 150_000 }
        val d = driver(ui); val runner = VisitRunner(backgroundScope, d, { testScheduler.currentTime })
        runner.start(plan, VisitExecution.FORMAL); advanceTimeBy(125_000); runCurrent()
        assertEquals(VisitStep.WAIT_LIST, runner.state.value.step)
        assertTrue(runner.state.value.message.contains("不会重复提交"))
        assertTrue(runner.state.value.message.contains("已等待")); assertTrue(d.pendingSubmission)
        advanceTimeBy(28_000); runCurrent()
        assertEquals(VisitStatus.COMPLETED, runner.state.value.status)
        assertEquals(1, ui.actions.count { it == "提交" })
    }
    @Test fun pauseAndStopCancelWaitingWithoutDispatchingAfterwards() = runTest {
        val ui = Ui { testScheduler.currentTime }.apply { popupUntil = 100_000 }
        val runner = VisitRunner(backgroundScope, driver(ui), { testScheduler.currentTime })
        runner.start(plan, VisitExecution.FORMAL); runCurrent(); runner.pause()
        advanceTimeBy(110_000); runCurrent()
        assertEquals(VisitStatus.PAUSED, runner.state.value.status); assertTrue(ui.actions.isEmpty())
        runner.resume(); runCurrent(); runner.stop(); val count = ui.actions.size
        advanceTimeBy(120_000); runCurrent(); assertEquals(count, ui.actions.size)
    }
    @Test fun lostSubmissionCallbackNeverCausesAnotherSubmitOnResume() = runTest {
        val ui = Ui { testScheduler.currentTime }.apply { uncertainSubmit = true }
        val d = driver(ui); val runner = VisitRunner(backgroundScope, d, { testScheduler.currentTime })
        runner.start(plan, VisitExecution.FORMAL); runCurrent()
        assertEquals(VisitStatus.ERROR, runner.state.value.status); assertTrue(d.pendingSubmission)
        runner.resume(); runCurrent()
        assertEquals(VisitStatus.COMPLETED, runner.state.value.status)
        assertEquals(1, ui.actions.count { it == "提交" })
    }
    @Test fun provenUnsentPopupInterruptionAutomaticallyContinues() = runTest {
        val ui = Ui { testScheduler.currentTime }.apply { rejectOnce = true }
        val runner = VisitRunner(backgroundScope, driver(ui), { testScheduler.currentTime })
        runner.start(plan, VisitExecution.FORMAL); advanceTimeBy(2_000); runCurrent()
        assertEquals(VisitStatus.COMPLETED, runner.state.value.status)
        assertEquals(1, ui.actions.count { it == "提交" })
    }
    @Test fun resetClearsPendingProgressButKeepsSubmissionIntervalAndConfiguration() = runTest {
        val ui = Ui { testScheduler.currentTime }.apply { replyDelay = 150_000 }
        val fence = Fence { testScheduler.currentTime }; val d = driver(ui, fence)
        val runner = VisitRunner(backgroundScope, d, { testScheduler.currentTime })
        runner.start(plan, VisitExecution.FORMAL); advanceTimeBy(1_000); runCurrent()
        assertTrue(d.pendingSubmission)
        assertTrue(runner.clearRuntime())
        assertFalse(d.pendingSubmission); assertEquals(VisitStep.FIND_LIST, runner.state.value.step)
        assertEquals(VisitStatus.IDLE, runner.state.value.status); assertEquals(0L, runner.state.value.rounds)
        assertTrue(d.submissionWaitMs(61_000) > 0)
        val count = ui.actions.size; advanceTimeBy(5_000); runCurrent(); assertEquals(count, ui.actions.size)
        ui.opened = false; ui.pendingUntil = null; ui.replyDelay = 0
        runner.start(plan, VisitExecution.FORMAL); advanceTimeBy(65_000); runCurrent()
        assertEquals(VisitStatus.COMPLETED, runner.state.value.status)
        assertEquals(2, ui.actions.count { it == "提交" })
    }
    @Test fun resetFailureDoesNotPretendThePendingMarkerWasCleared() = runTest {
        val ui = Ui { testScheduler.currentTime }; val fence = Fence { testScheduler.currentTime }
        fence.mark("old", "fixture"); fence.refuseClear = true
        val runner = VisitRunner(backgroundScope, driver(ui, fence), { testScheduler.currentTime })
        assertFalse(runner.clearRuntime())
        assertTrue(runner.state.value.pendingSubmission); assertTrue(runner.state.value.message.contains("失败"))
        assertTrue(ui.actions.isEmpty())
    }
    @Test fun resetDuringAcceptedSubmitWaitsForCancellationAndPreservesCooldown() = runTest {
        val ui = Ui { testScheduler.currentTime }.apply { callbackDelay = 10_000 }
        val fence = Fence { testScheduler.currentTime }; val d = driver(ui, fence)
        val runner = VisitRunner(backgroundScope, d, { testScheduler.currentTime })
        runner.start(plan, VisitExecution.FORMAL); runCurrent()
        assertEquals(1, ui.actions.count { it == "提交" }); assertTrue(d.pendingSubmission)
        assertTrue(runner.clearRuntime())
        assertFalse(d.pendingSubmission)
        assertEquals(61_000L, d.submissionWaitMs(61_000))
        advanceTimeBy(20_000); runCurrent()
        assertEquals(1, ui.actions.count { it == "提交" }); assertEquals(VisitStatus.IDLE, runner.state.value.status)
    }
    @Test fun searchFieldAndWatermarkDoNotPreventReadingPendingList() {
        val tree = Ui { 0 }.list()
        assertTrue(DirectVisitControls.pendingPage(tree))
        assertEquals("r/action", DirectVisitControls.button(tree, "走访")!!.path)
    }
}
