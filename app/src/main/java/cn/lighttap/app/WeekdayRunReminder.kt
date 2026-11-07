package cn.lighttap.app

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalDate

internal enum class WorkReminderSlot(val hour: Int, val minute: Int, val label: String) {
    MORNING(11, 15, "11:15"), AFTERNOON(17, 0, "17:00")
}
internal data class WorkReminderEvent(val date: LocalDate, val slot: WorkReminderSlot)

/** Device local weekday/time; persisted date prevents duplicate reminders
 * when the accessibility service reconnects during the 11:15 minute. */
internal class WeekdayRunReminder(
    loadDate: (WorkReminderSlot) -> String?,
    private val saveDate: (WorkReminderSlot, String) -> Boolean,
) {
    private val lastDates = WorkReminderSlot.entries.associateWith { loadDate(it) }.toMutableMap()
    fun claim(time: LocalDateTime, state: VisitState): WorkReminderEvent? {
        if (!state.busy || state.execution != VisitExecution.FORMAL ||
            time.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) return null
        val slot = WorkReminderSlot.entries.firstOrNull { it.hour == time.hour && it.minute == time.minute } ?: return null
        val date = time.toLocalDate().toString()
        if (date == lastDates[slot]) return null
        lastDates[slot] = date
        // A storage failure must not silence the reminder. The in-memory
        // date still prevents repeats until the process is recreated.
        saveDate(slot, date)
        return WorkReminderEvent(time.toLocalDate(), slot)
    }
}
