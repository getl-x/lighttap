package cn.lighttap.app

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class VisitNavigationClickTest {
    @Test fun openingUsesOneNodeActionAndNeverAddsASecondGesture() = runTest {
        var nodes = 0; var gestures = 0
        dispatchVisitClick(true, { nodes++; true }, { gestures++ })
        assertEquals(1, nodes); assertEquals(0, gestures)
    }
    @Test fun rejectedNavigationDoesNotSilentlyFallBackToAnotherClick() = runTest {
        var nodes = 0; var gestures = 0
        try { dispatchVisitClick(true, { nodes++; false }, { gestures++ }); fail("Must report rejection") }
        catch (_: VisitNavigationRejected) { }
        assertEquals(1, nodes); assertEquals(0, gestures)
    }
    @Test fun submissionKeepsExactlyOneGestureWithoutNodeAction() = runTest {
        var nodes = 0; var gestures = 0
        dispatchVisitClick(false, { nodes++; true }, { gestures++ })
        assertEquals(0, nodes); assertEquals(1, gestures)
    }

    @Test fun onlyExplicitGestureRejectionProvesThatNoClickWasSent() = runTest {
        try { awaitVisitGesture { false }; fail("Expected a system rejection") }
        catch (_: VisitPreActionMismatch) { }

        for (dispatch in listOf<((Boolean) -> Unit) -> Boolean>({ complete -> complete(false); true }, { true })) {
            try { awaitVisitGesture(dispatch); fail("Expected an uncertain result") }
            catch (error: VisitMismatch) { assertFalse(error is VisitPreActionMismatch) }
        }
        awaitVisitGesture { complete -> complete(true); true }
    }
}
