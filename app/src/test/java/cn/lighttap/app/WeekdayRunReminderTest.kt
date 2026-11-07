package cn.lighttap.app

import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class WeekdayRunReminderTest {
    private val running = VisitState(status = VisitStatus.RUNNING, execution = VisitExecution.FORMAL)
    private fun time(day: Int, minute: Int = 15, second: Int = 0) = LocalDateTime.of(2026, 11, day, 11, minute, second)
    private fun rule() = WeekdayRunReminder({ null }, { _, _ -> true })

    @Test fun eachWeekdayTriggersOnceAndWeekendsDoNot() {
        val saved = mutableMapOf<WorkReminderSlot, String>()
        val rule = WeekdayRunReminder({ saved[it] }, { slot, date -> saved[slot] = date; true })
        for (day in 9..13) {
            assertNotNull(rule.claim(time(day), running))
            assertNull(rule.claim(time(day, second = 30), running))
            assertNotNull(rule.claim(time(day).withHour(17).withMinute(0), running))
            assertNull(rule.claim(time(day).withHour(17).withMinute(0).withSecond(20), running))
        }
        for (day in 14..15) {
            assertNull(rule.claim(time(day), running))
            assertNull(rule.claim(time(day).withHour(17).withMinute(0), running))
        }
        assertEquals(setOf("2026-11-13"), saved.values.toSet())
    }

    @Test fun onlyThe1115And1700MinutesAreEligible() {
        val rule = rule()
        assertNull(rule.claim(time(9, 14, 59), running))
        assertNull(rule.claim(time(9, 16), running))
        assertNull(rule.claim(time(9).withHour(23), running))
        assertNotNull(rule.claim(time(9, second = 59), running.copy(status = VisitStatus.WAITING)))
        assertNull(rule.claim(time(9).withHour(16).withMinute(59).withSecond(59), running))
        assertEquals(WorkReminderSlot.AFTERNOON, rule.claim(time(9).withHour(17).withMinute(0), running)!!.slot)
        assertNull(rule.claim(time(9).withHour(17).withMinute(1), running))
    }

    @Test fun pausedCompletedIdleErrorAndStepTestDoNotConsumeDailyReminder() {
        val rule = rule()
        for (status in VisitStatus.entries.filterNot { it in setOf(VisitStatus.RUNNING, VisitStatus.WAITING) }) {
            assertNull(rule.claim(time(9), running.copy(status = status)))
        }
        assertNull(rule.claim(time(9), running.copy(execution = VisitExecution.STEP_TEST)))
        assertNotNull(rule.claim(time(9), running))
    }

    @Test fun persistedDatePreventsServiceRecreationOrClockRollbackRepeats() {
        val saved = mutableMapOf<WorkReminderSlot, String>()
        assertNotNull(WeekdayRunReminder({ saved[it] }, { slot, date -> saved[slot] = date; true }).claim(time(9), running))
        val recreated = WeekdayRunReminder({ saved[it] }, { slot, date -> saved[slot] = date; true })
        assertNull(recreated.claim(time(9, second = 50), running))
        assertNull(recreated.claim(time(9, 14), running))
        assertNull(recreated.claim(time(9), running))
        assertNotNull(recreated.claim(time(9).withHour(17).withMinute(0), running))
        assertNotNull(recreated.claim(time(10), running))
    }

    @Test fun failedPersistenceStillRemindsAndDoesNotRepeatInCurrentProcess() {
        val rule = WeekdayRunReminder({ null }, { _, _ -> false })
        assertNotNull(rule.claim(time(9), running))
        assertNull(rule.claim(time(9, second = 1), running))
    }
}
