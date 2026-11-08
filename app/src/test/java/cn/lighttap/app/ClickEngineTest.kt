package cn.lighttap.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClickEngineTest {
    @Test fun longWaitAndPauseDoNotPollAndResumeKeepsExactRemainder() = runTest {
        var clockReads = 0
        val calls = mutableListOf<Long>()
        val engine = ClickEngine(backgroundScope, TapDispatcher { calls += testScheduler.currentTime; true }, { clockReads++; testScheduler.currentTime })
        engine.start(ClickConfig(points = listOf(ClickPoint(1, 10f, 10f, 10)), startDelayMs = 60_000, stopMode = StopMode.ROUNDS, roundLimit = 1))
        advanceTimeBy(12_345); runCurrent(); engine.pause(); runCurrent()
        assertEquals(47_655L, engine.state.value.waitRemainingMs)
        val pausedReads = clockReads
        advanceTimeBy(600_000); runCurrent()
        assertEquals("Paused worker and ticker must both suspend", pausedReads, clockReads)
        assertTrue("Whole-second labels must not poll every 20ms", pausedReads < 300)
        engine.resume(); runCurrent()
        advanceTimeBy(47_654); runCurrent(); assertTrue(calls.isEmpty())
        advanceTimeBy(1); runCurrent(); assertEquals(listOf(660_000L), calls)
    }
    @Test fun editingPausedLongWaitDiscardsOldDeadlineImmediately() = runTest {
        val calls = mutableListOf<List<Long>>()
        val engine = engine(calls)
        engine.start(ClickConfig(points = listOf(p1), startDelayMs = 60_000))
        advanceTimeBy(500); runCurrent(); engine.pause(); runCurrent()
        engine.configure(ClickConfig(points = listOf(p2), stopMode = StopMode.ROUNDS, roundLimit = 1))
        engine.resume(); runCurrent()
        assertEquals(listOf(listOf(2L)), calls)
    }
    private val p1 = ClickPoint(1, 100f, 200f, 100)
    private val p2 = ClickPoint(2, 200f, 300f, 200)
    private val p3 = ClickPoint(3, 300f, 400f, 300)
    private fun TestScope.engine(calls: MutableList<List<Long>>, times: MutableList<Long> = mutableListOf(), latency: Long = 50): ClickEngine =
        ClickEngine(backgroundScope, TapDispatcher { points -> calls += points.map { it.id }; times += testScheduler.currentTime; delay(latency); true }, { testScheduler.currentTime })

    @Test fun singleStopsAtExactlyTenRounds() = runTest {
        val calls = mutableListOf<List<Long>>()
        val e = engine(calls)
        e.start(ClickConfig(points = listOf(p1, p2), stopMode = StopMode.ROUNDS, roundLimit = 10))
        advanceTimeBy(2000); runCurrent()
        assertEquals(10, calls.size); assertTrue(calls.all { it == listOf(1L) })
        assertEquals(10L, e.state.value.rounds); assertEquals(RunStatus.IDLE, e.state.value.status)
    }
    @Test fun sequenceUsesIndividualGapsAndCountsFullRounds() = runTest {
        val calls = mutableListOf<List<Long>>(); val times = mutableListOf<Long>(); val e = engine(calls, times)
        e.start(ClickConfig(mode = ClickMode.SEQUENCE, points = listOf(p1, p2, p3), stopMode = StopMode.ROUNDS, roundLimit = 2))
        advanceTimeBy(2000); runCurrent()
        assertEquals(listOf(1L, 2L, 3L, 1L, 2L, 3L), calls.flatten())
        assertEquals(listOf(0L, 150L, 400L, 750L, 900L, 1150L), times)
        assertEquals(2L, e.state.value.rounds)
    }
    @Test fun simultaneousDispatchesOneGroupPerRound() = runTest {
        val calls = mutableListOf<List<Long>>(); val times = mutableListOf<Long>(); val e = engine(calls, times)
        e.start(ClickConfig(mode = ClickMode.SIMULTANEOUS, points = listOf(p1, p2, p3), groupIntervalMs = 80, stopMode = StopMode.ROUNDS, roundLimit = 2))
        advanceTimeBy(1000); runCurrent()
        assertEquals(listOf(listOf(1L, 2L, 3L), listOf(1L, 2L, 3L)), calls)
        assertEquals(listOf(0L, 130L), times)
    }
    @Test fun pausePreservesCursorAndExcludesPausedTime() = runTest {
        val calls = mutableListOf<List<Long>>(); val e = engine(calls)
        e.start(ClickConfig(mode = ClickMode.SEQUENCE, points = listOf(p1, p2, p3)))
        advanceTimeBy(70); runCurrent(); e.pause()
        val pausedElapsed = e.state.value.elapsedMs
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, calls.size); assertEquals(pausedElapsed, e.state.value.elapsedMs); assertEquals(1, e.state.value.nextIndex)
        e.resume(); advanceTimeBy(100); runCurrent()
        assertEquals(listOf(1L, 2L), calls.flatten())
    }
    @Test fun durationStopsDuringLongGapAndExcludesPause() = runTest {
        val calls = mutableListOf<List<Long>>(); val e = engine(calls)
        e.start(ClickConfig(points = listOf(p1.copy(intervalMs = 60_000)), stopMode = StopMode.DURATION, durationMs = 1_000))
        advanceTimeBy(100); runCurrent(); e.pause()
        advanceTimeBy(10_000); runCurrent(); assertEquals(RunStatus.PAUSED, e.state.value.status)
        e.resume(); advanceTimeBy(920); runCurrent()
        assertEquals(RunStatus.IDLE, e.state.value.status); assertEquals(1, calls.size)
        assertTrue(e.state.value.elapsedMs in 1_000..1_020)
    }
    @Test fun duplicateStartDoesNotLaunchAnotherWorker() = runTest {
        val calls = mutableListOf<List<Long>>(); val e = engine(calls)
        val plan = ClickConfig(points = listOf(p1))
        e.start(plan); e.start(plan); advanceTimeBy(100); runCurrent()
        assertEquals(1, calls.size)
        e.pause(); e.start(plan); advanceTimeBy(1000); runCurrent(); assertEquals(1, calls.size)
    }
    @Test fun stopClearsProgressAndNoMoreClicksAreQueued() = runTest {
        val calls = mutableListOf<List<Long>>(); val e = engine(calls)
        e.start(ClickConfig(points = listOf(p1)))
        advanceTimeBy(25); runCurrent(); e.stop(); advanceTimeBy(500); runCurrent()
        assertEquals(1, calls.size); assertEquals(RuntimeState(message = "已停止"), e.state.value)
        e.start(ClickConfig(points = listOf(p2), stopMode = StopMode.ROUNDS, roundLimit = 1))
        advanceTimeBy(100); runCurrent()
        assertEquals(listOf(1L, 2L), calls.flatten()); assertEquals(1L, e.state.value.rounds)
    }
    @Test fun slowDispatchNeverOverlapsOrCatchesUp() = runTest {
        val calls = mutableListOf<List<Long>>(); val times = mutableListOf<Long>(); val e = engine(calls, times, latency = 500)
        e.start(ClickConfig(points = listOf(p1.copy(intervalMs = 10)), stopMode = StopMode.ROUNDS, roundLimit = 3))
        advanceTimeBy(2000); runCurrent()
        assertEquals(listOf(0L, 510L, 1020L), times); assertEquals(3, calls.size)
    }
    @Test fun cancelledGestureStopsWithError() = runTest {
        val e = ClickEngine(backgroundScope, TapDispatcher { false }, { testScheduler.currentTime })
        e.start(ClickConfig(points = listOf(p1))); advanceTimeBy(100); runCurrent()
        assertEquals(RunStatus.ERROR, e.state.value.status); assertEquals(0L, e.state.value.rounds)
    }
    @Test fun editWhilePausedRestartsSequenceAndKeepsCompletedRounds() = runTest {
        val calls = mutableListOf<List<Long>>(); val e = engine(calls)
        e.start(ClickConfig(mode = ClickMode.SEQUENCE, points = listOf(p1, p2)))
        advanceTimeBy(430); runCurrent(); e.pause()
        assertEquals(1L, e.state.value.rounds)
        e.configure(ClickConfig(mode = ClickMode.SEQUENCE, points = listOf(p3, p2)))
        assertEquals(0, e.state.value.nextIndex)
        e.resume(); advanceTimeBy(80); runCurrent()
        assertEquals(3L, calls.last().first()); assertEquals(1L, e.state.value.rounds)
    }
    @Test fun virtualThirtyMinuteRunHasStableCounts() = runTest {
        val calls = mutableListOf<List<Long>>(); val e = engine(calls)
        e.start(ClickConfig(points = listOf(p1), stopMode = StopMode.DURATION, durationMs = 1_800_000))
        advanceTimeBy(1_800_100); runCurrent()
        assertEquals(12_000, calls.size); assertEquals(12_000L, e.state.value.rounds)
        assertEquals(RunStatus.IDLE, e.state.value.status)
    }
    @Test fun deadlineDoesNotDispatchClickAtExactBoundary() = runTest {
        val calls = mutableListOf<List<Long>>(); val times = mutableListOf<Long>(); val e = engine(calls, times)
        e.start(ClickConfig(points = listOf(p1.copy(intervalMs = 950)), stopMode = StopMode.DURATION, durationMs = 1_000))
        advanceTimeBy(1_100); runCurrent()
        assertEquals(listOf(0L), times); assertEquals(RunStatus.IDLE, e.state.value.status)
    }
    @Test fun loweredRoundLimitStopsBeforeAnotherDispatch() = runTest {
        val calls = mutableListOf<List<Long>>(); val e = engine(calls)
        e.start(ClickConfig(points = listOf(p1)))
        advanceTimeBy(220); runCurrent(); e.pause()
        assertEquals(2L, e.state.value.rounds)
        e.configure(ClickConfig(points = listOf(p1), stopMode = StopMode.ROUNDS, roundLimit = 1))
        e.resume(); advanceTimeBy(100); runCurrent()
        assertEquals(2, calls.size); assertEquals(RunStatus.IDLE, e.state.value.status)
    }
    @Test fun pauseTouchCancellingGestureCanResumeWithoutAnError() = runTest {
        var attempts = 0
        val e = ClickEngine(backgroundScope, TapDispatcher { attempts++; delay(50); attempts > 1 }, { testScheduler.currentTime })
        e.start(ClickConfig(points = listOf(p1), stopMode = StopMode.ROUNDS, roundLimit = 1))
        advanceTimeBy(25); runCurrent(); e.pause(); advanceTimeBy(1000); runCurrent()
        assertEquals(RunStatus.PAUSED, e.state.value.status); assertEquals(0L, e.state.value.rounds)
        e.resume(); advanceTimeBy(100); runCurrent()
        assertEquals(2, attempts); assertEquals(1L, e.state.value.rounds); assertEquals(RunStatus.IDLE, e.state.value.status)
    }}
