package cn.lighttap.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VisitSubmissionPreparationTest {
    private class Driver(private val time: () -> Long) : VisitDriver {
        override var pendingSubmission = false
        override val submissionPreparationLeadMs = 2_000L
        var cost = 1_000L
        var prepared = false
        var invalidations = 0
        val preparations = mutableListOf<Long>()
        val clicks = mutableListOf<Long>()
        override suspend fun begin() = Unit
        override suspend fun validate(step: VisitStep) = Unit
        override suspend fun prepareSubmission() {
            preparations += time(); prepared = false; delay(cost); prepared = true
        }
        override fun invalidatePrepared() { invalidations++; prepared = false }
        override suspend fun execute(step: VisitStep): VisitEffect {
            if (step == VisitStep.SUBMIT) { check(prepared); clicks += time(); prepared = false; pendingSubmission = true }
            if (step == VisitStep.WAIT_LIST) pendingSubmission = false
            return VisitEffect()
        }
        override fun release() { invalidatePrepared() }
    }
    private val plan = VisitSettings(targetPackage = "fixture", mode = VisitRunMode.COUNT, countText = "2")
    @Test fun costlyPreparationOverlapsIntervalButNeverDispatchesEarly() = runTest {
        val driver = Driver { testScheduler.currentTime }
        val runner = VisitRunner(backgroundScope, driver, { testScheduler.currentTime })
        runner.start(plan, VisitExecution.FORMAL)
        advanceTimeBy(61_999); runCurrent()
        assertEquals(listOf(1_000L), driver.clicks)
        assertEquals(listOf(0L, 60_000L), driver.preparations)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(1_000L, 62_000L), driver.clicks)
        assertEquals(VisitStatus.COMPLETED, runner.state.value.status)
    }
    @Test fun slowPreparationMayDelayTheClickButNeverShortensTheInterval() = runTest {
        val driver = Driver { testScheduler.currentTime }.apply { cost = 3_000 }
        val runner = VisitRunner(backgroundScope, driver, { testScheduler.currentTime })
        runner.start(plan, VisitExecution.FORMAL); advanceTimeBy(70_000); runCurrent()
        assertEquals(2, driver.clicks.size)
        assertTrue(driver.clicks[1] - driver.clicks[0] >= 61_000)
        assertEquals(VisitStatus.COMPLETED, runner.state.value.status)
    }
    @Test fun pauseDuringPreparationCancelsAndInvalidatesIt() = runTest {
        val driver = Driver { testScheduler.currentTime }
        val runner = VisitRunner(backgroundScope, driver, { testScheduler.currentTime })
        runner.start(plan, VisitExecution.FORMAL); advanceTimeBy(60_500); runCurrent()
        runner.pause(); runCurrent()
        assertFalse(driver.prepared)
        advanceTimeBy(100_000); runCurrent()
        assertEquals(1, driver.clicks.size)
        runner.resume(); advanceTimeBy(1_000); runCurrent()
        assertEquals(2, driver.clicks.size)
        assertEquals(3, driver.preparations.size)
        assertTrue(driver.invalidations >= 1)
    }
    @Test fun preparationTimeoutDoesNotMarkOrDispatchSubmission() = runTest {
        val driver = Driver { testScheduler.currentTime }.apply { cost = 31_000 }
        val runner = VisitRunner(backgroundScope, driver, { testScheduler.currentTime })
        runner.start(plan, VisitExecution.FORMAL); advanceTimeBy(31_000); runCurrent()
        assertEquals(VisitStatus.ERROR, runner.state.value.status)
        assertTrue(driver.clicks.isEmpty())
        assertFalse(driver.pendingSubmission)
        assertFalse(driver.prepared)
    }
}
