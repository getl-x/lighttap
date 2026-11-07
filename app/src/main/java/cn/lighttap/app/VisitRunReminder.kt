package cn.lighttap.app

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal const val VISIT_REMINDER_INTERVAL_MS = 5 * 60_000L

/** Observe the runner without delaying or changing its actions. Ordinary step
 * updates and submission cooldowns do not restart the reminder interval. */
internal fun CoroutineScope.launchVisitRunReminder(
    state: StateFlow<VisitState>,
    intervalMs: Long = VISIT_REMINDER_INTERVAL_MS,
    remind: () -> Unit,
    cancelReminder: () -> Unit = {},
): Job {
    require(intervalMs > 0)
    fun running() = state.value.busy && state.value.execution == VisitExecution.FORMAL
    return launch {
        state.map { it.busy && it.execution == VisitExecution.FORMAL }
            .distinctUntilChanged()
            .collectLatest { active ->
                if (active) try {
                    while (currentCoroutineContext().isActive) {
                        delay(intervalMs)
                        // Check the current state even if cancellation delivery
                        // is queued at the same instant as the timer.
                        if (running()) remind()
                    }
                } finally { cancelReminder() }
            }
    }
}
