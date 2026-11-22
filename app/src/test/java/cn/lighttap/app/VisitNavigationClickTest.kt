package cn.lighttap.app

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class VisitNavigationClickTest {
    private val history = UiNode("r/0", text = "走访记录", clickable = true, bounds = UiBox(0, 10, 90, 40))
    private val visit = UiNode("r/1", text = "走访", clickable = true, bounds = UiBox(100, 10, 190, 40))
    private val list = UiTree("fixture", 1, listOf(UiNode("r"), history, visit))

    @Test fun incompletePreClickReadCanRecoverOnTheSameButton() = runTest {
        var reads = 0
        val matches = matchesVisitNavigation(list, visit) {
            if (++reads == 1) throw VisitReadPending("界面结构正在变化")
            list
        }
        assertTrue(matches)
        assertEquals(2, reads)
    }

    @Test fun recoveredReadMustStillMatchTheWindowAndButton() = runTest {
        val moved = visit.copy(bounds = UiBox(110, 10, 200, 40))
        for (changed in listOf(list.copy(windowId = 2), list.copy(nodes = listOf(list.node("r"), history, moved)))) {
            var reads = 0
            assertFalse(matchesVisitNavigation(list, visit) {
                if (++reads == 1) throw VisitReadPending("界面结构正在变化")
                changed
            })
            assertEquals(2, reads)
        }
    }

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
