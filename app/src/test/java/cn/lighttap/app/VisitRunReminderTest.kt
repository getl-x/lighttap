package cn.lighttap.app

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VisitRunReminderTest {
    private fun running() = VisitState(status = VisitStatus.RUNNING, execution = VisitExecution.FORMAL)

    @Test fun everyFiveMinutesAndStepUpdatesDoNotRestartTimer() = runTest {
        val state = MutableStateFlow(running())
        val reminders = mutableListOf<Long>()
        backgroundScope.launchVisitRunReminder(state, remind = { reminders += testScheduler.currentTime })
        runCurrent()
        repeat(4) {
            advanceTimeBy(60_000)
            state.value = state.value.copy(step = VisitStep.SUBMIT, status = VisitStatus.WAITING, remainingWaitMs = (it + 1) * 1_000L)
            runCurrent()
        }
        advanceTimeBy(59_999); runCurrent(); assertTrue(reminders.isEmpty())
        advanceTimeBy(1); runCurrent(); assertEquals(listOf(300_000L), reminders)
        advanceTimeBy(300_000); runCurrent(); assertEquals(listOf(300_000L, 600_000L), reminders)
        assertEquals(VisitStatus.WAITING, state.value.status)
    }

    @Test fun inactiveStatesAndStepTestNeverRemind() = runTest {
        for (value in listOf(VisitState(), running().copy(status = VisitStatus.PAUSED), running().copy(status = VisitStatus.ERROR),
            running().copy(status = VisitStatus.COMPLETED), running().copy(execution = VisitExecution.STEP_TEST))) {
            var reminders = 0
            val job = backgroundScope.launchVisitRunReminder(MutableStateFlow(value), remind = { reminders++ })
            runCurrent(); advanceTimeBy(900_000); runCurrent()
            assertEquals(0, reminders); job.cancel()
        }
    }

    @Test fun pauseCancelsAndResumeStartsNewFiveMinutes() = runTest {
        val state = MutableStateFlow(running())
        val reminders = mutableListOf<Long>()
        var cancellations = 0
        backgroundScope.launchVisitRunReminder(state, remind = { reminders += testScheduler.currentTime }, cancelReminder = { cancellations++ })
        runCurrent(); advanceTimeBy(299_999)
        state.value = state.value.copy(status = VisitStatus.PAUSED); runCurrent()
        advanceTimeBy(600_000); runCurrent()
        assertTrue(reminders.isEmpty()); assertEquals(1, cancellations)
        state.value = running(); runCurrent()
        advanceTimeBy(299_999); runCurrent(); assertTrue(reminders.isEmpty())
        advanceTimeBy(1); runCurrent(); assertEquals(listOf(1_199_999L), reminders)
    }

    @Test fun stoppingAtDeadlineAndDisconnectDoNotLeaveScheduledReminders() = runTest {
        val state = MutableStateFlow(running())
        var reminders = 0
        val job = backgroundScope.launchVisitRunReminder(state, remind = { reminders++ })
        runCurrent(); advanceTimeBy(300_000)
        state.value = VisitState(); runCurrent()
        assertEquals(0, reminders)
        state.value = running(); runCurrent(); job.cancel(); runCurrent()
        advanceTimeBy(600_000); runCurrent(); assertEquals(0, reminders)
    }

    @Test fun reminderDoesNotDelayOrPauseActualRunner() = runTest {
        val submissions = mutableListOf<Long>()
        val driver = object : VisitDriver {
            override val pendingSubmission = false
            override suspend fun begin() = Unit
            override suspend fun validate(step: VisitStep) = Unit
            override suspend fun execute(step: VisitStep): VisitEffect {
                if (step == VisitStep.SUBMIT) submissions += testScheduler.currentTime
                return VisitEffect()
            }
            override fun release() = Unit
        }
        val runner = VisitRunner(backgroundScope, driver, { testScheduler.currentTime })
        var reminders = 0
        backgroundScope.launchVisitRunReminder(runner.state, remind = { reminders++ })
        runner.start(VisitSettings(targetPackage = "fixture", mode = VisitRunMode.COUNT, countText = "7"), VisitExecution.FORMAL)
        runCurrent(); advanceTimeBy(366_000); runCurrent()
        assertEquals(VisitStatus.COMPLETED, runner.state.value.status)
        assertEquals(1, reminders)
        assertEquals((0L..6L).map { it * 61_000 }, submissions)
        advanceTimeBy(600_000); runCurrent(); assertEquals(1, reminders)
    }
}
