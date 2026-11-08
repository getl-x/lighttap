package cn.lighttap.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadOnlyProbeTest {
    @Test fun timeoutAndStopBothResolveProgressAndAllowAnotherCheck() = runTest {
        var status = ""
        var slow = true
        val finished = mutableListOf<String>()
        val probe = ReadOnlyProbe(backgroundScope, { status = it }, { finished += it }) {
            if (slow) delay(10_000)
            "检查完成"
        }
        probe.start(); runCurrent(); probe.stop(); runCurrent()
        assertTrue(status.contains("取消")); assertFalse(probe.active); assertTrue(finished.isEmpty())
        probe.start(); advanceTimeBy(5_000); runCurrent()
        assertTrue(status.contains("超时")); assertFalse(probe.active); assertEquals(1, finished.size)
        slow = false
        probe.start(); runCurrent()
        assertEquals("检查完成", status); assertFalse(probe.active)
        probe.stop(); assertEquals("检查完成", status)
    }

    @Test fun cancelledSlowCheckCannotOverwriteReplacement() = runTest {
        var status = ""
        var calls = 0
        val probe = ReadOnlyProbe(backgroundScope, { status = it }, {}) {
            if (++calls == 1) withContext(NonCancellable) { delay(1_000); "过期结果" }
            else { delay(2_000); "新结果" }
        }
        probe.start(); runCurrent(); probe.stop(); probe.start(); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertTrue(status.startsWith("正在")); assertTrue(probe.active)
        advanceTimeBy(1_000); runCurrent()
        assertEquals("新结果", status)
    }

    @Test fun disconnectCancellationResolvesProgressWithoutCompletionNotice() = runTest {
        var status = ""
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val probe = ReadOnlyProbe(owner, { status = it }, { fail("Cancelled check must not notify success") }) { awaitCancellation() }
        probe.start(); runCurrent(); owner.cancel(); runCurrent()
        assertTrue(status.contains("取消")); assertFalse(probe.active)
    }

    @Test fun immediateCompletionIsNotLaterReportedAsCancellation() = runTest {
        var status = ""
        val probe = ReadOnlyProbe(CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)), { status = it }, {}) { "已完成" }
        probe.start(); probe.stop()
        assertEquals("已完成", status)
    }
}
