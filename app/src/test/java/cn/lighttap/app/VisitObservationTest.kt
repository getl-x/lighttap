package cn.lighttap.app

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VisitObservationTest {
    private val first = UiTree("fixture", 1, listOf(UiNode("r", text = "before")))
    private val ready = first.copy(nodes = listOf(UiNode("r", text = "ready")))
    @Test fun freshSeedNeedsOneFurtherMatchingReadInsteadOfStartingOver() = runTest {
        var reads = 0
        assertEquals(ready, VisitObservation { reads++; ready }.stable(ready))
        assertEquals(1, reads)
        assertEquals(80L, testScheduler.currentTime)
    }
    @Test fun seedNeverPassesAfterAChangeWithoutAnotherMatchingRead() = runTest {
        var reads = 0
        assertEquals(ready, VisitObservation { reads++; ready }.stable(first))
        assertEquals(2, reads)
        assertEquals(160L, testScheduler.currentTime)
    }

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
    @Test fun identicalButIncompleteReadsWaitForFieldsBeforeReturning() = runTest {
        var reads = 0
        val observer = VisitObservation { if (++reads < 5) first else ready }
        val result = observer.stable { if (it == first) throw VisitMismatch("无法确定记录核对字段：居住地址") }
        assertEquals(ready, result)
        assertEquals(6, reads)
        assertEquals(400L, testScheduler.currentTime)
    }
    @Test fun confirmedIdentityConflictIsNotTreatedAsAnIncompleteRead() = runTest {
        var reads = 0
        try {
            VisitObservation { reads++; ready }.stable { throw VisitMismatch("表单记录与所选卡片不一致") }
            fail("Conflict ignored")
        } catch (error: VisitMismatch) { assertEquals("表单记录与所选卡片不一致", error.reason) }
        assertEquals(1, reads)
    }
    @Test fun continuouslyChangingPageHasBoundedWait() = runTest {
        var reads = 0
        try {
            VisitObservation { if (++reads % 2 == 0) ready else first }.stable()
            fail("Must time out")
        } catch (_: VisitMismatch) { }
        assertEquals(VisitObservation.STABLE_TIMEOUT_MS, testScheduler.currentTime)
        assertTrue(reads < 110)
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

    @Test fun reportedReadTimingsAllowChangedTreeThenTwoMatchingSnapshots() = runTest {
        var reads = 0
        val events = mutableListOf<VisitDebugEvent>()
        val observer = VisitObservation({ testScheduler.currentTime }, VisitDiagnostics { events += it }) {
            when (++reads) {
                1 -> { delay(321); first }
                2 -> { delay(1_235); ready }
                else -> { delay(1_400); ready }
            }
        }
        assertEquals(ready, observer.stable())
        assertEquals(3, reads)
        assertEquals(3_116L, testScheduler.currentTime)
        assertTrue(events.last().ok == true)
        assertEquals(1, events[1].delta!!.text)
    }

    @Test fun twoIdenticalSlowReadsDoNotTimeOutBeforeSecondCompletes() = runTest {
        assertEquals(ready, VisitObservation { delay(1_500); ready }.stable())
        assertEquals(3_080L, testScheduler.currentTime)
    }

    @Test fun longerBudgetNeverRetriesAnActionAndCancellationIsImmediate() = runTest {
        var reads = 0
        val events = mutableListOf<VisitDebugEvent>()
        val observer = VisitObservation({ testScheduler.currentTime }, VisitDiagnostics { events += it }) {
            reads++; delay(1_500); ready
        }
        val job = launch { observer.stable() }
        runCurrent(); advanceTimeBy(2_000); job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(2, reads)
        assertFalse(events.any { it.ok == true })
    }

    @Test fun snapshotChangeDiagnosticsContainOnlyCounts() {
        val before = UiTree("fixture", 1, listOf(UiNode("r"), UiNode("r/secret-path", text = "private-person"), UiNode("r/remove")))
        val after = before.copy(nodes = listOf(UiNode("r"), UiNode("r/secret-path", text = "private-address",
            description = "private-description", selected = true, bounds = UiBox(1, 1, 5, 5)), UiNode("r/add")))
        val delta = snapshotDelta(before, after)
        assertEquals(1, delta.added); assertEquals(1, delta.removed); assertEquals(1, delta.text)
        assertEquals(1, delta.description); assertEquals(1, delta.controls); assertEquals(1, delta.geometry)
        val json = VisitDebugEvent(VisitDebugKind.STABILITY, delta = delta).json().toString()
        assertFalse(json.contains("private-")); assertFalse(json.contains("secret-path"))
    }
}
