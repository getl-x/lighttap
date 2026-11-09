package cn.lighttap.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VisitSurfaceGuardTest {
    private val area = UiBox(100, 200, 400, 500)
    @Test fun clearsBeforeReadAndRestoresOnBothSuccessAndFailure() = runTest {
        var hidden = false; var restores = 0
        val guard = VisitSurfaceGuard({ hidden = true; assertEquals(listOf(area), it); true }, { hidden = false; restores++ })
        val value = guard.clear(listOf(area)) { assertTrue(hidden); 42 }
        assertEquals(42, value); assertFalse(hidden); assertEquals(150L, testScheduler.currentTime)
        try { guard.clear(listOf(area)) { assertTrue(hidden); throw VisitChoiceUncertain() }; fail("Must throw") }
        catch (_: VisitChoiceUncertain) { }
        assertFalse(hidden); assertEquals(2, restores)
    }
    @Test fun cancellationDuringWindowSettleRestoresAndNeverDispatches() = runTest {
        var hidden = false; var actions = 0
        val guard = VisitSurfaceGuard({ hidden = true; true }, { hidden = false })
        val job = launch { guard.clear(listOf(area)) { actions++ } }
        runCurrent(); assertTrue(hidden)
        advanceTimeBy(50); job.cancelAndJoin()
        assertFalse(hidden); assertEquals(0, actions)
    }
    @Test fun unrelatedOverlayNeedsNoWaitOrRestore() = runTest {
        var restored = false
        val guard = VisitSurfaceGuard({ false }, { restored = true })
        guard.clear(listOf(area)) { }
        assertFalse(restored); assertEquals(0L, testScheduler.currentTime)
    }
    @Test fun cancellationDuringConcealDispatcherHopStillRestores() = runTest {
        var hidden = false; var operations = 0
        val guard = VisitSurfaceGuard({ hidden = true; delay(100); true }, { hidden = false })
        val job = launch { guard.clear(listOf(area)) { operations++ } }
        runCurrent(); assertTrue(hidden)
        advanceTimeBy(50); job.cancelAndJoin()
        assertFalse(hidden); assertEquals(0, operations)
    }
    @Test fun serializesOverlappingReadersAndRestoresOnlyAfterEachFinishes() = runTest {
        var hidden = false; val phases = mutableListOf<Int>()
        val guard = VisitSurfaceGuard({ assertFalse(hidden); hidden = true; true }, { hidden = false })
        val first = launch { guard.clear(listOf(area)) { phases += 1; delay(100); assertTrue(hidden); phases += 2 } }
        val second = launch { guard.clear(listOf(area)) { phases += 3; assertTrue(hidden) } }
        first.join(); second.join()
        assertEquals(listOf(1, 2, 3), phases); assertFalse(hidden)
    }
}
