package cn.lighttap.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompletionReminderTest {
    private val point = ClickPoint(1, 10f, 10f, intervalMs = 100)
    private val plan = VisitSettings(targetPackage = "fixture")

    private class Driver : VisitDriver {
        override var pendingSubmission = false
        var empty = false
        var reject = false
        var rounds = 0
        override suspend fun begin() = Unit
        override suspend fun validate(step: VisitStep) {
            if (reject) throw VisitMismatch("fixture mismatch")
        }
        override suspend fun execute(step: VisitStep): VisitEffect {
            if (step == VisitStep.SUBMIT) pendingSubmission = true
            if (step == VisitStep.WAIT_LIST) { pendingSubmission = false; rounds++ }
            return VisitEffect(emptyList = empty && step == VisitStep.FIND_LIST)
        }
        override fun release() = Unit
    }

    @Test fun ordinaryRoundLimitsNotifyOnceInEveryClickModeAndCanRunAgain() = runTest {
        for (mode in ClickMode.entries) {
            var completed = 0
            val engine = ClickEngine(backgroundScope, TapDispatcher { true }, { testScheduler.currentTime }, { completed++ })
            val config = ClickConfig(mode = mode, points = listOf(point, point.copy(id = 2)), stopMode = StopMode.ROUNDS, roundLimit = 2)
            repeat(2) { run ->
                engine.start(config); advanceTimeBy(1_000); runCurrent()
                assertEquals(RunStatus.IDLE, engine.state.value.status)
                assertEquals(2L, engine.state.value.rounds)
                engine.pause(); engine.resume(); engine.stop(); runCurrent()
                assertEquals(run + 1, completed)
            }
        }
    }

    @Test fun ordinaryDurationDeadlineNotifiesOnceEvenDuringInFlightGesture() = runTest {
        var completed = 0
        val engine = ClickEngine(backgroundScope, TapDispatcher { delay(2_000); true }, { testScheduler.currentTime }, { completed++ })
        engine.start(ClickConfig(points = listOf(point), stopMode = StopMode.DURATION, durationMs = 1_000))
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, completed)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, completed)
        assertEquals(RunStatus.IDLE, engine.state.value.status)
    }

    @Test fun ordinaryFinalDispatchedGestureCanFinishAfterPause() = runTest {
        var completed = 0
        val engine = ClickEngine(backgroundScope, TapDispatcher { delay(50); true }, { testScheduler.currentTime }, { completed++ })
        engine.start(ClickConfig(points = listOf(point), stopMode = StopMode.ROUNDS, roundLimit = 1)); runCurrent()
        engine.pause()
        assertEquals(0, completed)
        advanceTimeBy(50); runCurrent()
        assertEquals(RunStatus.IDLE, engine.state.value.status)
        assertEquals(1L, engine.state.value.rounds)
        assertEquals(1, completed)
    }

    @Test fun ordinaryPauseManualStopFailureAndShutdownNeverNotify() = runTest {
        var completed = 0
        val engine = ClickEngine(backgroundScope, TapDispatcher { true }, { testScheduler.currentTime }, { completed++ })
        engine.start(ClickConfig(points = listOf(point))); runCurrent()
        engine.pause(); advanceTimeBy(5_000); runCurrent(); engine.stop()
        engine.start(ClickConfig(points = listOf(point))); runCurrent(); engine.fail("service disconnected")
        val rejected = ClickEngine(backgroundScope, TapDispatcher { false }, { testScheduler.currentTime }, { completed++ })
        rejected.start(ClickConfig(points = listOf(point))); advanceTimeBy(1_000); runCurrent()
        assertEquals(RunStatus.ERROR, rejected.state.value.status)
        assertEquals(0, completed)
    }

    @Test fun formalOnceAndCountNotifyOnlyAfterFinalConfirmedRound() = runTest {
        for (limit in listOf(1, 3)) {
            var completed = 0
            val driver = Driver()
            val runner = VisitRunner(backgroundScope, driver, { testScheduler.currentTime }, onCompleted = { completed++ })
            runner.start(plan.copy(mode = if (limit == 1) VisitRunMode.ONCE else VisitRunMode.COUNT, countText = limit.toString()), VisitExecution.FORMAL)
            runCurrent()
            assertEquals(if (limit == 1) 1 else 0, completed)
            advanceTimeBy(180_000); runCurrent()
            assertEquals(limit, driver.rounds)
            assertEquals(VisitStatus.COMPLETED, runner.state.value.status)
            assertEquals(1, completed)
            runner.finishTest(); runner.resume(); runner.stop(); advanceTimeBy(60_000); runCurrent()
            assertEquals(1, completed)
        }
    }

    @Test fun emptyListCompletesContinuousTaskEvenWithNoCompletedRounds() = runTest {
        var completed = 0
        val driver = Driver().apply { empty = true }
        val runner = VisitRunner(backgroundScope, driver, { testScheduler.currentTime }, onCompleted = { completed++ })
        runner.start(plan.copy(mode = VisitRunMode.CONTINUOUS), VisitExecution.FORMAL); runCurrent()
        assertEquals(VisitStatus.COMPLETED, runner.state.value.status)
        assertEquals(0L, runner.state.value.rounds)
        assertEquals(1, completed)
    }

    @Test fun stepTestWaitsForExplicitFinishConfirmationAndNotifiesOnce() = runTest {
        var completed = 0
        val runner = VisitRunner(backgroundScope, Driver(), { testScheduler.currentTime }, onCompleted = { completed++ })
        runner.start(plan, VisitExecution.STEP_TEST); runCurrent()
        repeat(8) { runner.resume(); runCurrent(); assertEquals(0, completed) }
        assertEquals(VisitStatus.FINISH_CONFIRM, runner.state.value.status)
        runner.finishTest(); runner.finishTest()
        assertEquals(VisitStatus.COMPLETED, runner.state.value.status)
        assertEquals(1, completed)
    }

    @Test fun privatePauseStopMismatchAndPendingSubmissionNeverNotify() = runTest {
        var completed = 0
        val driver = Driver()
        val runner = VisitRunner(backgroundScope, driver, { testScheduler.currentTime }, onCompleted = { completed++ })
        runner.start(plan.copy(mode = VisitRunMode.CONTINUOUS), VisitExecution.FORMAL); runCurrent()
        runner.pause(); advanceTimeBy(180_000); runCurrent(); runner.stop()
        driver.pendingSubmission = true; driver.reject = true
        runner.start(plan, VisitExecution.FORMAL); runCurrent()
        assertEquals(VisitStatus.ERROR, runner.state.value.status)
        assertTrue(runner.state.value.pendingSubmission)
        runner.stop(); advanceTimeBy(180_000); runCurrent()
        assertEquals(0, completed)
    }

    @Test fun finishingReminderCollectorCannotCancelCompletionVibration() = runTest {
        val played = mutableListOf<List<Long>>()
        var cancellations = 0
        val vibration = RunVibration({ testScheduler.currentTime }, { played += it.toList(); true }, { cancellations++ })
        val runner = VisitRunner(backgroundScope, Driver(), { testScheduler.currentTime }, onCompleted = { vibration.complete(CompletionVibration.TRIPLE) })
        backgroundScope.launchVisitRunReminder(runner.state, intervalMs = 10_000,
            remind = { vibration.remind() }, cancelReminder = { vibration.cancelRunReminder() })
        runner.start(plan.copy(mode = VisitRunMode.COUNT, countText = "2"), VisitExecution.FORMAL)
        runCurrent(); advanceTimeBy(61_000); runCurrent()
        assertEquals(VisitStatus.COMPLETED, runner.state.value.status)
        assertEquals(CompletionVibration.TRIPLE.timings, played.last())
        assertEquals(0, cancellations)
        val count = played.size
        advanceTimeBy(180_000); runCurrent()
        assertEquals(count, played.size)
    }

    @Test fun completionHasPriorityOverOtherRemindersAndDisconnectCanCancelIt() {
        var time = 0L
        val played = mutableListOf<List<Long>>()
        var cancellations = 0
        val vibration = RunVibration({ time }, { played += it.toList(); true }, { cancellations++ })
        assertTrue(vibration.remindBeforeBreak())
        assertFalse(vibration.remind())
        assertTrue(vibration.complete(CompletionVibration.LONG))
        vibration.cancelRunReminder()
        assertFalse(vibration.remind()); assertFalse(vibration.remindBeforeBreak())
        assertEquals(0, cancellations)
        time = 800
        assertTrue(vibration.remind())
        vibration.complete(CompletionVibration.DOUBLE); vibration.cancel()
        assertEquals(1, cancellations)
        assertTrue(vibration.remind())
    }

    @Test fun offAndUnavailableVibratorDoNotPlayOrBlockExistingReminders() {
        var attempts = 0
        val vibration = RunVibration({ 0L }, { attempts++; false }, {})
        assertFalse(vibration.complete(CompletionVibration.OFF)); assertEquals(0, attempts)
        assertFalse(vibration.complete(CompletionVibration.DOUBLE))
        assertFalse(vibration.remindBeforeBreak()); assertFalse(vibration.remind())
        assertEquals(3, attempts)
        assertEquals(CompletionVibration.DOUBLE, CompletionVibration.restored(null))
        assertEquals(CompletionVibration.DOUBLE, CompletionVibration.restored("invalid"))
    }

    @Test fun optionalReminderFailureDoesNotChangeCompletedResult() = runTest {
        val engine = ClickEngine(backgroundScope, TapDispatcher { true }, { testScheduler.currentTime }, { error("vibrator unavailable") })
        engine.start(ClickConfig(points = listOf(point), stopMode = StopMode.ROUNDS, roundLimit = 1)); runCurrent()
        assertEquals(RunStatus.IDLE, engine.state.value.status)
        assertEquals(1L, engine.state.value.rounds)
        val runner = VisitRunner(backgroundScope, Driver(), { testScheduler.currentTime }, onCompleted = { error("vibrator unavailable") })
        runner.start(plan, VisitExecution.FORMAL); runCurrent()
        assertEquals(VisitStatus.COMPLETED, runner.state.value.status)
    }
}
