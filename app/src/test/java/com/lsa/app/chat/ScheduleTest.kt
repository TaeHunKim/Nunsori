package com.lsa.app.chat

import com.lsa.app.chat.schedule.Schedule
import com.lsa.app.data.db.ScheduledTaskEntity
import com.lsa.app.data.db.TaskRepeat
import com.lsa.app.data.db.TaskStatus
import com.lsa.app.data.db.TaskTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ScheduleTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun task(repeat: TaskRepeat, minute: Int = 0, mask: Int = 0, every: Int = 0, once: Long? = null, trigger: TaskTrigger = TaskTrigger.TIME) =
        ScheduledTaskEntity(
            sessionId = 1, trigger = trigger, status = TaskStatus.ACTIVE, instruction = "x", repeat = repeat,
            atMinuteOfDay = minute, weekdayMask = mask, intervalMinutes = every, onceAtMs = once, createdAt = 0,
        )

    @Test fun dailyPicksTodayIfStillAheadElseTomorrow() {
        val t = task(TaskRepeat.DAILY, minute = 9 * 60)
        assertEquals(ms(2026, 11, 2, 9, 0), Schedule.nextRun(t, ms(2026, 11, 2, 8, 59), zone))
        assertEquals("strictly after", ms(2026, 11, 3, 9, 0), Schedule.nextRun(t, ms(2026, 11, 2, 9, 0), zone))
    }

    @Test fun weeklyFindsTheNextSelectedWeekday() {
        // 2026-11-02 is a Monday. Wednesday + Friday at 18:30.
        val t = task(TaskRepeat.WEEKLY, minute = 18 * 60 + 30, mask = (1 shl 2) or (1 shl 4))
        assertEquals(ms(2026, 11, 4, 18, 30), Schedule.nextRun(t, ms(2026, 11, 2, 12, 0), zone))
        assertEquals(ms(2026, 11, 6, 18, 30), Schedule.nextRun(t, ms(2026, 11, 4, 18, 30), zone))
        assertEquals("wraps into the next week", ms(2026, 11, 9 + 2, 18, 30), Schedule.nextRun(t, ms(2026, 11, 6, 18, 30), zone))
    }

    @Test fun weeklyWithoutDaysNeverFires() {
        assertNull(Schedule.nextRun(task(TaskRepeat.WEEKLY, minute = 60), 0, zone))
    }

    @Test fun intervalAddsTheStepAndHasAFloor() {
        val now = ms(2026, 11, 2, 12, 0)
        assertEquals(now + 30 * 60_000L, Schedule.nextRun(task(TaskRepeat.EVERY_MINUTES, every = 30), now, zone))
        assertEquals("never faster than the minimum", now + Schedule.MIN_INTERVAL_MINUTES * 60_000L, Schedule.nextRun(task(TaskRepeat.EVERY_MINUTES, every = 1), now, zone))
    }

    @Test fun onceFiresOnlyWhileItIsInTheFuture() {
        val at = ms(2026, 11, 5, 7, 0)
        val t = task(TaskRepeat.ONCE, once = at)
        assertEquals(at, Schedule.nextRun(t, at - 1, zone))
        assertNull(Schedule.nextRun(t, at, zone))
    }

    @Test fun fileTasksHaveNoClock() {
        assertNull(Schedule.nextRun(task(TaskRepeat.ONCE, trigger = TaskTrigger.FILE), 0, zone))
    }

    @Test fun dailyAcrossDaylightSavingKeepsTheLocalTime() {
        val ny = ZoneId.of("America/New_York") // clocks go forward on 2026-03-08
        fun nyMs(d: Int, h: Int) = LocalDateTime.of(2026, 3, d, h, 0).atZone(ny).toInstant().toEpochMilli()
        val t = task(TaskRepeat.DAILY, minute = 9 * 60)
        assertEquals(nyMs(8, 9), Schedule.nextRun(t, nyMs(7, 9), ny))
        assertEquals("23 hours later in absolute time", 23 * 3600_000L, nyMs(8, 9) - nyMs(7, 9))
    }

    @Test fun parsesTimesAndWeekdays() {
        assertEquals(9 * 60, Schedule.parseTime("9"))
        assertEquals(21 * 60 + 5, Schedule.parseTime(" 21:05 "))
        assertNull(Schedule.parseTime("25:00"))
        assertNull(Schedule.parseTime("noon"))
        assertEquals((1 shl 0) or (1 shl 2), Schedule.parseWeekdays("mon, Wednesday"))
        assertEquals((1 shl 5) or (1 shl 6), Schedule.parseWeekdays("6,7"))
        assertNull(Schedule.parseWeekdays("someday"))
    }
}
