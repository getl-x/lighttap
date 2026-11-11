package cn.lighttap.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VisitRunnerTest {
    private val plan = VisitSettings(targetPackage = "fixture.app")
    private class Driver : VisitDriver {
        override var pendingSubmission = false
        val actions = mutableListOf<VisitStep>()
        var rejected: VisitStep? = null
        var rejectedValidation: VisitStep? = null
        var latency = 0L
        var scrollRepeats = 0
        var fenceThenFail = false
        val submissions = mutableListOf<Long>()
        var time: () -> Long = { 0 }
        override suspend fun begin() = Unit
        override suspend fun validate(step: VisitStep) { if (step == rejectedValidation) throw VisitMismatch("校验不匹配") }
        override suspend fun execute(step: VisitStep): VisitEffect {
            if (step == rejected) throw VisitMismatch("步骤不匹配")
            actions += step
            if (step == VisitStep.SUBMIT) { submissions += time(); pendingSubmission = true; if (fenceThenFail) throw VisitMismatch("提交结果不明确") }
            delay(latency)
            if (step == VisitStep.WAIT_LIST) pendingSubmission = false
            if (step == VisitStep.LOCATE_DESCRIPTION && scrollRepeats-- > 0) return VisitEffect(repeat = true)
            return VisitEffect()
        }
        override fun release() = Unit
    }
    private fun TestScope.runner(driver: Driver, errors: MutableList<String> = mutableListOf(), confirmed: () -> Unit = {}): VisitRunner {
        driver.time = { testScheduler.currentTime }
        return VisitRunner(backgroundScope, driver, { testScheduler.currentTime }, { errors += it }, confirmed)
    }
    @Test fun stepModeExecutesExactlyOneStepForEachContinue() = runTest {
        val d = Driver(); val e = runner(d)
        e.start(plan, VisitExecution.STEP_TEST); runCurrent()
        for (count in 1..9) {
            assertEquals(count, d.actions.size)
            assertEquals(if (count == 9) VisitStatus.FINISH_CONFIRM else VisitStatus.CHECKPOINT, e.state.value.status)
            advanceTimeBy(100_000); runCurrent(); assertEquals(count, d.actions.size)
            if (count < 9) { e.resume(); runCurrent() }
        }
        assertEquals(1L, e.state.value.rounds)
        assertFalse(d.actions.contains(VisitStep.GAP))
    }
    @Test fun addressAndDescriptionAreSeparateCheckpoints() = runTest {
        val d = Driver(); val e = runner(d); e.start(plan, VisitExecution.STEP_TEST); runCurrent()
        repeat(3) { e.resume(); runCurrent() }
        assertEquals(VisitStep.FILL_ADDRESS, d.actions.last())
        assertFalse(d.actions.contains(VisitStep.FILL_DESCRIPTION))
        e.resume(); runCurrent(); assertEquals(VisitStep.LOCATE_DESCRIPTION, d.actions.last())
        e.resume(); runCurrent(); assertEquals(VisitStep.FILL_DESCRIPTION, d.actions.last())
    }
    @Test fun eachScrollHasItsOwnCheckpoint() = runTest {
        val d = Driver().apply { scrollRepeats = 2 }; val e = runner(d)
        e.start(plan, VisitExecution.STEP_TEST); runCurrent(); repeat(3) { e.resume(); runCurrent() }
        repeat(3) { e.resume(); runCurrent(); assertEquals(VisitStep.LOCATE_DESCRIPTION, d.actions.last()); assertEquals(VisitStatus.CHECKPOINT, e.state.value.status) }
        assertFalse(d.actions.contains(VisitStep.FILL_DESCRIPTION))
    }
    @Test fun formalAndTestUseIdenticalActionGraph() = runTest {
        val a = Driver(); val b = Driver(); val formal = runner(a); val test = runner(b)
        formal.start(plan, VisitExecution.FORMAL); runCurrent()
        test.start(plan, VisitExecution.STEP_TEST); runCurrent(); repeat(8) { test.resume(); runCurrent() }
        assertEquals(a.actions, b.actions); assertEquals(VisitStatus.COMPLETED, formal.state.value.status)
    }
    @Test fun defaultIntervalPreparesNextFormAndSubmitsOnlyAfterSixtyOneSeconds() = runTest {
        val d = Driver(); val e = runner(d)
        e.start(plan.copy(mode = VisitRunMode.COUNT, countText = "2"), VisitExecution.FORMAL); runCurrent()
        assertEquals(VisitStatus.WAITING, e.state.value.status); assertEquals(1L, e.state.value.rounds)
        assertEquals(VisitStep.SUBMIT, e.state.value.step)
        assertEquals(61_000L, e.state.value.remainingWaitMs)
        assertEquals(2, d.actions.count { it == VisitStep.FILL_DESCRIPTION })
        val count = d.actions.size
        advanceTimeBy(60_999); runCurrent(); assertEquals(count, d.actions.size)
        advanceTimeBy(1); runCurrent(); assertEquals(2L, e.state.value.rounds); assertEquals(VisitStatus.COMPLETED, e.state.value.status)
        assertEquals(listOf(0L, 61_000L), d.submissions)
    }
    @Test fun processingTimeCountsTowardIntervalWithoutAnExtraMinuteAfterReturn() = runTest {
        val d = Driver(); val e = runner(d)
        d.latency = 1_000
        e.start(plan.copy(mode = VisitRunMode.COUNT, countText = "2"), VisitExecution.FORMAL); runCurrent()
        advanceTimeBy(80_000); runCurrent()
        assertEquals(18, d.actions.size); assertEquals(2L, e.state.value.rounds)
        assertEquals(62_000L, d.submissions[1] - d.submissions[0])
    }
    @Test fun pauseKeepsCooldownRunningButNeverDispatchesUntilResumed() = runTest {
        val d = Driver(); val e = runner(d)
        e.start(plan.copy(mode = VisitRunMode.COUNT, countText = "2"), VisitExecution.FORMAL); runCurrent()
        advanceTimeBy(12_000); runCurrent(); e.pause("锁屏")
        assertEquals(49_000L, e.state.value.remainingWaitMs)
        val count = d.actions.size
        advanceTimeBy(300_000); runCurrent(); assertEquals(count, d.actions.size)
        e.resume(); runCurrent(); assertEquals(18, d.actions.size)
        assertEquals(VisitStatus.COMPLETED, e.state.value.status)
    }
    @Test fun stopAndImmediateRestartCannotBypassSubmitCooldown() = runTest {
        val d = Driver(); val e = runner(d)
        e.start(plan, VisitExecution.FORMAL); runCurrent(); e.stop()
        e.start(plan, VisitExecution.FORMAL); runCurrent()
        assertEquals(1, d.submissions.size)
        advanceTimeBy(60_999); runCurrent(); assertEquals(1, d.submissions.size)
        advanceTimeBy(1); runCurrent(); assertEquals(2, d.submissions.size)
    }
    @Test fun failedSubmitDoesNotStartGapAndNeverSubmitsAgainOnResume() = runTest {
        val d = Driver().apply { fenceThenFail = true }; val e = runner(d)
        e.start(plan.copy(mode = VisitRunMode.CONTINUOUS), VisitExecution.FORMAL); runCurrent()
        assertEquals(VisitStatus.ERROR, e.state.value.status); assertEquals(VisitStep.WAIT_LIST, e.state.value.step)
        assertEquals(0L, e.state.value.rounds); assertEquals(0L, e.state.value.remainingWaitMs)
        e.resume(); runCurrent()
        assertEquals(1, d.actions.count { it == VisitStep.SUBMIT }); assertEquals(1L, e.state.value.rounds)
    }
    @Test fun existingSubmissionFenceStartsWithVerificationOnly() = runTest {
        val d = Driver().apply { pendingSubmission = true }; val e = runner(d)
        e.start(plan, VisitExecution.STEP_TEST); runCurrent()
        assertEquals(listOf(VisitStep.WAIT_LIST), d.actions); assertEquals(VisitStatus.FINISH_CONFIRM, e.state.value.status)
    }
    @Test fun everyMismatchStopsWithoutExecutingAnotherAction() = runTest {
        for (failed in VisitStep.entries.filter { it != VisitStep.GAP }) {
            val d = Driver().apply { rejected = failed }; val e = runner(d)
            e.start(plan, VisitExecution.FORMAL); runCurrent()
            assertEquals(VisitStatus.ERROR, e.state.value.status)
            assertFalse(d.actions.contains(failed)); val count = d.actions.size
            advanceTimeBy(120_000); runCurrent(); assertEquals(count, d.actions.size); e.stop()
        }
    }
    @Test fun validationFailureNeverDispatchesStep() = runTest {
        val d = Driver().apply { rejectedValidation = VisitStep.FILL_ADDRESS }; val e = runner(d)
        e.start(plan, VisitExecution.FORMAL); runCurrent()
        assertEquals(VisitStatus.ERROR, e.state.value.status); assertFalse(d.actions.contains(VisitStep.FILL_ADDRESS))
    }
    @Test fun duplicateStartOrContinueCannotReleaseTwoSteps() = runTest {
        val d = Driver().apply { latency = 100 }; val e = runner(d)
        e.start(plan, VisitExecution.STEP_TEST); e.start(plan, VisitExecution.FORMAL); runCurrent()
        e.resume(); e.resume(); advanceTimeBy(100); runCurrent(); assertEquals(1, d.actions.size)
        e.resume(); e.resume(); runCurrent(); advanceTimeBy(100); runCurrent(); assertEquals(2, d.actions.size)
    }
    @Test fun userCheckpointTimeDoesNotConsumeNextStepTimeout() = runTest {
        val d = Driver().apply { latency = 29_000 }; val e = runner(d)
        e.start(plan, VisitExecution.STEP_TEST); runCurrent(); advanceTimeBy(29_000); runCurrent()
        advanceTimeBy(300_000); runCurrent(); e.resume(); runCurrent(); advanceTimeBy(29_000); runCurrent()
        assertEquals(VisitStatus.CHECKPOINT, e.state.value.status); assertEquals(2, d.actions.size)
    }
    @Test fun timeoutPausesAndDoesNotRetry() = runTest {
        val d = Driver().apply { latency = 31_000 }; val e = runner(d)
        e.start(plan, VisitExecution.FORMAL); runCurrent(); advanceTimeBy(30_100); runCurrent()
        assertEquals(VisitStatus.ERROR, e.state.value.status); advanceTimeBy(60_000); runCurrent(); assertEquals(1, d.actions.size)
    }
    @Test fun innerCancellationCannotLeaveAStoppedWorkerDisplayedAsRunning() = runTest {
        var cancelledOnce = false
        var submissions = 0
        val d = object : VisitDriver {
            override var pendingSubmission = false
            override suspend fun begin() = Unit
            override suspend fun validate(step: VisitStep) = Unit
            override suspend fun execute(step: VisitStep): VisitEffect {
                if (step == VisitStep.SUBMIT) { submissions++; pendingSubmission = true }
                if (step == VisitStep.FILL_ADDRESS && !cancelledOnce) {
                    cancelledOnce = true; throw CancellationException("Generated inner cancellation")
                }
                if (step == VisitStep.WAIT_LIST) pendingSubmission = false
                return VisitEffect()
            }
            override fun release() = Unit
        }
        val e = VisitRunner(backgroundScope, d, { testScheduler.currentTime })
        e.start(plan, VisitExecution.FORMAL); runCurrent()
        assertEquals(VisitStatus.ERROR, e.state.value.status)
        assertFalse(e.state.value.busy)
        assertEquals(0, submissions)
        e.resume(); runCurrent()
        assertEquals(VisitStatus.COMPLETED, e.state.value.status)
        assertEquals(1, submissions)
    }
    @Test fun cancellationAfterSubmissionPreservesTheFenceAndResumeOnlyChecksResult() = runTest {
        var submissions = 0
        val d = object : VisitDriver {
            override var pendingSubmission = false
            override suspend fun begin() = Unit
            override suspend fun validate(step: VisitStep) = Unit
            override suspend fun execute(step: VisitStep): VisitEffect {
                if (step == VisitStep.SUBMIT) {
                    submissions++; pendingSubmission = true
                    throw CancellationException("Generated uncertain callback cancellation")
                }
                if (step == VisitStep.WAIT_LIST) pendingSubmission = false
                return VisitEffect()
            }
            override fun release() = Unit
        }
        val e = VisitRunner(backgroundScope, d, { testScheduler.currentTime })
        e.start(plan, VisitExecution.FORMAL); runCurrent()
        assertEquals(VisitStatus.ERROR, e.state.value.status)
        assertEquals(VisitStep.WAIT_LIST, e.state.value.step)
        assertTrue(d.pendingSubmission)
        e.resume(); runCurrent()
        assertEquals(VisitStatus.COMPLETED, e.state.value.status)
        assertEquals(1, submissions)
    }
    @Test fun stopResetsCountAndCountdown() = runTest {
        val d = Driver(); val e = runner(d)
        e.start(plan.copy(mode = VisitRunMode.CONTINUOUS), VisitExecution.FORMAL); runCurrent(); e.stop()
        val count = d.actions.size
        advanceTimeBy(120_000); runCurrent(); assertEquals(count, d.actions.size)
        assertEquals(0L, e.state.value.rounds); assertEquals(0L, e.state.value.remainingWaitMs)
    }
    @Test fun finalTestRequiresExplicitConfirmation() = runTest {
        var confirmed = 0; val d = Driver(); val e = runner(d, confirmed = { confirmed++ })
        e.start(plan, VisitExecution.STEP_TEST); runCurrent(); repeat(8) { e.resume(); runCurrent() }
        assertEquals(0, confirmed); e.resume(); runCurrent(); assertEquals(0, confirmed)
        e.finishTest(); assertEquals(1, confirmed); assertEquals(VisitStatus.COMPLETED, e.state.value.status)
    }
    @Test fun invalidWaitOrCountCannotStart() = runTest {
        for (value in listOf("", "0", "1", "60", "-1", "1.2", "86401", "abc", " 62", "999999999999")) {
            val d = Driver(); val e = runner(d)
            e.start(plan.copy(waitText = value), VisitExecution.FORMAL); runCurrent(); assertTrue(d.actions.isEmpty())
        }
        assertNull(plan.copy(waitText = "61").error()); assertNull(plan.copy(waitText = "86400").error())
        assertNotNull(plan.copy(mode = VisitRunMode.COUNT, countText = "0").error())
        assertEquals("01:02", countdown(62_000))
    }
}
