package cn.lighttap.app

import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DiagnosedVisitUiAccessTest {
    @Test fun recordsTimingAndFailureButNeverNodeTextValuesOrHashes() = runTest {
        val events = mutableListOf<VisitDebugEvent>()
        val page = UiTree("fixture", 1, listOf(UiNode("r"), UiNode("r/0", text = "待办", selected = true),
            UiNode("r/1", text = "private-identity", editable = true)))
        var actions = 0
        val delegate = object : VisitUiAccess {
            override val target = "fixture"
            override suspend fun capture(): UiTree { kotlinx.coroutines.delay(250); return page }
            override suspend fun perform(tree: UiTree, node: UiNode, action: Int, value: String?) {
                actions++; assertEquals("private-input", value)
                throw VisitMismatch("private-platform-error")
            }
        }
        val observed = DiagnosedVisitUiAccess(delegate, VisitDiagnostics { events += it }, { testScheduler.currentTime })
        assertEquals(page, observed.capture())
        val capture = events.single()
        assertEquals(250L, capture.durationMs); assertEquals(true, capture.pendingSelected)
        try { observed.perform(page, page.nodes.last(), AccessibilityNodeInfo.ACTION_SET_TEXT, "private-input"); fail("Should rethrow") }
        catch (e: VisitMismatch) { assertEquals("private-platform-error", e.reason) }
        assertEquals(1, actions)
        val export = events.joinToString { it.json().toString() }
        assertFalse(export.contains("private-identity")); assertFalse(export.contains("private-input")); assertFalse(export.contains("private-platform-error"))
        assertEquals(false, events.last().ok)
    }
}
