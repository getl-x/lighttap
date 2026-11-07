package cn.lighttap.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VisitObservationTest {
    private val first = UiTree("fixture", 1, listOf(UiNode("r", text = "before")))
    private val ready = first.copy(nodes = listOf(UiNode("r", text = "ready")))

    @Test fun transientPublicationIsObservedUntilTwoSnapshotsAgree() = runTest {
        var reads = 0
        val observer = VisitObservation {
            when (++reads) {
                1 -> first
                2 -> throw VisitReadPending("publishing")
                3 -> first
                else -> ready
            }
        }
        assertEquals(ready, observer.stable())
        assertEquals(5, reads)
        assertEquals(320L, testScheduler.currentTime)
    }
    @Test fun differentForegroundIsNeverRetried() = runTest {
        var reads = 0
        try {
            VisitObservation { reads++; throw VisitMismatch("wrong target") }.stable()
            fail("Must reject")
        } catch (error: VisitMismatch) { assertEquals("wrong target", error.reason) }
        assertEquals(1, reads)
    }
    @Test fun continuouslyChangingPageHasBoundedWait() = runTest {
        var reads = 0
        try {
            VisitObservation { if (++reads % 2 == 0) ready else first }.stable()
            fail("Must time out")
        } catch (_: VisitMismatch) { }
        assertEquals(3_000L, testScheduler.currentTime)
        assertTrue(reads < 40)
    }
    @Test fun cancellingObservationDoesNotResumeOrRetry() = runTest {
        var reads = 0
        val job = launch { VisitObservation { reads++; throw VisitReadPending("publishing") }.read() }
        runCurrent(); advanceTimeBy(200); job.cancelAndJoin()
        val count = reads
        advanceTimeBy(30_000); runCurrent()
        assertEquals(count, reads)
        assertTrue(job.isCancelled)
    }
    @Test fun snapshotIndexesAreLocalToEachCopyAndDontAffectEquality() {
        val a = ready.copy(nodes = ready.nodes + UiNode("r/0", text = "走访"))
        a.exact("走访"); a.under("r"); a.node("r/0")
        assertEquals(a, a.copy())
        assertTrue(a.copy(nodes = ready.nodes).exact("走访").isEmpty())
        assertEquals(listOf("r/0", "r"), a.ancestors(a.node("r/0")).map { it.path })
    }
    @Test fun fingerprintEncodingRemainsCompatibleWithPersistedFences() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hashText(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hashText("abc"))
    }
    @Test fun optionalInlineTextDoesNotChangeSnapshotButControlsArePreserved() {
        val label = UiNode("r/0", text = "居住地址", className = "android.widget.TextView")
        val leaf = label.copy(path = "r/0/0", bounds = UiBox(2, 2, 10, 10))
        val nodes = listOf(UiNode("r"), label, leaf)
        assertEquals(nodes.dropLast(1), withoutRedundantTextLeaves(nodes))
        assertEquals(3, withoutRedundantTextLeaves(nodes.dropLast(1) + leaf.copy(selected = true)).size)
        assertEquals(3, withoutRedundantTextLeaves(nodes.dropLast(1) + leaf.copy(editable = true)).size)
    }
}
