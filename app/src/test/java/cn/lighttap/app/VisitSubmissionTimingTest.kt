package cn.lighttap.app

import org.junit.Assert.*
import org.junit.Test

class VisitSubmissionTimingTest {
    @Test fun exactSixtySecondsStillNeedsOneSecondAndRestartUsesSameStamp() {
        val saved = SubmissionStamp(5_000, 8)
        assertEquals(1_000L, submissionWaitMs(saved.copy(), 65_000, 8, 61_000))
        assertEquals(0L, submissionWaitMs(saved, 66_000, 8, 61_000))
        assertEquals(22_000L, submissionWaitMs(saved, 66_000, 8, 83_000))
    }
    @Test fun rebootWaitsUntilNewBootIsOldEnough() {
        val saved = SubmissionStamp(900_000, 8)
        assertEquals(41_000L, submissionWaitMs(saved, 20_000, 9, 61_000))
        assertEquals(0L, submissionWaitMs(saved, 62_000, 9, 61_000))
    }
    @Test fun shortIntervalsCannotBypassMinimumAndFreshStateHasNoPastSubmit() {
        assertEquals(61_000L, submissionWaitMs(SubmissionStamp(5_000, 8), 5_000, 8, 0))
        assertEquals(0L, submissionWaitMs(null, 5_000, 8, 61_000))
    }
    @Test fun migrateShortOldWaitsAndPreserveLongerUserValues() {
        for (saved in listOf(null, "0", "60", "-1", "bad", "86401")) assertEquals("61", restoredSubmissionInterval(saved))
        assertEquals("62", restoredSubmissionInterval("62"))
        assertEquals("83", restoredSubmissionInterval("83"))
    }
}
