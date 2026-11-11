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
    @Test fun unreadableNativeActionUsesOneGestureWhenNoClickWasAccepted() = runTest {
        var nodes = 0; var gestures = 0
        dispatchVisitClick(true, { nodes++; false }, { gestures++ })
        assertEquals(1, nodes); assertEquals(1, gestures)
    }
    @Test fun submissionKeepsExactlyOneGestureWithoutNodeAction() = runTest {
        var nodes = 0; var gestures = 0
        dispatchVisitClick(false, { nodes++; true }, { gestures++ })
        assertEquals(0, nodes); assertEquals(1, gestures)
    }
}
