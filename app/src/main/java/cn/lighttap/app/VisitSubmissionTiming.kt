package cn.lighttap.app

const val MIN_SUBMISSION_INTERVAL_MS = 61_000L
const val DEFAULT_SUBMISSION_INTERVAL_SECONDS = "61"

data class SubmissionStamp(val elapsedMs: Long, val bootCount: Int)

/** Monotonic time ignores wall-clock edits. After reboot, waiting until the
 * current boot is old enough also guarantees enough time since the old submit. */
fun submissionWaitMs(stamp: SubmissionStamp?, now: Long, bootCount: Int, intervalMs: Long): Long {
    if (stamp == null) return 0
    val interval = intervalMs.coerceAtLeast(MIN_SUBMISSION_INTERVAL_MS)
    val age = if (stamp.bootCount == bootCount && now >= stamp.elapsedMs) now - stamp.elapsedMs else now
    return (interval - age.coerceAtLeast(0)).coerceAtLeast(0)
}

fun restoredSubmissionInterval(saved: String?): String =
    saved?.toLongOrNull()?.takeIf { it in 61L..86_400L }?.toString() ?: DEFAULT_SUBMISSION_INTERVAL_SECONDS
