package cn.lighttap.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenshotGateTest {
    @Test fun localScreenshotTimeoutHasItsOwnReasonAndReleasesTheGate() = runTest {
        val gate = ScreenshotGate({ testScheduler.currentTime })
        try { screenshotWithin(200) { gate.capture { delay(1_000); true } }; fail("Must time out") }
        catch (error: VisitMismatch) { assertEquals("本机截图等待超时，请查看后继续", error.reason) }
        assertTrue(screenshotWithin(1_000) { gate.capture { true } })
        assertEquals(700L, testScheduler.currentTime)
    }

    @Test fun outerCancellationIsNeverReportedAsALocalScreenshotTimeout() = runTest {
        var reported = false
        val job = launch {
            try { screenshotWithin(2_000) { delay(5_000); true } }
            catch (_: VisitMismatch) { reported = true }
        }
        advanceTimeBy(100); job.cancelAndJoin()
        assertFalse(reported)
    }
    @Test fun preparingAnObservationWaitsButDoesNotTakeOrReserveAnImage() = runTest {
        val gate = ScreenshotGate({ testScheduler.currentTime })
        var images = 0
        gate.capture { images++ }
        gate.awaitReady()
        assertEquals(500L, testScheduler.currentTime)
        assertEquals(1, images)
        gate.awaitReady()
        gate.capture { images++ }
        assertEquals(500L, testScheduler.currentTime)
        assertEquals(2, images)
    }
    @Test fun differentReadersSerializeAndWaitOnlyForTheRemainingCooldown() = runTest {
        val gate = ScreenshotGate({ testScheduler.currentTime })
        val starts = mutableListOf<Long>()
        val first = launch { gate.capture { starts += testScheduler.currentTime; delay(100) } }
        val second = launch { gate.capture { starts += testScheduler.currentTime } }
        joinAll(first, second)
        assertEquals(listOf(0L, 600L), starts)
        delay(600)
        gate.capture { starts += testScheduler.currentTime }
        assertEquals(1200L, starts.last())
    }

    @Test fun failedRequestsStillHaveACooldownAndDoNotBlockTheNextReader() = runTest {
        val gate = ScreenshotGate({ testScheduler.currentTime })
        try { gate.capture<Unit> { delay(100); error("capture failed") }; fail() } catch (_: IllegalStateException) { }
        gate.capture { assertEquals(600L, testScheduler.currentTime) }
    }

    @Test fun cancellingAWaitingReaderNeverCapturesOrConsumesAnotherSlot() = runTest {
        val gate = ScreenshotGate({ testScheduler.currentTime })
        gate.capture { }
        val pending = launch { gate.capture { fail("Cancelled reader captured") } }
        advanceTimeBy(200); pending.cancelAndJoin()
        gate.capture { assertEquals(500L, testScheduler.currentTime) }
    }
}
