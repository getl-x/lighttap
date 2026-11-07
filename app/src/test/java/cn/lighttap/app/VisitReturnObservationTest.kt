package cn.lighttap.app

import org.junit.Assert.*
import org.junit.Test

class VisitReturnObservationTest {
    @Test fun immediateSuccessHintUsesActualClickOnlyWhenUninterrupted() {
        val observer = VisitReturnObservation(10_000)
        assertEquals(10_000L, observer.observe(10_800, true, false, "new", true)!!.anchorMs)
    }
    @Test fun missingHintNeedsTwoNormalFramesAndKeepsFirstTimestamp() {
        val observer = VisitReturnObservation(10_000)
        assertNull(observer.observe(10_800, true, false, "new", false))
        assertEquals(10_800L, observer.observe(11_800, true, false, "new", false)!!.anchorMs)
    }
    @Test fun loadingOnFormOrListAlwaysWinsOverHint() {
        for (ready in listOf(false, true)) {
            val observer = VisitReturnObservation(10_000)
            assertNull(observer.observe(11_000, false, true, if (ready) "new" else null, true))
            assertNull(observer.observe(15_000, false, true, null, false))
            assertNull(observer.observe(16_000, true, false, "new", false))
            assertEquals(16_000L, observer.observe(17_000, true, false, "new", false)!!.anchorMs)
        }
    }
    @Test fun missedSamplingCannotBackdateToClickAndUnknownResetsCandidate() {
        val observer = VisitReturnObservation(10_000)
        assertEquals(13_000L, observer.observe(13_000, true, false, "new", true)!!.anchorMs)
        val fallback = VisitReturnObservation(null)
        assertNull(fallback.observe(15_000, true, false, "new", false))
        assertNull(fallback.observe(16_000, false, false, null, false))
        assertNull(fallback.observe(17_000, true, false, "new", false))
        assertEquals(17_000L, fallback.observe(18_000, true, false, "new", false)!!.anchorMs)
    }
    @Test fun differentListsDoNotCountAsTwoStableSamples() {
        val observer = VisitReturnObservation(null)
        assertNull(observer.observe(1_000, true, false, "one", false))
        assertNull(observer.observe(2_000, true, false, "two", false))
        assertEquals(2_000L, observer.observe(3_000, true, false, "two", false)!!.anchorMs)
    }
    @Test fun pauseOrMissedSampleDiscardsUnconfirmedNormalCandidate() {
        val observer = VisitReturnObservation(0)
        assertNull(observer.observe(1_000, true, false, "new", false))
        assertNull(observer.observe(10_000, true, false, "new", false))
        assertEquals(10_000L, observer.observe(11_000, true, false, "new", false)!!.anchorMs)
    }
    @Test fun consecutiveSlowCapturesConfirmWithoutRestartingFirstNormalTime() {
        val observer = VisitReturnObservation(0)
        assertNull(observer.observe(1_600, true, false, "new", false))
        assertEquals(1_600L, observer.observe(3_200, true, false, "new", false)!!.anchorMs)
    }
    @Test fun foreignOrOldSuccessHintsCannotAuthorizeCurrentSubmit() {
        val signals = VisitSuccessSignals()
        signals.observe("foreign", "target", listOf("新增走访成功！"), 1_100)
        assertFalse(signals.since(1_000, 1_200))
        signals.observe("target", "target", listOf("新增走访成功！"), 900)
        assertFalse(signals.since(1_000, 1_200))
        signals.observe("target", "target", listOf("新增走访成功！"), 1_100)
        assertTrue(signals.since(1_000, 1_200))
    }
    @Test fun dimmedBodyAndWhiteDialogCannotLookLikeNormalList() {
        val w = 72; val h = 96
        assertEquals(ReturnVisual.CLEAR, classifyReturnPixels(IntArray(w*h) { -1 }, w, h))
        val dimmed = IntArray(w*h) { 0xff999999.toInt() }
        assertEquals(ReturnVisual.BLOCKED, classifyReturnPixels(dimmed, w, h))
        for (y in 35..60) for (x in 10..61) dimmed[y*w+x] = -1
        assertEquals(ReturnVisual.BLOCKED, classifyReturnPixels(dimmed, w, h))
        val toast = IntArray(w*h) { -1 }
        for (y in 75..83) for (x in 22..49) toast[y*w+x] = 0xff444444.toInt()
        assertEquals(ReturnVisual.CLEAR, classifyReturnPixels(toast, w, h))
        assertEquals(ReturnVisual.UNKNOWN, classifyReturnPixels(IntArray(w*h) { 0xff2222ff.toInt() }, w, h))
    }
}
