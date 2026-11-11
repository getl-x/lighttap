package cn.lighttap.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Virtual time, generated records only. Exercise the production driver and
 * runner together under slow/partial reads, loading and rejected dispatch. */
@OptIn(ExperimentalCoroutinesApi::class)
class VisitLongRunRecoveryTest {
    @Test fun sixtyRoundsSurviveMixedSlowReadsLoadingAndExplicitRecoveryWithoutDuplicates() = runTest {
        val count = 60
        val now = { testScheduler.currentTime }
        val screen = UiBox(0, 0, 720, 1600)
        var index = 0
        var opened = false
        var address = ""
        var description = ""
        var replyAt: Long? = null
        var reads = 0
        var rejected = 0
        val rejectedRecords = mutableSetOf<Int>()
        val clickedRecords = mutableListOf<Int>()
        val clicks = mutableListOf<Long>()
        val fence = object : VisitSubmissionFence {
            override var hash: String? = null
            override var target: String? = null
            override var pendingClickMs: Long? = null
            var stamp: Long? = null
            override fun mark(hash: String, target: String): Boolean { this.hash = hash; this.target = target; return true }
            override fun clear(): Boolean { hash = null; target = null; pendingClickMs = null; return true }
            override fun recordClick(at: Long): Boolean { pendingClickMs = at; stamp = at; return true }
            override fun confirmSubmission(at: Long): Boolean { stamp = at; return true }
            override fun submissionWaitMs(intervalMs: Long) = stamp?.let { (intervalMs - (now() - it)).coerceAtLeast(0) } ?: 0
        }
        fun tree(): UiTree {
            val name = "样例${('一'.code + index).toChar()}"
            val root = listOf(UiNode("r", bounds = screen))
            val nodes = if (opened) listOf(
                UiNode("r/0", text = "新增"), UiNode("r/1", text = "走访记录"),
                UiNode("r/2"), UiNode("r/2/0", text = "姓名"), UiNode("r/2/1", text = name),
                UiNode("r/3"), UiNode("r/3/0", text = "居住地址"), UiNode("r/3/1", text = "测试地址"),
                UiNode("r/4"), UiNode("r/4/0", text = "检查地址"), UiNode("r/4/1", text = address, editable = true),
                UiNode("r/5"), UiNode("r/5/0", text = "走访描述"), UiNode("r/5/1", text = description, editable = true),
                UiNode("r/6", text = "正常", selected = true, clickable = true), UiNode("r/7", text = "异常", clickable = true),
                UiNode("r/8", text = "提交", clickable = true),
            ) else listOf(UiNode("r/0", text = "待办", selected = true), UiNode("r/1", text = "已完成")) +
                if (index == count) listOf(UiNode("r/empty", text = "暂无待办")) else listOf(
                    UiNode("r/2", bounds = UiBox(0, 100, 700, 500)),
                    UiNode("r/2/0", text = name, bounds = UiBox(20, 120, 120, 160)),
                    UiNode("r/2/1", text = "身份证：示例", bounds = UiBox(20, 170, 300, 200)),
                    UiNode("r/2/2", text = "居住地址：测试地址", bounds = UiBox(20, 220, 500, 260)),
                    UiNode("r/2/3", text = "0/1"), UiNode("r/2/4", text = "走访记录", clickable = true),
                    UiNode("r/2/5", text = "走访", clickable = true),
                )
            return UiTree("fixture", if (opened) 2 else 1, root + nodes + UiNode("r/timer", text = "倒计时${90000 - reads}天"))
        }
        val bridge = object : VisitUiAccess {
            override val target = "fixture"
            override var lastClickDispatchMs: Long? = null
            override suspend fun capture(): UiTree {
                reads++
                delay(listOf(90L, 700L, 1300L, 180L)[reads % 4])
                if (reads % 17 == 0) throw VisitReadPending("界面结构正在变化")
                if (replyAt?.let { now() >= it } == true) { replyAt = null; opened = false; index++ }
                return tree()
            }
            override suspend fun perform(tree: UiTree, node: UiNode, action: Int, value: String?) {
                currentCoroutineContext().ensureActive()
                when {
                    value != null && node.path == "r/4/1" -> address = value
                    value != null && node.path == "r/5/1" -> description = value
                    node.text == "走访" -> { opened = true; address = ""; description = "" }
                    node.text == "提交" -> {
                        if (index % 7 == 0 && rejectedRecords.add(index)) { rejected++; throw VisitPreActionMismatch("执行前页面发生变化，已暂停") }
                        assertEquals(VisitContract.ADDRESS, address); assertEquals(VisitContract.DESCRIPTION, description)
                        assertFalse(clickedRecords.contains(index))
                        lastClickDispatchMs = now(); clicks += now(); clickedRecords += index
                        replyAt = now() + if (index % 3 == 0) 5_000 else 0
                    }
                    else -> fail("Unexpected generated action")
                }
            }
        }
        val driver = AndroidVisitDriver(bridge, VisitChoiceVerifier { _, _ -> fail("Explicit normal needs no fallback") }, fence,
            VisitReturnVision { ReturnFrame(if (replyAt != null) ReturnVisual.BLOCKED else ReturnVisual.CLEAR, now()) }, now)
        val runner = VisitRunner(backgroundScope, driver, now)
        runner.start(VisitSettings(targetPackage = "fixture", mode = VisitRunMode.COUNT, countText = count.toString()), VisitExecution.FORMAL)
        var recoveries = 0
        repeat(500) {
            if (runner.state.value.status != VisitStatus.COMPLETED) {
                advanceTimeBy(10_000); runCurrent()
                if (runner.state.value.status == VisitStatus.ERROR) {
                    assertEquals("Only the injected pre-action rejection may pause", "执行前页面发生变化，已暂停", runner.state.value.message)
                    assertEquals(VisitStep.SUBMIT, runner.state.value.step); assertFalse(driver.pendingSubmission)
                    recoveries++; runner.resume()
                }
            }
        }
        assertEquals(VisitStatus.COMPLETED, runner.state.value.status)
        assertEquals(count.toLong(), runner.state.value.rounds)
        assertEquals((0 until count).toList(), clickedRecords)
        assertEquals(9, rejected); assertEquals(rejected, recoveries)
        assertTrue(clicks.zipWithNext().all { (a, b) -> b - a >= 61_000 })
        assertFalse(driver.pendingSubmission)
        runner.stop()
    }
}
